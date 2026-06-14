# Compliance Buddy - Status Script

$NS = "cb-system"

Write-Host ""
Write-Host "======================================" -ForegroundColor Cyan
Write-Host "  Compliance Buddy  -  Status" -ForegroundColor Cyan
Write-Host "======================================" -ForegroundColor Cyan
Write-Host ""

Write-Host "Pods:" -ForegroundColor Yellow
kubectl get pods -n $NS
Write-Host ""

Write-Host "Ingress:" -ForegroundColor Yellow
kubectl get ingress -n $NS
Write-Host ""

Write-Host "URLs:" -ForegroundColor Yellow
Write-Host "  Swagger UI : http://compliance-buddy.local/swagger-ui/index.html"
Write-Host "  API health : http://compliance-buddy.local/actuator/health"
Write-Host "  SonarQube  : http://compliance-buddy.local/sonar"
Write-Host "  API Key    : dev-key-change-in-prod"
Write-Host ""

Read-Host "Press Enter to close"
