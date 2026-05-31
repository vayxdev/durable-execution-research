#!/usr/bin/env bash
# 一键跑通 Restate 版转账(Spring Boot)。Ctrl-C / Enter 退出时清理。
set -euo pipefail
cd "$(dirname "$0")"
APP_JAR=target/restate-demo-1.0.0.jar

cleanup() {
  echo "--- 清理 ---"
  pkill -9 -f "restate-demo-1.0.0.jar" 2>/dev/null || true
  docker rm -f restate-study >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "--- 1) 打包并启动 Spring Boot 应用(endpoint :9080)---"
mvn -q -DskipTests package
java -jar "$APP_JAR" >/tmp/restate-app.log 2>&1 &
until nc -z localhost 9080 2>/dev/null; do sleep 1; done
sleep 2

echo "--- 2) 启动 Restate Server ---"
docker rm -f restate-study >/dev/null 2>&1 || true
docker run -d --name restate-study -p 8080:8080 -p 9070:9070 restatedev/restate:1.6.2 >/dev/null
until curl -s localhost:9070/health >/dev/null 2>&1; do sleep 1; done

echo "--- 3) 注册服务(use_http_11:starter 的 endpoint 是 HTTP/1.1 模式,见 README)---"
curl -s localhost:9070/deployments -H 'content-type: application/json' \
  -d '{"uri":"http://host.docker.internal:9080","use_http_11":true}' >/dev/null
echo "已注册"

echo "--- 4) 发起转账 ---"
curl -s localhost:8080/MoneyTransferService/transfer -H 'content-type: application/json' \
  -d '{"from":"alice","to":"bob","amount":100}'
echo

echo "服务与 Server 仍在运行。Web UI http://localhost:9070/ui/ 。按 Enter 退出并清理……"
read -r
