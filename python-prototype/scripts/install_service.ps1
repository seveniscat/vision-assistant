# Register and start the vision-assistant uvicorn service via NSSM.
# Must be run as Administrator (UAC). Same ops pattern as ocr-agent.

$ErrorActionPreference = 'Stop'

param(
    [string]$Service = 'vision-assistant',
    [int]$Port = 9000,
    [string]$AppDir = 'D:\bzdev\vision-assistant',
    [string]$ApiKeys = '',
    [string]$OcrBase = 'http://127.0.0.1:48763',
    [string]$IpcheckBase = 'http://127.0.0.1:8001'
)

$NSSM   = 'C:\Users\zhangbing\AppData\Local\Microsoft\WinGet\Packages\NSSM.NSSM_Microsoft.Winget.Source_8wekyb3d8bbwe\nssm-2.24-101-g897c7ad\win64\nssm.exe'
$PY     = "$AppDir\.venv\Scripts\python.exe"
$LOGDIR = "$AppDir\logs"

if (-not (Test-Path $NSSM))   { Write-Error "nssm.exe not found at $NSSM"; exit 1 }
if (-not (Test-Path $PY))     { Write-Error "python.exe not found at $PY (先创建 .venv 并安装依赖)"; exit 1 }
if (-not (Test-Path $AppDir)) { Write-Error "app dir not found at $AppDir"; exit 1 }

if (-not (Test-Path $LOGDIR)) { New-Item -ItemType Directory -Path $LOGDIR | Out-Null }

# Idempotent: remove pre-existing service with the same name.
$existing = Get-Service -Name $Service -ErrorAction SilentlyContinue
if ($existing) {
    Write-Output "Service $Service exists — removing the old one first."
    if ($existing.Status -eq 'Running') {
        & $NSSM stop $Service 2>$null | Out-Null
        Start-Sleep -Seconds 2
    }
    & $NSSM remove $Service confirm
    Write-Output "Old service removed."
}

Write-Output "Installing service $SERVICE ..."
& $NSSM install $Service $PY "-m uvicorn app.main:app --host 0.0.0.0 --port $Port"

$envExtra = "VA_OCR_BASE_URL=$OcrBase VA_IPCHECK_BASE_URL=$IpcheckBase"
if ($ApiKeys) { $envExtra += " VA_API_KEYS=$ApiKeys" }

& $NSSM set $Service AppDirectory       $AppDir
& $NSSM set $Service AppEnvironmentExtra $envExtra
& $NSSM set $Service DisplayName        "vision-assistant (uvicorn :$Port)"
& $NSSM set $Service Description        'Unified vision capability gateway: OCR + IP consistency orchestration, task persistence, C-end console.'
& $NSSM set $Service Start              SERVICE_AUTO_START
& $NSSM set $Service AppStdout          "$LOGDIR\stdout.log"
& $NSSM set $Service AppStderr          "$LOGDIR\stderr.log"
& $NSSM set $Service AppRotateFiles     1
& $NSSM set $Service AppRotateOnline    1
& $NSSM set $Service AppRotateBytes     10485760

# Crash auto-restart after 5s.
& $NSSM set $Service AppExit Default Restart
& $NSSM set $Service AppRestartDelay    5000

Write-Output "Configuration done. Starting service..."
& $NSSM start $Service
Start-Sleep -Seconds 5

$s = Get-Service -Name $Service -ErrorAction SilentlyContinue
Write-Output ("SERVICE_STATUS: " + $s.Status)
Write-Output ("HEALTH: http://127.0.0.1:$Port/api/v1/health")
