#!/usr/bin/env bash
# 崩溃恢复实验(Restate,Spring Boot 版):转账在「已扣款」后进入 10s 持久化暂停(ctx.sleep),
# 期间 kill 掉 Spring Boot 应用进程,Restate Server 到点重新投递,验证 withdraw 不被重复执行。
set -uo pipefail
cd "$(dirname "$0")"
LEDGER=/tmp/durable-ledger-restate.log
APP_JAR=target/restate-demo-1.0.0.jar

cleanup() {
  echo "--- 清理应用进程(保留 Server 容器,供看 UI)---"
  pkill -9 -f "restate-demo-1.0.0.jar" 2>/dev/null || true
}
trap cleanup EXIT

rm -f "$LEDGER"
mvn -q -DskipTests package

echo "=== 1) 启动 Spring Boot 应用(endpoint :9080)==="
java -jar "$APP_JAR" >/tmp/restate-app1.log 2>&1 &
until nc -z localhost 9080 2>/dev/null; do sleep 1; done
sleep 2

echo "=== 2) 启动 Restate Server 并注册(use_http_11)==="
docker rm -f restate-study >/dev/null 2>&1 || true
docker run -d --name restate-study -p 8080:8080 -p 9070:9070 restatedev/restate:1.6.2 >/dev/null
until curl -s localhost:9070/health >/dev/null 2>&1; do sleep 1; done
curl -s localhost:9070/deployments -H 'content-type: application/json' \
  -d '{"uri":"http://host.docker.internal:9080","use_http_11":true}' >/dev/null
echo "已注册"

echo "=== 3) 发起转账(后台;扣款后进入 10s 暂停)==="
curl -s localhost:8080/MoneyTransferService/transfer -H 'content-type: application/json' \
  -d '{"from":"alice","to":"bob","amount":100}' >/tmp/restate-call.log 2>&1 &
CALL_PID=$!

sleep 6
echo ">>> 此刻账本(应只有 withdraw 一行):"; cat "$LEDGER" 2>/dev/null | sed 's/^/    /'

echo "=== 4) 💥 杀掉 Spring Boot 应用(模拟崩溃)==="
pkill -9 -f "restate-demo-1.0.0.jar" 2>/dev/null || true
sleep 2
echo ">>> 应用已死,账本不变(仍只有 withdraw):"; cat "$LEDGER" 2>/dev/null | sed 's/^/    /'

echo "=== 5) 重启应用(endpoint 回到 9080,Server 重新投递)==="
java -jar "$APP_JAR" >/tmp/restate-app2.log 2>&1 &
until nc -z localhost 9080 2>/dev/null; do sleep 1; done

echo "=== 6) 等待转账完成 ==="
wait "$CALL_PID" 2>/dev/null || true
echo "    结果: $(cat /tmp/restate-call.log)"

echo "=== 7) 最终账本与结论 ==="
cat "$LEDGER" 2>/dev/null | sed 's/^/    /'
echo "    withdraw 次数 = $(grep -c withdraw "$LEDGER" 2>/dev/null)  (期望 1 → 崩溃没导致重复扣款)"
echo "    deposit  次数 = $(grep -c deposit  "$LEDGER" 2>/dev/null)  (期望 1)"
echo "    Web UI: http://localhost:9070/ui/"
