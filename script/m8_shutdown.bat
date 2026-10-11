@echo off
REM AIOps 一键清理（关闭所有相关进程）
setlocal
powershell -NoProfile -Command "Get-Process java,node -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue" 2>nul
echo 已清理全部 java / node 进程。
endlocal
