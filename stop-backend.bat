@echo off
REM 停止后端进程（按 8080 端口查找）
setlocal
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":8080.*LISTENING"') do (
    echo [%DATE% %TIME%] Killing PID %%a
    taskkill /PID %%a /F
)
echo 后端已停止
timeout /t 2 > nul
