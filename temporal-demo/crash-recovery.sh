#!/usr/bin/env bash
# 崩溃恢复实验(Temporal,Spring Boot 版):应用启动即 Worker 常驻;转账在「已扣款」后
# 进入 10s 持久化定时器,期间 kill 掉 Spring Boot 应用进程,再重启,验证 withdraw 不被重复执行。
# Server 用 docker 多容器(postgres + auto-setup + ui)。
set -uo pipefail
cd "$(dirname "$0")"
LEDGER=/tmp/durable-ledger-temporal.log
APP_JAR=target/temporal-demo-1.0.0.jar

cleanup() {
  echo "--- 清理应用进程(保留 Server 栈,供看 UI)---"
  pkill -9 -f "temporal-demo-1.0.0.jar" 2>/dev/null || true
}
trap cleanup EXIT

rm -f "$LEDGER"
mvn -q -DskipTests package

echo "=== 1) 启动 Temporal Server 栈(postgres + server + ui)==="
docker compose -f docker-compose.postgres.yml up -d >/dev/null 2>&1
CIP=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' temporal-demo-temporal-1)
export TEMPORAL_ADDRESS=${CIP}:7233    # OrbStack 下连容器 IP(见 README「坑 1」)
echo "    TEMPORAL_ADDRESS=$TEMPORAL_ADDRESS"
until temporal operator namespace list --address ${CIP}:7233 >/dev/null 2>&1; do sleep 2; done

echo "=== 2) 启动 Spring Boot 应用(Worker 随应用自动注册)==="
java -jar "$APP_JAR" >/tmp/temporal-app1.log 2>&1 &
until nc -z localhost 8088 2>/dev/null; do sleep 1; done
sleep 4

echo "=== 3) REST 发起转账(异步,返回 workflowId)==="
RESP=$(curl -s -X POST localhost:8088/transfer -H 'content-type: application/json' -d '{"from":"alice","to":"bob","amount":100}')
WFID=$(echo "$RESP" | grep -oE '"workflowId":"[^"]*"' | sed 's/.*:"//;s/"//')
echo "    workflowId=$WFID"

sleep 5
echo ">>> 此刻账本(应只有 withdraw):"; cat "$LEDGER" 2>/dev/null | sed 's/^/    /'

echo "=== 4) 💥 杀掉 Spring Boot 应用(模拟崩溃)==="
pkill -9 -f "temporal-demo-1.0.0.jar" 2>/dev/null || true
sleep 2
echo ">>> 应用已死,账本不变:"; cat "$LEDGER" 2>/dev/null | sed 's/^/    /'

echo "=== 5) 重启应用 ==="
java -jar "$APP_JAR" >/tmp/temporal-app2.log 2>&1 &
until nc -z localhost 8088 2>/dev/null; do sleep 1; done
sleep 3

echo "=== 6) 取结果(GET /transfer/{id})==="
curl -s localhost:8088/transfer/$WFID | sed 's/^/    /'
echo; echo "=== 最终账本与结论 ==="
cat "$LEDGER" 2>/dev/null | sed 's/^/    /'
echo "    withdraw 次数 = $(grep -c withdraw "$LEDGER" 2>/dev/null)  (期望 1)"
echo "    deposit  次数 = $(grep -c deposit  "$LEDGER" 2>/dev/null)  (期望 1)"
echo "    Web UI: http://localhost:8233"
