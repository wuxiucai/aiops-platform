@echo off
REM M7 一键启动脚本（Windows）
REM 依次：backend / demo-order / demo-payment / 验证 health

setlocal enableextensions enabledelayedexpansion

set ROOT=D:\Users\wuzif\Desktop\2027届本科毕设\aiops-platform
set BACKEND=%ROOT%\backend\target\aiops-backend-1.0.0.jar
set ORDER=%ROOT%\demo-service\demo-order-service\target\demo-order-service.jar
set PAYMENT=%ROOT%\demo-service\demo-payment-service\target\demo-payment-service.jar

echo [M7] 准备启动 3 个服务 (backend=8080 / order=8081 / payment=8082)

if not exist "%BACKEND%" (
    echo [M7] ERROR: backend jar 不存在， 请先 mvn -DskipTests package
    exit /b 1
)

start /min "aiops-backend" java -jar "%BACKEND%" > "%ROOT%\backend\logs\m7-backend.log" 2>&1
timeout /t 3 /nobreak >nul
start /min "demo-order" java -jar "%ORDER%" > "%ROOT%\demo-service\demo-order-service\logs\m7-order.log" 2>&1
timeout /t 5 /nobreak >nul
start /min "demo-payment" java -jar "%PAYMENT%" > "%ROOT%\demo-service\demo-payment-service\logs\m7-payment.log" 2>&1

echo [M7] 等待 30s 让 Spring Boot 启动...
timeout /t 30 /nobreak >nul

echo [M7] 验证 3 个 /actuator/health
curl -s -o nul -w "  backend  http=%%{http_code}\n" http://localhost:8080/actuator/health
curl -s -o nul -w "  order    http=%%{http_code}\n" http://localhost:8081/actuator/health
curl -s -o nul -w "  payment  http=%%{http_code}\n" http://localhost:8082/actuator/health

echo [M7] 启动完成 （前端访问 http://localhost:5173)
endlocal
