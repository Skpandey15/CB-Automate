# Compliance Buddy - Stop Script
# Gracefully stops the k3d cluster (all pods are preserved for next start).

$K3D     = "C:\Users\skp4j\bin\k3d"
$CLUSTER = "compliance-buddy"

Write-Host ""
Write-Host "======================================" -ForegroundColor Cyan
Write-Host "  Compliance Buddy  -  Stop" -ForegroundColor Cyan
Write-Host "======================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "  Stopping cluster '$CLUSTER'..." -ForegroundColor Yellow

& $K3D cluster stop $CLUSTER 2>&1 | ForEach-Object { Write-Host "  $_" -ForegroundColor Gray }

if ($LASTEXITCODE -eq 0) {
    Write-Host ""
    Write-Host "  Cluster stopped. All data is preserved." -ForegroundColor Green
    Write-Host "  Run start-cb.ps1 (or start-cb.bat) to bring it back up." -ForegroundColor Gray
} else {
    Write-Host ""
    Write-Host "  ERROR: Could not stop cluster. Is Docker running?" -ForegroundColor Red
}

Write-Host ""
Read-Host "Press Enter to close"
