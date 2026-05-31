# Durable Execution 学习:Temporal vs Restate(Java)

用同一个业务场景 —— **资金转账**(账户 A → 账户 B:扣款 → 入账 → 通知)—— 分别用 Temporal 和 Restate 实现,在代码里对照学习「持久化执行」的概念与设计原理。

## 为什么转账是经典例子

一个转账流程包含多个**有副作用、可能失败、需要重试**的步骤:

```
withdraw(from, amount)   // 扣款:钱出去了,崩溃后绝不能重复扣
deposit(to, amount)      // 入账:可能临时失败(对方银行抖动),要重试
notify(user)             // 通知:失败了也别让整个转账回滚
```

传统写法要手写:状态表 + 消息队列 + 定时重试 + 幂等键 + 对账补偿。
Durable Execution 的承诺:**你只写上面这三行普通代码,框架保证「执行到一半进程崩溃,重启后从断点继续,且每步只执行一次」。**

## 两个框架的核心设计差异

| 维度 | Temporal | Restate |
|---|---|---|
| 持久化原理 | **Event Sourcing + 重放**:把每步结果记成事件历史,崩溃后**重新执行你的代码**,用历史「快进」重建内存状态到断点 | **Journaling**:每个 `ctx.*` 写一条 durable log;恢复时**同样会重放 handler 代码**,但遇到已记录的步骤直接返回旧结果来短路 |

> ⚠️ 常见误解:两者恢复时**都会重新执行你的代码**,都靠持久化日志短路已完成的步骤。差别不在「重放 vs 不重放」,而在下面几行(约束严格度、运行形态、角色划分)。
| 对你代码的约束 | Workflow 代码必须**确定性**:禁止 `new Random()`、`System.currentTimeMillis()`、直接 IO —— 否则重放会偏离历史 | 约束更宽松,更像写普通 RPC handler;不确定操作用 `ctx.run(...)` 包一下即可 |
| 副作用怎么写 | 放进 **Activity**(独立重试、有超时,不参与重放) | 放进 `ctx.run("step", () -> ...)`,结果被 journal 记录 |
| 运行时形态 | 独立 **Temporal Server** 集群(生产依赖 Cassandra/PG;开发用 `temporal server start-dev` 单进程) | 单个自包含二进制(Rust),你的服务是普通 HTTP 端点,Server 反向调用它 |
| 调用模型 | Client 启动 Workflow → Worker 拉取任务执行 | Restate Server 作为「持久化代理」转发请求到你的 HTTP handler |

> 一句话记忆:**Temporal = 重放派(replay)**,**Restate = 日志派(journal)**。理解 Temporal 的「确定性 + 重放」是最大的入门门槛,搞懂它再看 Restate 会非常轻松。

## 目录

- `docs/durable-execution.md` —— **核心原理笔记**(框架无关),建议先读
- `temporal-demo/` —— Temporal 版,含内存测试(无需任何外部服务即可跑通)
- `restate-demo/`  —— Restate 版,用 Docker 拉起 Restate Server

## 运行环境(均为 docker 单容器部署,自带 Web UI)

| | 镜像(最新稳定版) | 端口 | Web UI |
|---|---|---|---|
| Temporal | `temporalio/temporal:1.7.0`(内嵌 Server 1.31.0) | gRPC 7233 | http://localhost:8233 |
| Restate  | `restatedev/restate:1.6.2` | ingress 8080 / admin 9070 | http://localhost:9070/ui/ |

两者均为开发模式、单机、数据非持久(容器停即清空)。各模块的 `./crash-recovery.sh`
会自动拉起对应容器,跑完**保留容器**供你打开 Web UI 查看执行历史 / journal。

> 注:Temporal 也可用官方 `temporal` CLI 的 `server start-dev` 本地运行;此处统一成 docker
> 以便和 Restate 部署方式对齐。`mvn test` 跑的 Temporal 内存测试不需要任何容器。

详见各模块内的 README 与源码注释。
