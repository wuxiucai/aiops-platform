@echo off
REM AIOps 一键启动（PowerShell Start-Process 让进程脱离本 shell）
setlocal EnableDelayedExpansion
cd /d %~dp0..

echo ============================================
echo   AIOps 一键启动 (PowerShell daemon 方式)
echo ============================================

powershell -NoProfile -Command "
  Write-Host '[1/5] 清理残留 java / node 进程...' -ForegroundColor Yellow
  Get-Process java,node -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
  Start-Sleep -Seconds 2

  Write-Host '[2/5] 启动 backend (8080)...' -ForegroundColor Yellow
  Start-Process -FilePath 'java.exe' -ArgumentList '-jar', 'target\aiops-backend-1.0.0.jar' -WorkingDirectory 'backend' -WindowStyle Hidden
  Start-Sleep -Seconds 18

  Write-Host '[3/5] 启动 demo-order (8081)...' -ForegroundColor Yellow
  Start-Process -FilePath 'java.exe' -ArgumentList '-jar', 'target\demo-order-service.jar' -WorkingDirectory 'demo-service\demo-order-service' -WindowStyle Hidden
  Start-Sleep -Seconds 6

  Write-Host '[4/5] 启动 demo-payment (8082)...' -ForegroundColor Yellow
  Start-Process -FilePath 'java.exe' -ArgumentList '-jar', 'target\demo-payment-service.jar' -WorkingDirectory 'demo-service\demo-payment-service' -WindowStyle Hidden
  Start-Sleep -Seconds 6

  Write-Host '[5/5] 启动 frontend (5173)...' -ForegroundColor Yellow
  Start-Process -FilePath 'cmd.exe' -ArgumentList '/c', 'cd /d frontend && npm run dev' -WindowStyle Hidden
  Start-Sleep -Seconds 12

  Write-Host ''
  Write-Host '===== 健康检查 =====' -ForegroundColor Cyan

  foreach (\$check in @(
    @('backend','http://localhost:8080/api/auth/captcha'),
    @('order'  ,'http://localhost:8081/actuator/health'),
    @('payment','http://localhost:8082/actuator/health'),
    @('frontend','http://localhost:5173')
  )) {
    \$name = \$check[0]
    \$url = \$check[1]
    try {
      \$resp = Invoke-WebRequest -Uri \$url -TimeoutSec 3 -UseBasicParsing -ErrorAction Stop
      Write-Host ('  ' + \$name.PadRight(10) + ' OK (' + \$resp.StatusCode + ')') -ForegroundColor Green
    } catch {
      Write-Host ('  ' + \$name.PadRight(10) + ' FAIL - ' + \$_.Exception.Message) -ForegroundColor Red
    }
  }

  Write-Host ''
  Write-Host '服务地址：' -ForegroundColor Cyan
  Write-Host '  backend  = http://localhost:8080'
  Write-Host '  order    = http://localhost:8081'
  Write-Host '  payment  = http://localhost:8082'
  Write-Host '  frontend = http://localhost:5173'
  Write-Host ''
  Write-Host '登录: admin / 123456'
  Write-Host '============================================' -ForegroundColor Cyan
"

endlocal
