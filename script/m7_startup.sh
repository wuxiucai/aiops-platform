#!/usr/bin/env bash
# M7 一键启动脚本（Linux / macOS / Git Bash on Windows）
# 依次：backend / demo-order / demo-payment / 验证 health

set -eu

ROOT="${ROOT:-D:/Users/wuzif/Desktop/2027届本科毕设/aiops-platform}"
BACKEND_JAR="$ROOT/backend/target/aiops-backend-1.0.0.jar"
ORDER_JAR="$ROOT/demo-service/demo-order-service/target/demo-order-service.jar"
PAYMENT_JAR="$ROOT/demo-service/demo-payment-service/target/demo-payment-service.jar"

if [ ! -f "$BACKEND_JAR" ]; then
    echo "[M7] ERROR: backend jar 不存在, 请先 mvn -DskipTests package" >&2
    exit 1
fi

echo "[M7] 准备启动 3 个服务 (backend=8080 / order=8081 / payment=8082)"

mkdir -p "$ROOT/backend/logs" "$ROOT/demo-service/demo-order-service/logs" \
         "$ROOT/demo-service/demo-payment-service/logs"

nohup java -jar "$BACKEND_JAR" > "$ROOT/backend/logs/m7-backend.log" 2>&1 &
BACKEND_PID=$!
sleep 3
nohup java -jar "$ORDER_JAR" > "$ROOT/demo-service/demo-order-service/logs/m7-order.log" 2>&1 &
ORDER_PID=$!
sleep 5
nohup java -jar "$PAYMENT_JAR" > "$ROOT/demo-service/demo-payment-service/logs/m7-payment.log" 2>&1 &
PAYMENT_PID=$!

echo "[M7] backend pid=$BACKEND_PID order=$ORDER_PID payment=$PAYMENT_PID"
echo "[M7] 等 30s 让 Spring Boot 启动..."
sleep 30

# health check
echo "[M7] 验证 3 个 /actuator/health"
for port in 8080 8081 8082; do
    case $port in
        8080) name=backend ;;
        8081) name=order ;;
        8082) name=payment ;;
    esac
    code=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:$port/actuator/health" || echo "000")
    echo "  $name http=$code"
done

echo "[M7] 启动完成 （前端访问 http://localhost:5173)"
