# Restate 版(Spring Boot)

Restate 的运行形态:**你的服务是个普通 HTTP 服务**(Spring Boot 应用),把自己注册到 Restate Server;
客户端调用 Server,Server 作为「持久化代理」转发给你的 handler,并把每一步记进 durable journal。

Spring Boot 化后:`@Service`(Restate handler 定义)+ `@RestateComponent`(注册为 bean,starter 自动收集)。
应用一启动,`sdk-spring-boot-starter` 就把内置 endpoint 起在 **:9080** —— 不再有裸 `main`。

## 一键运行

```bash
./run.sh
```

启动 Spring Boot 应用(:9080)→ Docker 起 Restate Server(ingress :8080 / admin+UI :9070)→ 注册 → 发起转账。

## 手动分步

```bash
# 1) 打包并启动 Spring Boot 应用(endpoint :9080)
mvn -q -DskipTests package
java -jar target/restate-demo-1.0.0.jar

# 2) 启动 Restate Server
docker run -d --name restate-study -p 8080:8080 -p 9070:9070 restatedev/restate:1.6.2

# 3) 注册服务 —— 注意 use_http_11(原因见下)
curl localhost:9070/deployments -H 'content-type: application/json' \
  -d '{"uri":"http://host.docker.internal:9080","use_http_11":true}'

# 4) 经 Restate ingress 发起转账(调 Server 的 8080,不是直接调你的服务)
curl localhost:8080/MoneyTransferService/transfer -H 'content-type: application/json' \
  -d '{"from":"alice","to":"bob","amount":100}'
```

## 为什么注册要加 `use_http_11`(踩坑记录)

`sdk-spring-boot-starter` 起的内置 endpoint **默认是 `REQUEST_RESPONSE` 模式(纯 HTTP/1.1)**
(可在 `/discover` 的 manifest 里看到 `"protocolMode":"REQUEST_RESPONSE"`)。
而 Restate Server 注册 deployment 时**默认尝试用 h2c(明文 HTTP/2)** 去连 endpoint 做 discovery,
两边协议不匹配 → 注册报 `META0003 ... broken pipe`(server 发 h2 preface,HTTP/1.1 的 endpoint 读不懂)。

加 `"use_http_11":true` 让 Server 改用 HTTP/1.1 连 endpoint,与之对上即可。

> 这**不是** OrbStack/Docker 的问题,而是两边默认协议模式不一致 —— 实测:HTTP/1.1 在宿主直连和
> 经 `host.docker.internal` 转发都正常。独立 `RestateHttpServer`(非 Spring Boot)默认是 BIDI_STREAM/h2c,
> 恰好匹配 Server 默认,所以那时不用此参数。

## 观察 journal

```bash
curl localhost:9070/query -H 'content-type: application/json' \
  -d '{"query":"SELECT index, entry_type, name FROM sys_journal ORDER BY index"}'
```
或 Web UI:http://localhost:9070/ui/ → Invocations。

## 崩溃恢复实验

```bash
./crash-recovery.sh
```
转账「已扣款」后进入 10s 持久化暂停(`ctx.sleep`),期间 kill 掉 Spring Boot 应用进程,
Server 到点重新投递,验证 withdraw 不被重复执行、流程从断点续跑。

## 源码核心概念

| 文件 | 概念 |
|---|---|
| `Application` | `@SpringBootApplication` + `@EnableRestate`(总开关,@Import 三套自动配置) |
| `MoneyTransferService` | `@Service` + `@RestateComponent`、`Context`、`ctx.run(...)` 包副作用、try/catch 手写 Saga |
| `Accounts` | 副作用必须经 `ctx.run` 才能崩溃可恢复 |
| `application.yml` | `restate.sdk.http.port=9080`、`restate.client.base-uri` |

## 清理

```bash
docker rm -f restate-study
```
