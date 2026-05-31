# Durable Execution(持久化执行)原理

> 一篇框架无关的核心原理笔记。配套实证来自本仓库的 `temporal-demo/` 与 `restate-demo/`
> 两个可运行项目(同一个「资金转账」场景的两种实现)。

## 0. 一句话定义

**Durable Execution = 让你用普通函数写出「进程崩溃后能从断点继续、且每一步只执行一次」的长流程。**

你照常写顺序代码:

```java
withdraw(from, amount);   // 扣款
deposit(to, amount);      // 入账
notify(to);               // 通知
```

框架负责保证:无论这段代码跑到哪一行时机器崩了、被 kill 了、重启了、换了台机器,
重启后都能**恰好从中断处继续**,已经做过的步骤**不会重做**。

---

## 1. 它解决什么问题

一个跨多步骤、可能运行几秒到几个月的业务流程(下单→扣款→发货→通知),最难的从来不是
业务逻辑本身,而是**故障恢复**:

- 进程崩在「扣款成功、入账之前」,重启后怎么知道钱已经扣了?
- 重试一个失败的步骤,怎么保证不会重复扣款?
- 某一步永久失败,前面已经造成的影响怎么撤销?

传统做法要手写一大坨基础设施:**状态表 + 消息队列 + 定时重试 + 幂等键 + 对账补偿**,
业务逻辑被淹没在这些「管道代码」里。Durable Execution 把这些全部下沉到框架,
让你只写最上面那三行业务代码。

---

## 2. 核心机制:重跑 + 日志短路

这是整个范式**唯一**真正需要理解的机制,其余一切都是它的推论。

框架在你每执行一个有副作用的步骤时,把**这一步的结果**追加写进一份持久化日志
(Temporal 叫 event history,Restate 叫 journal)。崩溃后恢复时:

> **它不是「从上次的内存接着跑」,而是「从头重新执行你的代码」,
> 遇到日志里已经记录过的步骤就直接返回当时的结果(短路),不真正重做;
> 一路短路到上次中断的位置,再开始真正往下执行。**

用转账举例,假设进程崩在「已扣款、入账前」:

```
首次执行:  withdraw ──(写日志: withdraw=done)──▶ 💥崩溃
恢复重跑:  withdraw ──(日志已有 → 直接返回,不真扣)──▶ deposit ──▶ notify ──▶ 完成
```

最终账本里 `withdraw` 只出现一次。**这就是「精确一次」的来源,也是 durable execution 的全部魔法。**

### 实证(本仓库的崩溃恢复实验)

`temporal-demo/crash-recovery.sh` 和 `restate-demo/crash-recovery.sh` 都做了同一件事:
让转账在「已扣款」后进入一段持久化暂停,期间 `kill -9` 掉执行进程,再重启。结果完全对称:

| 阶段 | 磁盘账本内容 |
|---|---|
| 扣款后、崩溃前 | `withdraw alice 100` |
| 💥 杀掉进程后 | `withdraw alice 100`(不变) |
| 重启 → 跑完 | `withdraw alice 100` / `deposit bob 100` |
| **结论** | **withdraw=1, deposit=1 —— 崩溃没导致重复扣款** |

> ⚠️ 破除误解:**Temporal 和 Restate 恢复时都会重跑你的代码**。
> 「Temporal=重放派、Restate=日志派」只是记忆口诀,本质两者都是「重跑 + 日志短路」。

---

## 3. 由机制推导出的三条铁律

「恢复=重跑代码」这一条机制,直接推导出下面三条你必须遵守的规矩。

### 铁律一:副作用必须被隔离、只执行一次

**副作用(side effect)** = 代码除了算出返回值,还改变外部世界或依赖外部世界:
改数据库、写文件、调 HTTP、读时钟、取随机数。

既然代码会重跑,副作用如果裸奔就会被执行多次(扣两次款)。所以框架要求把每个副作用
**包进一个「可记录的单元」**,执行一次后记下结果,重跑时返回记录值:

```java
// Temporal:副作用放进 Activity(独立的、带重试的远程调用单元)
accounts.withdraw(from, amount);          // 调用 Activity 存根

// Restate:副作用用 ctx.run(...) 包住
ctx.run("withdraw", () -> Accounts.withdraw(from, amount));
```

### 铁律二:确定性约束

重跑要能和日志对齐,代码每次走的路径就必须一致。所以**「可记录单元」之外的普通代码**
不能依赖不确定的东西:

```java
if (new Random().nextInt(100) < 50) {  // ❌ 重跑可能走另一条分支 → 与日志对不上 → 状态错乱
    deposit(...);
}
long now = System.currentTimeMillis();  // ❌ 每次重跑值都不同
```

要随机/时钟,得用框架提供的确定性版本(Temporal: `Workflow.newRandom()` /
`Workflow.currentTimeMillis()`;Restate: 把它放进 `ctx.run`,让结果被记录下来固定住)。

> **两个框架都有这条约束**,因为两者都重跑。区别只是约束的「表面积」:
> Temporal 整段 workflow 受约束,讲得严格而显式;Restate 把不确定性都收进 `ctx.run` 边界,
> 日常代码需小心的地方更少,容易给人「没有约束」的错觉。

### 铁律三:幂等是底线

框架保证「每步只执行一次」是针对**正常崩溃恢复**;但重试、超时重投等场景下,
一个副作用仍可能被投递多次。生产中真正写外部系统时,副作用本身最好也幂等
(用业务幂等键去重)。Temporal 的 `workflowId`、Restate 的 idempotency key 就是为此。

---

## 4. Saga:多步骤的逻辑回滚

单库事务能 `ROLLBACK`,但「扣 A 的钱」「加 B 的钱」是两次独立操作,没有统一回滚。
**Saga 模式**:每做成功一步就登记一个「反向操作」,后续失败时按相反顺序执行这些反向操作,
做**逻辑回滚**(补偿)。

```java
withdraw(from, amount);
addCompensation(() -> refund(from, amount));  // 登记反向操作
deposit(to, amount);                          // 若彻底失败 → 执行 refund,账目不凭空少钱
```

- Temporal 内置 `io.temporal.workflow.Saga` 类。
- Restate 没内置,但用普通 `try/catch` + 一个补偿栈就能实现(见 `MoneyTransferService` 里的
  `Deque<Runnable> compensations`)—— 这正体现了「普通代码 + durable steps」模型的好处:
  控制流就是你熟悉的 Java。

---

## 5. 持久化暂停与 Suspension

长流程经常要「等」:等 10 秒、等人工审批、等外部回调。普通 `Thread.sleep` 不行
(进程崩了计时就丢了)。Durable Execution 提供**持久化定时器**:

```java
Workflow.sleep(Duration.ofSeconds(10));   // Temporal
ctx.sleep(Duration.ofSeconds(10));        // Restate
```

计时由 **Server** 维护,不占用执行进程的内存。本仓库实验里,我们正是在这段 sleep 期间杀掉进程
——计时照走,到点 Server 把流程重新投给任意一个活着的实例继续。

> Restate 在长暂停时会**主动 suspend**:执行进程可以被完全释放(我们 `kill -9` 了它都没事),
> 这让它天然适配 serverless —— 没活时不占资源,有事件再被拉起。

---

## 6. 两种运行形态:拉(pull)vs 推(push)

「谁主动发起连接」决定了部署形态,这是 Temporal 与 Restate 最直观的架构差异。

```
Temporal(拉):  Worker ──「有我的任务吗?」long-poll──▶ Server
                Worker 长驻、主动轮询;Server 从不主动连 Worker。
                → Worker 可藏在内网/防火墙后,无需公网入口。

Restate(推):   Server ──「执行这个 handler」HTTP POST──▶ 你的服务
                你的服务是普通 HTTP server,被 Server 主动调用。
                → 天然适配 serverless/FaaS,但服务需可被 Server 寻址。
```

---

## 7. Temporal vs Restate 落点对比

| 维度 | Temporal | Restate |
|---|---|---|
| 恢复机制 | 重放整段 Workflow,用 event history 短路 | 重跑 handler,用 journal 短路(本质相同) |
| 副作用单元 | **Activity**(独立重试、独立超时) | **`ctx.run(...)`** |
| 确定性约束 | 整段 workflow,严格显式 | 收进 `ctx.run` 边界,心智负担小 |
| 角色划分 | 强制 Workflow / Activity 二分 | 一个 handler 走到底,无强制二分 |
| Saga 补偿 | 内置 `Saga` 类 | 普通 try/catch 手写 |
| 持久化暂停 | `Workflow.sleep`,Server 维护定时器 | `ctx.sleep` + **suspension**(进程可释放) |
| 运行形态 | **拉**:Worker long-poll Server | **推**:Server 反向 POST 你的 HTTP 服务 |
| 部署依赖 | Server 集群(生产依赖 Cassandra/PG) | 单个自包含二进制(Rust) |
| 有状态原语 | 主要靠 workflow 内存状态 | **Virtual Object**(按 key 串行 + 持久化状态) |

---

## 8. 心智模型总结

把整套范式压成一条因果链:

```
「恢复 = 重跑代码」
   ├─▶ 所以副作用要隔离、只执行一次   (Activity / ctx.run)
   ├─▶ 所以代码要确定性               (禁裸 Random/时钟/IO)
   ├─▶ 所以多步失败用 Saga 逻辑回滚    (补偿而非 ROLLBACK)
   └─▶ 「谁连谁(拉/推)」决定部署形态  (Worker 轮询 / HTTP 被调)
```

记住这条链,Temporal 和 Restate 的所有 API 设计都能从第一性原理推出来。

## 参考运行

- Temporal:`cd temporal-demo && mvn test`(内存跑通)/ `./crash-recovery.sh`(崩溃恢复)
- Restate:`cd restate-demo && ./run.sh`(端到端)/ `./crash-recovery.sh`(崩溃恢复)
