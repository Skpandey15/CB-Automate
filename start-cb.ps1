# Compliance Buddy - Start Script
# Starts Docker (if needed), k3d cluster, and waits for all pods to be ready.

$K3D    = "C:\Users\skp4j\bin\k3d"
$DOCKER = "C:\Program Files\Docker\Docker\Docker Desktop.exe"
$CLUSTER = "compliance-buddy"
$NS      = "cb-system"

# Pods that have known image issues in this cluster - skip them in readiness check
$KNOWN_BROKEN = @("cb-python-embedder", "opa", "ollama")

function Write-Step($n, $msg) {
    Write-Host "`n[$n] $msg" -ForegroundColor Yellow
}
function Write-Ok($msg)   { Write-Host "    OK  $msg" -ForegroundColor Green }
function Write-Info($msg) { Write-Host "    ... $msg" -ForegroundColor Gray  }
function Write-Warn($msg) { Write-Host "    WARN $msg" -ForegroundColor DarkYellow }
function Write-Err($msg)  { Write-Host "    ERROR: $msg" -ForegroundColor Red }

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Compliance Buddy  -  Start            " -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# ── 1. Docker ─────────────────────────────────────────────────────────────────
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
    Write-Info "Waiting for Docker... ($($i * 5)s elapsed)"
    Start-Sleep -Seconds 5
}
if (-not $dockerReady) {
    Write-Err "Docker did not start after 3 min. Launch Docker Desktop and re-run."
    Read-Host; exit 1
}
Write-Ok "Docker is ready."

# ── 2. k3d cluster ────────────────────────────────────────────────────────────
Write-Step "2/4" "Starting k3d cluster '$CLUSTER'..."
& $K3D cluster start $CLUSTER 2>&1 | ForEach-Object { Write-Info $_ }
if ($LASTEXITCODE -ne 0) {
    Write-Err "k3d cluster start failed."
    Read-Host; exit 1
}
Write-Ok "Cluster started."

# Point kubectl at the right context
kubectl config use-context "k3d-$CLUSTER" | Out-Null

# ── 3. Nodes ready ────────────────────────────────────────────────────────────
Write-Step "3/4" "Waiting for cluster nodes..."
kubectl wait --for=condition=Ready node --all --timeout=90s | Out-Null
Write-Ok "Nodes ready."

# ── 4. Pods ready ─────────────────────────────────────────────────────────────
Write-Step "4/4" "Waiting for pods to be ready..."
Write-Info "Expected startup times: infra ~30s | services ~60-90s | cb-notifier/mcp-server ~2-3min | SonarQube ~4min"
Write-Info "Skipping known image-unavailable pods: $($KNOWN_BROKEN -join ', ')"

$timeout = 480   # 8 minutes
$elapsed = 0
$allReady = $false

while ($elapsed -lt $timeout) {
    $rows = kubectl get pods -n $NS --no-headers 2>$null
    if (-not $rows) {
        Write-Info "No pods found yet... (${elapsed}s)"
        Start-Sleep -Seconds 10
        $elapsed += 10
        continue
    }

    $notReady = $rows | Where-Object {
        $line = $_
        # Skip pods that are Running (any N/N pattern)
        if ($line -match "\s+\d+/\d+\s+Running")  { return $false }
        # Skip completed jobs
        if ($line -match "Completed")              { return $false }
        # Skip known-broken pods (no image or resource constraints)
        foreach ($broken in $KNOWN_BROKEN) {
            if ($line -match $broken)              { return $false }
        }
        return $true
    }

    $notReadyCount = ($notReady | Measure-Object).Count
    if ($notReadyCount -eq 0) { $allReady = $true; break }

    # Show which pods are still pending
    $pendingNames = $notReady | ForEach-Object { ($_ -split "\s+")[0] }
    Write-Info "$notReadyCount pod(s) still starting (${elapsed}s / ${timeout}s): $($pendingNames -join ', ')"
    Start-Sleep -Seconds 15
    $elapsed += 15
}

# ── Summary ───────────────────────────────────────────────────────────────────
Write-Host ""
Write-Host "Pod status:" -ForegroundColor Yellow
kubectl get pods -n $NS
Write-Host ""

# Warn about known-broken pods
Write-Warn "cb-python-embedder → ImagePullBackOff (no image built - Python FastAPI, not started)"
Write-Warn "opa                → ImagePullBackOff (OPA image not available in cluster)"
Write-Warn "ollama             → CrashLoopBackOff (resource constraints on this machine)"
Write-Host ""

# Hosts file check
$hostsOk = Select-String -Path "C:\Windows\System32\drivers\etc\hosts" `
               -Pattern "compliance-buddy" -Quiet
if (-not $hostsOk) {
    Write-Host "  WARNING: 'compliance-buddy.local' not in hosts file." -ForegroundColor DarkYellow
    Write-Host "  Run fix-hosts.vbs on your Desktop (as Administrator) to add it." -ForegroundColor DarkYellow
    Write-Host ""
}

Write-Host "========================================" -ForegroundColor Cyan
if ($allReady) {
    Write-Host "  All managed services running!" -ForegroundColor Green
} else {
    Write-Host "  Stack is up (some pods may still be initialising)" -ForegroundColor Yellow
}
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "  ── Application ──────────────────────────────────────────────────" -ForegroundColor Gray
Write-Host "  Swagger UI   : http://compliance-buddy.local/swagger-ui/index.html"
Write-Host "  API health   : http://compliance-buddy.local/actuator/health"
Write-Host "  SonarQube    : http://compliance-buddy.local/sonar  (admin / admin)"
Write-Host "  MCP server   : http://compliance-buddy.local:8086/sse"
Write-Host "  API Key      : dev-key-change-in-prod"
Write-Host ""
Write-Host "  ── Observability ────────────────────────────────────────────────" -ForegroundColor Gray
Write-Host "  Grafana      : http://localhost:3000  (admin / admin)"
Write-Host "  Prometheus   : http://localhost:9090"
Write-Host ""
Write-Host "  ── Quick test ───────────────────────────────────────────────────" -ForegroundColor Gray
Write-Host '  curl -X POST http://compliance-buddy.local/api/v1/scans/my-app ^'
Write-Host '       -H "X-API-Key: dev-key-change-in-prod"'
Write-Host ""
Read-Host "Press Enter to close"
