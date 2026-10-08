@echo off
REM 启动后端 jar（已编译的最新版本）
REM 关键：必须从 backend\ 目录启动，因为 AgentController 用相对路径
REM       ../agent/target/aiops-agent-1.0.0.jar 找 agent jar。
REM       如果从 aiops-platform\ 根目录启动，.. 会指向不对的位置，下载会报 "agent jar 未就绪"。
REM 用法: 双击本文件，或 cmd 中执行  start-backend.bat
setlocal
cd /d %~dp0
if not exist backend\target\aiops-backend-1.0.0.jar (
    echo [ERROR] backend jar 不存在，请先执行  mvn -DskipTests package
    pause
    exit /b 1
)
netstat -ano | findstr ":8080.*LISTENING" > nul
if %ERRORLEVEL% EQU 0 (
    echo [WARN] 8080 端口已被占用，后端可能已在运行
    pause
    exit /b 0
)
echo [%DATE% %TIME%] 启动 aiops-backend on :8080 （从 backend\ 目录）...
REM 关键：先 cd 到 backend 再起 JVM，让 ../agent 相对路径解析正确
pushd backend
start "aiops-backend" /min java -jar target\aiops-backend-1.0.0.jar > logs\aiops-platform.log 2>&1
popd
echo 已后台启动。日志在 backend\logs\aiops-platform.log
timeout /t 2 > nul
