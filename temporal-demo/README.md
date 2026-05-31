# Temporal 版(Spring Boot)

Spring Boot 化后:`temporal-spring-boot-starter` 自动配置 `WorkflowClient` 与 Worker,
扫描 `@WorkflowImpl` / `@ActivityImpl` 自动注册到 task queue。**应用一启动 Worker 就常驻**,
REST 接口触发转账 —— 不再有裸 `main`。

## 最快:内存测试(无需任何外部服务)

```bash
mvn -q test
```

用 `TestWorkflowEnvironment`(内存版迷你 Server)跑完整转账:deposit 前两次失败、第三次成功;
alice 只扣一次、bob 只入账一次 —— 自动重试没造成重复记账。

## 跑真实 Server + Spring Boot 应用

REST 用法(两种 Server 方式都一样):

```bash
mvn -q -DskipTests package
java -jar target/temporal-demo-1.0.0.jar         # OrbStack 跑方式二时见「坑 1」,需 export TEMPORAL_ADDRESS
curl -X POST localhost:8088/transfer -H 'content-type: application/json' \
  -d '{"from":"alice","to":"bob","amount":100}'   # → {"workflowId":"...","status":"STARTED"}
curl localhost:8088/transfer/<workflowId>          # 取结果
```

### 方式一:单容器开发 Server(内存 SQLite)

```bash
docker run -d --name temporal-study -p 7233:7233 -p 8233:8233 \
  temporalio/temporal:1.7.0 server start-dev --ip 0.0.0.0 \
  --dynamic-config-value frontend.workerHeartbeatsEnabled=true   # 开 worker heartbeat
# gRPC :7233 / Web UI :8233(内嵌 Server 1.31,支持顶层 Workers 页)
```

### 方式二:Postgres 持久化(多容器,生产形态)

`auto-setup` 镜像停在 1.29.6(无 ListWorkers API,顶层 Workers 页用不了),故改用
**`server:1.31` + `admin-tools:1.31`** 自建 schema 的拓扑(见 `docker-compose.postgres.yml`):

```bash
docker compose -f docker-compose.postgres.yml up -d
#   postgres → schema-setup(建表)→ server:1.31 → ns-setup(自动注册 default namespace) + ui
#   全自动、开箱即用;dynamicconfig/docker.yaml 已开 worker heartbeat

# 验证数据落库 / 重启不丢:
docker exec temporal-demo-postgresql-1 psql -U temporal -d temporal -t -c "select count(*) from history_node;"
docker restart temporal-demo-temporal-1                   # 重启 server,workflow 历史仍在
docker compose -f docker-compose.postgres.yml down -v     # 停并清空
```

## 一键崩溃恢复实验

```bash
./crash-recovery.sh
```
转账「已扣款」后进入 10s 持久化定时器,期间 `kill` 掉 Spring Boot 应用,再重启,
验证 withdraw 不被重复执行、流程从断点续跑。

## 在 UI 看 Worker(顶层 Workers 页)

需要 **Server ≥ 1.31** + `frontend.workerHeartbeatsEnabled: true`(本项目两种方式都已配)。
worker 会上报 heartbeat,顶层 Workers 页显示其 Identity / Task Queue / Worker SDK(如 `Java 1.35.0`)。
另外 worker 存活始终能在 **Task Queue → Pollers** 看到(与版本无关)。

## 部署坑(实测,主要影响 OrbStack)

**坑 1 — `localhost:7233` gRPC 超时(宿主连完整版 server)。**
完整版 server 的 ringpop membership 广播**容器内网 IP**,经 OrbStack 的 localhost 端口转发后
gRPC 异常。OrbStack 下宿主可直连容器 IP,绕过:
```bash
export TEMPORAL_ADDRESS=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' temporal-demo-temporal-1):7233
```
(`TransferController`/Worker 已支持该环境变量,默认 `127.0.0.1:7233`;单容器 start-dev 因广播 127.0.0.1 无此问题。)

**坑 2 — 容器间也连不上 server,导致注册 namespace 卡住。**
同源问题(membership 广播 IP 跨容器路由不通)。解法:`ns-setup` 容器用
`network_mode: "service:temporal"` **共享 server 网络栈**,连 `127.0.0.1:7233` 注册(已配进 compose)。

**坑 3 — `auto-setup` 需 `BIND_ON_IP=0.0.0.0`**(本拓扑用 `server` 镜像,同样设了),否则 frontend 不监听所有网卡。

## 源码核心概念

| 文件 | 概念 |
|---|---|
| `Application` | `@SpringBootApplication`;starter 自动配 WorkflowClient + Worker |
| `TransferController` | REST 触发(异步 start + 按 id 取结果),注入 starter 装配的 `WorkflowClient` |
| `MoneyTransferWorkflowImpl` | `@WorkflowImpl(taskQueues=…)`、确定性约束、`RetryOptions`、`Saga` 补偿 |
| `AccountActivitiesImpl` | `@Component`+`@ActivityImpl(taskQueues=…)`,Activity=副作用、自动重试 |
| `application.yml` | `spring.temporal.connection.target`、`workers-auto-discovery.packages` |
| `docker-compose.postgres.yml` | server 1.31 + admin-tools 建 schema + ns-setup 注册 + ui |
| `dynamicconfig/docker.yaml` | 开启 `frontend.workerHeartbeatsEnabled` |
