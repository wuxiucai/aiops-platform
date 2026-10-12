# aiops-platform 一键启动脚本
# 后端 (8080) + demo-order (8081) + demo-payment (8082) + vite 前端 (5173)

$RepoRoot = $PSScriptRoot

function Start-Backend {
    Write-Host "[start-all] 启动后端 (cwd=backend/)..." -ForegroundColor Cyan
    Push-Location $RepoRoot\backend
    Start-Process -FilePath "java" -ArgumentList "-jar target/aiops-backend-1.0.0.jar" `
                  -RedirectStandardOutput "logs/aiops-platform.log" `
                  -RedirectStandardError "logs/aiops-platform-error.log" `
                  -WindowStyle Hidden
    Pop-Location
}

function Start-DemoService {
    param([string]$Name, [string]$Dir)
    Write-Host "[start-all] 启动 $Name..." -ForegroundColor Cyan
    Push-Location "$RepoRoot\demo-service\$Dir"
    $rearomatJar = Get-ChildItem target/*.jar | Select-Object -First 1
    if ($rearomatJar) {
        Start-Process -FilePath "java" -ArgumentList "-jar $($rearomatJar.FullName)" `
                      -RedirectStandardOutput "logs/$Dir.log" `
                      -RedirectStandardError "logs/$Dir-error.log" `
                      -WindowStyle Hidden
    } else {
        Write-Warning "$Name jar 不存在，请先 mvn package"
    }
    Pop-Location
}

function Start-Frontend {
    Write-Host "[start-all] 启动前端 vite (5173)..." -ForegroundColor Cyan
    Push-Location $RepoRoot\frontend
    Start-Process -FilePath "npm" -ArgumentList "run dev" -WindowStyle Hidden
    Pop-Location
}

# 检查端口占用并 kill
function Kill-Port {
    param([int]$Port)
    $netstat = netstat -ano | Select-String ":$Port.*LISTENING" | Select-Object -First 1
    if ($netstat) {
        $parts = $netstat.ToString().Split()
        $pid = $parts[-1]
        Write-Host "[start-all] 端口 $Port 已被 PID $pid 占用，尝试 kill..." -ForegroundColor Yellow
        Stop-Process -Id $pid -Force -ErrorAction SilentlyContinue
    }
}

Write-Host "=== aiops-platform 一键启动 ===" -ForegroundColor Green

Kill-Port 8080; Kill-Port 8081; Kill-Port 8082; Kill-Port 5173

Start-Backend
Start-Sleep -Seconds 2
Start-DemoService -Name "demo-order-service" -Dir "demo-order-service"
Start-Sleep -Seconds 2
Start-DemoService -Name "demo-payment-service" -Dir "demo-payment-service"
Start-Sleep -Seconds 2
Start-Frontend

Write-Host ""
Write-Host "全部启动命令已发起。等 30s 后请检查：" -ForegroundColor Green
Write-Host "  后端:      http://localhost:8080/api/auth/login" -ForegroundColor White
Write-Host "  前端:      http://localhost:5173" -ForegroundColor White
Write-Host "  order:     http://localhost:8081/actuator/health" -ForegroundColor White
Write-Host "  payment:   http://localhost:8082/actuator/health" -ForegroundColor White
Write-Host ""
Write-Host "日志位置:" -ForegroundColor Yellow
Write-Host "  backend:   backend/logs/aiops-platform.log" -ForegroundColor White
Write-Host "  order:     demo-service/demo-order-service/logs/demo-order-service.log" -ForegroundColor White
Write-Host "  payment:   demo-service/demo-payment-service/logs/demo-payment-service.log" -ForegroundColor White
