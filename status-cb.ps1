# Compliance Buddy - Status Script

$NS = "cb-system"

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Compliance Buddy  -  Status           " -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

Write-Host "Pods (cb-system):" -ForegroundColor Yellow
kubectl get pods -n $NS
Write-Host ""

Write-Host "Services:" -ForegroundColor Yellow
kubectl get svc -n $NS
Write-Host ""

Write-Host "Ingress:" -ForegroundColor Yellow
kubectl get ingress -n $NS
Write-Host ""

Write-Host "Known image issues (not managed by CB build pipeline):" -ForegroundColor DarkYellow
Write-Host "  cb-python-embedder  ImagePullBackOff  - Python FastAPI, no image built"
Write-Host "  opa                 ImagePullBackOff  - OPA policy engine"
Write-Host "  ollama              CrashLoopBackOff  - resource constraints"
Write-Host ""

Write-Host "── Application ──────────────────────────────────────────────────" -ForegroundColor Gray
Write-Host "  Swagger UI   : http://compliance-buddy.local/swagger-ui/index.html"
Write-Host "  API health   : http://compliance-buddy.local/actuator/health  (no auth)"
Write-Host "  SonarQube    : http://compliance-buddy.local/sonar  (admin / admin)"
Write-Host "  MCP server   : http://compliance-buddy.local:8086/sse"
Write-Host "  API Key      : dev-key-change-in-prod"
Write-Host ""
Write-Host "── Observability ────────────────────────────────────────────────" -ForegroundColor Gray
Write-Host "  Grafana      : http://localhost:3000  (admin / admin)"
Write-Host "  Prometheus   : http://localhost:9090"
Write-Host ""
Write-Host "── Key endpoints ────────────────────────────────────────────────" -ForegroundColor Gray
Write-Host "  Vulnerabilities : /api/v1/vulnerabilities"
Write-Host "  Fixes           : /api/v1/fixes"
Write-Host "  Metrics         : /api/v1/metrics"
Write-Host "  LLM cost        : /api/v1/cost/summary"
Write-Host "  Trigger scan    : POST /api/v1/scans/{projectKey}"
Write-Host ""

Read-Host "Press Enter to close"
