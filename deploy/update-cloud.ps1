# 知微 · 云端更新脚本（Windows Server）
#
# 用法：
#   1) 把本脚本和新的 zw-agent-platform-0.1.0.jar 放到同一个目录（比如 C:\ziwei\update\）
#   2) 在该目录打开 PowerShell（管理员），执行：
#        powershell -ExecutionPolicy Bypass -File .\update-cloud.ps1
#   3) 脚本会检查环境变量 → 备份旧 jar → 替换 → 用原来的命令行重启 → 健康检查
#
# 可选参数：
#   -AppDir C:\ziwei\app      应用目录（默认）
#   -JarName xxx.jar          jar 名（默认 zw-agent-platform-0.1.0.jar）
#   -HealthUrl http://127.0.0.1:8000/api/health

param(
    [string]$AppDir   = 'C:\ziwei\app',
    [string]$JarName  = 'zw-agent-platform-0.1.0.jar',
    [string]$HealthUrl = 'http://127.0.0.1:8000/api/health'
)

$ErrorActionPreference = 'Stop'

function Fail($msg) {
    Write-Host ""
    Write-Host "[X] $msg" -ForegroundColor Red
    Read-Host "按回车退出"
    exit 1
}

Write-Host "== 1/6 检查机器级环境变量 ==" -ForegroundColor Cyan
$need = @('MYSQL_PASSWORD', 'MIMO_API_KEY', 'DASHSCOPE_API_KEY', 'QDRANT_API_KEY', 'JWT_SECRET')
$miss = @()
foreach ($k in $need) {
    $v = [Environment]::GetEnvironmentVariable($k, 'Machine')
    if ([string]::IsNullOrWhiteSpace($v)) { $miss += $k }
}
if ($miss.Count -gt 0) {
    Write-Host "缺少以下机器级环境变量（新版 yml 里默认值是空的，缺了启动会失败）：" -ForegroundColor Yellow
    foreach ($k in $miss) { Write-Host ("   - " + $k) }
    Write-Host ""
    Write-Host "先执行（值换成你的真实值，设完要重启一次 PowerShell / 服务才生效）：" -ForegroundColor Yellow
    foreach ($k in $miss) { Write-Host ('   setx /M ' + $k + ' "你的值"') }
    Fail "环境变量没补齐，先设好再换 jar"
}
Write-Host "   OK，5 个必需变量都在" -ForegroundColor Green

Write-Host ""
Write-Host "== 2/6 定位新 jar ==" -ForegroundColor Cyan
$newJar = Join-Path $PSScriptRoot $JarName
if (-not (Test-Path $newJar)) {
    Fail ("没找到新 jar：" + $newJar + "（要和本脚本放同一个目录）")
}
$newSize = [math]::Round((Get-Item $newJar).Length / 1MB, 1)
Write-Host ("   " + $newJar + "  (" + $newSize + " MB)") -ForegroundColor Green
if ($newSize -lt 100) {
    Fail "这个 jar 只有 $newSize MB，正常应该是 130 MB 左右 —— 可能是没打包完整，别覆盖线上包"
}

Write-Host ""
Write-Host "== 3/6 找正在运行的进程 ==" -ForegroundColor Cyan
$proc = Get-CimInstance Win32_Process -Filter "name='java.exe'" |
        Where-Object { $_.CommandLine -like ('*' + $JarName + '*') } |
        Select-Object -First 1
if (-not $proc) {
    Write-Host "   没找到跑这个 jar 的 java 进程，将只替换文件、你需要手动启动" -ForegroundColor Yellow
} else {
    Write-Host ("   PID " + $proc.ProcessId) -ForegroundColor Green
    Write-Host ("   命令行: " + $proc.CommandLine)
}

Write-Host ""
Write-Host "== 4/6 备份旧 jar ==" -ForegroundColor Cyan
$target = Join-Path $AppDir $JarName
$backupDir = Join-Path $AppDir 'backups'
if (-not (Test-Path $backupDir)) { New-Item -ItemType Directory -Path $backupDir | Out-Null }
if (Test-Path $target) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $bak = Join-Path $backupDir ($JarName + '.' + $stamp + '.bak')
    Copy-Item $target $bak -Force
    Write-Host ("   已备份到 " + $bak) -ForegroundColor Green
} else {
    Write-Host "   目标位置还没有 jar，跳过备份" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "== 5/6 停进程 + 替换 jar ==" -ForegroundColor Cyan
if ($proc) {
    Stop-Process -Id $proc.ProcessId -Force
    Start-Sleep -Seconds 3
    Write-Host "   已停止 PID $($proc.ProcessId)" -ForegroundColor Green
}
Copy-Item $newJar $target -Force
Write-Host ("   已替换 " + $target) -ForegroundColor Green

Write-Host ""
Write-Host "== 6/6 重启 + 健康检查 ==" -ForegroundColor Cyan
if ($proc) {
    # 用原来那条命令行启动，工作目录按应用目录
    $cmd = $proc.CommandLine
    $r = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{
        CommandLine      = $cmd
        CurrentDirectory = $AppDir
    }
    if ($r.ReturnValue -ne 0) { Fail ("启动失败，Win32_Process.Create 返回 " + $r.ReturnValue) }
    Write-Host ("   已启动，PID " + $r.ProcessId) -ForegroundColor Green
    Write-Host "   等待 20 秒让它起来…"
    Start-Sleep -Seconds 20
} else {
    Write-Host "   没有原进程，跳过自动启动 —— 请手动运行 start.bat" -ForegroundColor Yellow
}

try {
    $resp = Invoke-WebRequest -Uri $HealthUrl -UseBasicParsing -TimeoutSec 10
    Write-Host ("   健康检查 " + $HealthUrl + " → " + $resp.StatusCode) -ForegroundColor Green
} catch {
    Write-Host ("   健康检查失败：" + $_.Exception.Message) -ForegroundColor Yellow
    Write-Host "   看一眼日志：$AppDir\logs\" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "完成。回滚：把 backups\ 里最新的 .bak 复制回 $target 再重启即可。" -ForegroundColor Cyan
Read-Host "按回车退出"
