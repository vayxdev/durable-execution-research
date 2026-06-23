# Restate 实现原理(源码级笔记)

> 基于 `restate/` submodule(restatedev/restate @ `3456157`,main)的源码梳理。
> 文中路径均相对于 `restate/` 目录,可直接跳转。
>
> 阅读路线:Part I 给抽象总揽;Part II 跟随一次 client 调用由表入里,每一层回答上一层留下的问题;Part III 是横向专题与对照。

---

# Part I 总揽

## 一句话核心

**Restate = 一个为"调用"而生的 event-sourced 数据库**:把每次函数调用变成一条 partition 日志上的命令流,用户代码产生的每个副作用(调用、sleep、状态写入)都先记入 journal,崩溃后靠重放 journal 把代码"快进"回断点。

换个角度:**用一条复制日志统一了 RPC、队列、K/V 存储、定时器和工作流引擎五件事**,用户代码的执行进度本身成为这条日志上的可恢复状态。

## 全景图

```
        ┌─ ingress(HTTP/Kafka) ─┐
请求 ──▶│  幂等键 → InvocationId │
        └──────────┬────────────┘
                   ▼ InvokeCommand
        ╔══════ Bifrost 日志(每 partition 一条,quorum 复制)══════╗
        ╚══════════╤═══════════════════════════════════════════╝
                   ▼ apply(单事务)
        ┌─ Partition Processor(leader)──────────────────────┐
        │ journal │ state │ timer │ promise │ outbox │ inbox │ ← RocksDB
        └────┬──────────────────────────────────┬───────────┘
             ▼ invoker(HTTP/2 推送+重放 journal)  └─ shuffle → 其他 partition
        ┌─ 你的服务(SDK)─┐
        │ 确定性快进+执行  │
        └────────────────┘
```

## 四个关键设计决策

理解了这四条,其余实现细节都是推论:

1. **日志为唯一真相**(log is the database):一切变更先成为日志记录再生效;RocksDB 里的状态只是日志的物化视图,丢了可重建
2. **反向调用**:用户服务是无状态 HTTP endpoint(可以是 Lambda),server 主动推任务 —— 与 Temporal worker 拉取任务队列方向相反
3. **journal 记用户命令而非系统事件**:恢复时 SDK 重放 journal、对已记录的步骤直接短路返回旧结果,代码被"快进"到断点
4. **共识用量压到最低**:Raft 只管低频元数据;高吞吐数据路径用 sequencer + flexible quorum(一次 RTT、无选举);故障恢复统一建模为日志链上的"seal 旧段、接新段"

## 三种服务抽象

定义在 `crates/types/src/invocation/mod.rs:46`:

| 类型 | 语义 | server 侧实现要点 |
|---|---|---|
| **Service** | 无状态函数 | 直接执行 |
| **Virtual Object** | 按 key 单线程 + K/V state | key → partition_key;Exclusive handler 加锁排队 |
| **Workflow** | run 一次 + durable promise | promise_table 存共享 promise |

---

# Part II 一次调用的旅程:由表入里

以 `POST /greeter/greet` 为主线,从 client 可见的表层一路下钻到字节落盘。

## 第 1 层:client 看到什么

- 一个普通的 HTTP 调用:`POST http://restate:8080/greeter/greet`,同步拿结果(`call`)或投递即返回(`send`,可带 `delay`)
- 可携带 `idempotency-key` header:重试同一个 key,**保证只执行一次**,重复请求直接拿到同一份结果
- 调用 Virtual Object 时 URL 带 key:`POST /counter/my-key/add`,同 key 的请求严格串行

client 无需感知的事实:它打到的只是 ingress,真正的执行可能发生在集群另一台机器、另一个时间点(挂起一个月后恢复),但对 client 而言语义始终是"一次函数调用"。

## 第 2 层:ingress —— 请求变成一条命令

`crates/ingress-http/src/handler/service_handler.rs:82`

1. 解析 target(service/handler/key),校验 schema
2. 生成 `InvocationId`:**幂等键参与生成** —— 相同 target + idempotency_key 确定性地得到相同 ID,这是入口去重的根基
3. 包装成 `InvokeCommand`(`crates/wal-protocol/src/v2/commands.rs`)
4. 按 key hash 出 `partition_key`,查 `PartitionTable` 找到目标 partition,把命令 **append 到该 partition 的 Bifrost 日志**

**请求一旦在日志上提交即不会丢失** —— 此时还没有任何用户代码被执行。`call` 模式下 ingress 挂起等待完成通知;`send` 模式拿到 submit 确认即返回。

留给下一层的问题:日志上的命令谁来消费?

## 第 3 层:partition processor —— 状态机消费日志

`crates/worker/src/partition/state_machine/mod.rs:1250`

每个 partition 有一个 leader processor,顺序 apply 日志记录。处理 `InvokeCommand`:

1. **去重检查**:该 InvocationId 已存在?→ Completed 则直接回存储的结果;in-flight 则 attach 等待
2. **排队检查**(Virtual Object Exclusive):`service_status_table` 中该 key 已锁定?→ 进 inbox/vqueue 排队(`mod.rs:1417`);未锁定 → 上锁继续
3. **初始化 journal**:写 `journal[0] = Input(请求参数)`,status 置为 `Invoked`
4. 向 invoker 发出 `Action::Invoke`

关键机制:**一条日志记录的 apply 产生的所有写入落在同一个 RocksDB 事务里**(`mod.rs:385`)—— journal、status、K/V state、timer、promise、outbox 消息,要么全有要么全无。crash 后重新 apply 同一条记录是幂等的。

partition 本地的表(`crates/storage-api/src/`):

| 表 | 存什么 |
|---|---|
| invocation_status_table | 生命周期:Scheduled → Inboxed → Invoked → Suspended → Completed |
| journal_table | 每个 invocation 的命令序列 |
| state_table | Virtual Object / Workflow 的 K/V state |
| timer_table | sleep / 延迟调用 / 清理任务,按时间序 |
| promise_table | Workflow durable promise |
| outbox_table | 待发往其他 partition 的消息 |
| inbox_table / vqueue | Virtual Object 排队 |

留给下一层的问题:`Action::Invoke` 之后,用户代码在哪里、怎么跑?

## 第 4 层:invoker ↔ SDK —— durable execution 真正发生的地方

`crates/invoker-impl/src/invocation_task/mod.rs:256`,协议 `crates/service-protocol-v4/`

invoker 对用户服务(注册过的 HTTP endpoint)开一条 **HTTP/2 stream**(content-type `application/vnd.restate.invocation.v4`),双向消息流:

```
Server → SDK : StartMessage + 已有 journal 全量(首次只有 Input)
SDK 执行用户代码,每个 ctx.* 产生一条 Command 回传:
SDK → Server : CommandMessage(index=1, Call …)      ← ctx.call()
SDK → Server : CommandMessage(index=2, Sleep …)     ← ctx.sleep()
SDK → Server : CommandMessage(index=3, Run …)       ← ctx.run()
…
Server → SDK : CommandAck / CompletionMessage(回填结果)
SDK → Server : EndMessage(带 Output)或 SuspensionMessage
```

三条核心规则:

1. **命令先 durable 再生效**:SDK 发出的 command 经 `InvokerEffect` 写回 partition 日志、apply 进 journal 后,server 回 ack;SDK 凭 ack 才认为该步骤已提交
2. **恢复 = 重放 + 短路**:crash/重试时 invoker 把已有 journal 推给 SDK(`requires_ack=false`),SDK 重新执行代码,但**遇到已记录的命令不真正执行,直接返回记录的结果**,直到走到第一条新命令 —— 代码被"快进"到断点
3. **挂起是一等公民**:代码 await 一个未完成的结果(sleep、call 返回、awakeable)时,SDK 发 suspension,server 把 status 置为 `Suspended{awaiting_on: [completion_id…]}`(`lifecycle/suspend.rs`),**连接断开,不占任何资源**。等到 completion 写入日志,状态机发现有 Suspended 在等它 → resume(`lifecycle/resume.rs`),重开 stream 重放 journal 继续

`awaiting_on` 用 `UnresolvedFuture`(`crates/types/src/journal_v2/unresolved_future.rs`)表达,支持 all/any 组合 —— 所以 `Promise.all/race` 也能跨崩溃恢复。

journal 命令全集见 `crates/types/src/journal_v2/command.rs:50`:`Input / Output / Call / OneWayCall / Sleep / SetState / GetState / Run / CompleteAwakeable / GetPromise / …`

> 与 Temporal 的差异:Temporal 持久化的是 event history(WorkflowTaskScheduled/Started/Completed 等系统事件),Restate 直接记用户命令,粒度更细、协议更瘦。

留给下一层的问题:`ctx.call()` 调别的服务,目标多半在另一个 partition,怎么过去?

## 第 5 层:跨 partition —— outbox + shuffle

`crates/worker/src/partition/shuffle.rs:32`,`crates/storage-api/src/outbox_table/mod.rs:23`

`ctx.serviceClient().call()` 不直接发网络请求:

```
本 partition 事务内: CallCommand → journal + outbox_table   ← 和业务状态原子提交
后台 shuffle 任务:  读 outbox → 按目标 partition_key 包 Envelope(带 dedup 头)
                  → 投到目标 partition 的 Bifrost 日志
响应原路返回:      目标完成后 InvocationResponse 走对方 outbox 回来 → completion 回填
```

**本质是事务性消息队列(transactional outbox)内置化** —— 这是 Restate 宣称"不需要 Kafka + DB + 重试框架"的底气。

同样走日志的还有 timer(`crates/storage-api/src/timer_table/mod.rs:27`):sleep 到点 → completion;延迟调用到点 → 新 invocation(可跨 partition);保留期到点 → 清理 invocation 状态。timer 写入与 journal 同事务,后台 scanner 按时间序触发。

### exactly-once:三层去重叠出

1. **入口**:幂等键决定 InvocationId,重复请求 attach/直接拿结果
2. **跨 partition**:outbox 消息带 `(partition_id, seq_number)` dedup 头
3. **journal**:同 index 幂等写入;leader 切换用 `EpochSequenceNumber` 过滤旧 epoch 残留命令

### 版本钉死:PinnedDeployment

invocation 首次执行时钉死 deployment 版本(`crates/types/src/deployment.rs:143`,`lifecycle/pinned_deployment.rs:68`):新版本发布后,in-flight 调用继续打到旧 endpoint,replay 永远面对同一份代码。Temporal 用 patch/versioning API 在同一份代码里兼容新旧;Restate 直接要求旧 deployment 在线到 in-flight 排空。

留给最后一层的问题:前面所有保证都建立在"日志不丢、且只有一个 leader 在 apply"之上 —— 这由谁兜底?

## 第 6 层:最底层 —— 分布式如何兜底

### 6.1 节点形态:单二进制、多角色

每个节点跑同一个二进制,通过 `roles` 组合承担 5 种角色(`crates/types/src/nodes_config.rs:132`):`worker` / `log-server` / `metadata-server` / `admin` / `http-ingress`。所有节点常驻 gossip failure detector(`crates/node/src/failure_detector.rs`,100ms tick,Dead/Suspect/Alive/FailingOver 状态机)。

### 6.2 日志凭什么不丢:Bifrost(virtual consensus,不是 Raft)

`crates/bifrost/`,核心在 `providers/replicated_loglet/`

- 每个 log 是一条 **segment chain**,每段由一个 loglet 承载,provider 可插拔:memory / local(RocksDB)/ replicated
- **写路径**:单一 **sequencer** 节点定序(无选举竞争),把记录复制到 log-server nodeset,按 **flexible quorum** 确认;`ReplicationProperty` 可表达 `{zone: 2, node: 3}` 这类跨域约束(`crates/types/src/replication/`)
- **读/恢复**:用 **f-majority**(与任意合法 write quorum 相交的节点集)确定 tail
- **故障处理不靠选主,靠 seal + 重配置**:sequencer 挂了就 seal 当前 segment(f-majority 确认封印),在 chain 上接新 segment、新 sequencer、新 nodeset(`crates/bifrost/src/bifrost_admin.rs:84`)。换 leader = 换 metadata 里的一条链表项

| | Raft 组 | Bifrost replicated loglet |
|---|---|---|
| 定序 | leader(选举产生) | sequencer(metadata 指定,无选举) |
| 持久化 | 固定 replica group 的 majority | 任意 nodeset 上的 flexible quorum |
| 故障恢复 | 组内重新选举,成员组固定 | seal 旧段 + 接新段,可换全新 nodeset |

### 6.3 元数据凭什么可信:小 Raft

`crates/metadata-server/src/raft/`(基于 raft-rs fork):小型线性化 KV,只存 4 样东西 —— `NodesConfiguration`、`PartitionTable`、`Logs`(log chain 配置)、`Schema`。全部版本化,写入走 `Precondition::MatchesVersion` 的 CAS。其他节点通过 MetadataManager 拉取 + 版本观察同步,不在热路径上。

### 6.4 凭什么只有一个 leader 在写:epoch fencing

- partition processor 的 leader 当选 = 在 metadata store 上 **CAS 递增 `LeaderEpoch`**,然后向日志写 `AnnounceLeader`;follower 只重放不提议
- 每条命令带 `leader_epoch`,processor 拒绝低于 `last_observed_leader_epoch` 的消息 —— 旧 leader 的残留写入被协议性 fence,不需要 STONITH 式外部仲裁

### 6.5 状态丢了怎么办:snapshot + 续放

partition state 定期 snapshot 到 object store(S3 等,`crates/partition-store/src/snapshots.rs`);新副本下载 snapshot 后从 `applied_lsn` 续放日志追上,无需全量 replay。state 不复制 —— 它永远可以从日志推导。

### 6.6 谁来指挥:Cluster Controller

跑在 admin 角色上(`crates/admin/src/cluster_controller/`),选主极简 —— **活着的 admin 节点中 PlainNodeId 最小者为 leader**(`service/cluster_controller_state.rs:40`),因为它的决策都落到 metadata store 的 CAS 上,本身不需要强共识。负责 PP 调度、触发 log seal-and-extend、trim、节点上下线处理。

---

# Part III 横向专题

## 复制模型:三层"是否镜像"各不相同

| 层 | 是镜像吗 | 一致性来源 |
|---|---|---|
| metadata(Raft) | ✅ 字节级镜像 | 共识协议复制 |
| log-server | ❌ 各持片段 | quorum 保证"逻辑日志"不丢 |
| partition state | ⚠️ 逻辑镜像、物理独立 | 确定性重放同一日志 |

log-server 层最反直觉:每条 record 由 SpreadSelector 独立选一个满足 replication property 的子集落盘,**没有任何一台 log-server 必然拥有完整日志**:

```
nodeset = {N1..N5}, replication = {node: 2}
record 1 → N1, N2
record 2 → N3, N4
record 3 → N2, N5
```

这就是为什么确定 tail 必须问 f-majority、恢复要走 digest/repair。同路线的系统:BookKeeper ensemble striping、LogDevice copyset placement。

## 与数据库复制的本质区别

- **复制对象不同**:DB 复制状态变更流(standby 是镜像,分物理镜像/逻辑镜像/最终镜像三档);Restate 只复制日志,状态各自重放推导,是严格的 state machine replication
- **failover 内建**:写入本来就是 quorum 同步,不存在"备库没追上";换 leader = CAS epoch + seal,旧 leader 被协议性 fence
- **与 Dynamo 式 quorum 的区别**:Cassandra 的 quorum 是对单 key 值做的,无全序;Bifrost 的 quorum 是对已被 sequencer 定序的记录做持久化确认 —— quorum 只管"不丢",顺序由 sequencer 给出。这是它能承载 exactly-once 状态机的前提

## 与 Temporal 的架构对照(速记)

| 维度 | Temporal | Restate |
|---|---|---|
| 持久层 | 外置 DB(Cassandra/MySQL/PG),shard 状态存表 | 自带复制日志(Bifrost)+ RocksDB 物化 |
| 日志内容 | event history(系统事件) | 用户命令 journal |
| 任务分发 | worker 长轮询 task queue | server 通过 HTTP/2 反向推送 |
| 服务间调用 | child workflow / activity | outbox + shuffle(内置事务性消息) |
| 版本治理 | patch/versioning API,同份代码兼容新旧 | PinnedDeployment,旧版本保持在线 |
| 共识 | 依赖外置 DB 的一致性 + membership(ringpop) | 小 Raft(元数据)+ virtual consensus(数据) |

## 服务注册流程(deployment)

`restate deployments register <url>` → Admin REST API(`crates/admin/src/rest_api/deployments.rs:58`)→ 调用 SDK 的 discovery endpoint(`crates/types/src/schema/registry/discovery_client.rs`)拿到 services/handlers/schema → `SchemaUpdater` 写入 metadata(`crates/types/src/schema/registry/mod.rs:301`)。同一 service 可有多个 deployment 版本,新 invocation 用最新版,in-flight 钉在旧版(见 PinnedDeployment)。

## 关键源码索引

| 子系统 | 入口 |
|---|---|
| ingress | `crates/ingress-http/` |
| partition 状态机 | `crates/worker/src/partition/state_machine/` |
| invoker | `crates/invoker-impl/` |
| journal v2 类型 | `crates/types/src/journal_v2/` |
| 服务协议 | `crates/service-protocol-v4/` |
| 存储表定义 | `crates/storage-api/src/`(journal/state/timer/promise/outbox/inbox/fsm) |
| partition 存储 + snapshot | `crates/partition-store/` |
| Bifrost | `crates/bifrost/`(providers/replicated_loglet 为核心) |
| log server | `crates/log-server/` |
| metadata server | `crates/metadata-server/` |
| 复制/quorum 类型 | `crates/types/src/replication/` |
| 控制面 | `crates/admin/src/cluster_controller/` |
| 节点生命周期/gossip | `crates/node/` |
