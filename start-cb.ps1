# Compliance Buddy - Start Script
# Starts Docker (if needed), k3d cluster, and waits for all pods to be ready.

$K3D    = "C:\Users\skp4j\bin\k3d"
$DOCKER = "C:\Program Files\Docker\Docker\Docker Desktop.exe"
$CLUSTER = "compliance-buddy"
$NS      = "cb-system"

function Write-Step($n, $msg) {
    Write-Host "`n[$n] $msg" -ForegroundColor Yellow
}
function Write-Ok($msg)  { Write-Host "    $msg" -ForegroundColor Green }
function Write-Info($msg){ Write-Host "    $msg" -ForegroundColor Gray  }
function Write-Err($msg) { Write-Host "    ERROR: $msg" -ForegroundColor Red }

Write-Host ""
Write-Host "======================================" -ForegroundColor Cyan
Write-Host "  Compliance Buddy  -  Start" -ForegroundColor Cyan
Write-Host "======================================" -ForegroundColor Cyan

# ?? 1. Docker ????????????????????????????????????????????????????????????????
Write-Step "1/4" "Checking Docker..."
$dockerReady = $false
for ($i = 0; $i -lt 36; $i++) {
    $out = & docker info 2>&1
    if ($LASTEXITCODE -eq 0) { $dockerReady = $true; break }
    if ($i -eq 0) {
        Write-Info "Docker not running - launching Docker Desktop..."
        if (Test-Path $DOCKER) {
            Start-Process $DOCKER
        } else {
            Write-Info "Docker Desktop not found at default path. Please start it manually."
        }
    }
    Write-Info "Waiting for Docker... ($($i * 5)s)"
    Start-Sleep -Seconds 5
}
if (-not $dockerReady) { Write-Err "Docker did not start. Launch Docker Desktop and re-run."; Read-Host; exit 1 }
Write-Ok "Docker is ready."

# ?? 2. k3d cluster ???????????????????????????????????????????????????????????
Write-Step "2/4" "Starting k3d cluster '$CLUSTER'..."
& $K3D cluster start $CLUSTER 2>&1 | ForEach-Object { Write-Info $_ }
if ($LASTEXITCODE -ne 0) { Write-Err "k3d cluster start failed."; Read-Host; exit 1 }
Write-Ok "Cluster started."

# Ensure kubectl is pointed at the right context
kubectl config use-context "k3d-$CLUSTER" | Out-Null

# ?? 3. Nodes ready ???????????????????????????????????????????????????????????
Write-Step "3/4" "Waiting for cluster nodes..."
kubectl wait --for=condition=Ready node --all --timeout=90s | Out-Null
Write-Ok "Nodes ready."

# ?? 4. Pods ready ????????????????????????????????????????????????????????????
Write-Step "4/4" "Waiting for pods (SonarQube may take ~4 min)..."
$timeout = 420   # seconds
$elapsed = 0
$allReady = $false

while ($elapsed -lt $timeout) {
    $rows = kubectl get pods -n $NS --no-headers 2>$null
    $notReady = $rows | Where-Object {
        $_ -notmatch "\s+1/1\s+Running" -and $_ -notmatch "Completed"
    }
    if (($notReady | Measure-Object).Count -eq 0) { $allReady = $true; break }
    $count = ($notReady | Measure-Object).Count
    Write-Info "$count pod(s) still starting... (${elapsed}s / ${timeout}s)"
    Start-Sleep -Seconds 15
    $elapsed += 15
}

# ?? Summary ??????????????????????????????????????????????????????????????????
Write-Host ""
kubectl get pods -n $NS
Write-Host ""

# Hosts file reminder
$hostsOk = Select-String -Path "C:\Windows\System32\drivers\etc\hosts" `
               -Pattern "compliance-buddy" -Quiet
if (-not $hostsOk) {
    Write-Host "  WARNING: 'compliance-buddy.local' not in hosts file." -ForegroundColor DarkYellow
    Write-Host "  Run fix-hosts.vbs on your Desktop (as Administrator) to add it." -ForegroundColor DarkYellow
    Write-Host ""
}

Write-Host "======================================" -ForegroundColor Cyan
if ($allReady) {
    Write-Host "  All systems running!" -ForegroundColor Green
} else {
    Write-Host "  Stack is up (some pods may still initialise)" -ForegroundColor Yellow
}
Write-Host "======================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "  Swagger UI : http://compliance-buddy.local/swagger-ui/index.html"
Write-Host "  API health : http://compliance-buddy.local/actuator/health"
Write-Host "  SonarQube  : http://compliance-buddy.local/sonar  (admin / admin)"
Write-Host "  API Key    : dev-key-change-in-prod"
Write-Host ""
Read-Host "Press Enter to close"
