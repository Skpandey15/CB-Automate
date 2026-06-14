# Compliance Buddy (CB)

Autonomous security remediation agent. Detects SonarQube vulnerabilities, generates
AI-powered fixes via GPT-4o / Claude, applies patches, opens GitHub PRs, and notifies
the team -- all through an event-driven Spring pipeline.

---

## Table of Contents

1. [Architecture](#architecture)
2. [Modules](#modules)
3. [Start / Stop the Stack](#start--stop-the-stack)
4. [First-Time Configuration](#first-time-configuration)
5. [Testing Guide](#testing-guide)
6. [API Reference](#api-reference)
7. [Swagger UI](#swagger-ui)
8. [Configuration Reference](#configuration-reference)
9. [Event Flow](#event-flow)
10. [CWE Coverage](#cwe-coverage)

---

## Architecture

```
SonarQube --> cb-scanner --> VulnerabilityDetectedEvent
                                      |
                                      v
                               cb-agent (GPT-4o / Claude)
                               |-- RAG: MongoDB vector search
                               |-- MCP: code context tool calls
                               `--> FixGeneratedEvent
                                          |
                                          v
                                   cb-patcher (JGit + ./gradlew)
                                   `--> FixValidatedEvent / EscalationEvent
                                                |
                                                v
                                         cb-pr (GitHub + Version1)
                                         `--> PRRaisedEvent
                                                    |
                                                    v
                                             cb-notifier (JavaMail)
```

All events are in-process Spring ApplicationEvents -- no Kafka, no Redis.

---

## Modules

| Module               | Port | Responsibility                                   |
|----------------------|------|--------------------------------------------------|
| cb-core              | --   | Domain records, events, repos, async/cache config|
| cb-scanner           | 8081 | SonarQube REST API poller                        |
| cb-agent             | 8082 | LLM fix generation (Spring AI + RAG)             |
| cb-patcher           | 8083 | Diff application + build validation              |
| cb-pr                | 8084 | GitHub PR creation + Version1 ticket             |
| cb-notifier          | 8085 | Email (JavaMail)                                 |
| cb-api               | 8080 | REST API + Swagger UI + API-key auth             |
| cb-python-embedder   | 8090 | FastAPI -- text-embedding-3-large (3072 dims)    |

---

## Start / Stop the Stack

The stack runs in a local k3d (Kubernetes) cluster backed by Docker Desktop.

> **After every machine restart**, Docker Desktop must be running before the cluster starts.

### Double-click (easiest)

| Script         | What it does                                                     |
|----------------|------------------------------------------------------------------|
| start-cb.bat   | Starts Docker if needed -> starts k3d -> waits for pods -> URLs  |
| stop-cb.bat    | Stops k3d cluster (all data is preserved for next start)         |
| status-cb.bat  | Shows current pod status and URLs                                |

### PowerShell directly

```powershell
PowerShell -ExecutionPolicy Bypass -File start-cb.ps1
PowerShell -ExecutionPolicy Bypass -File stop-cb.ps1
PowerShell -ExecutionPolicy Bypass -File status-cb.ps1
```

### Startup times after restart

| Service                                          | Ready after |
|--------------------------------------------------|-------------|
| MongoDB                                          | ~20 s       |
| cb-api, cb-scanner, cb-agent, cb-patcher, cb-pr  | ~45 s       |
| cb-notifier                                      | ~2 min      |
| SonarQube                                        | ~4 min      |

---

## First-Time Configuration

Edit `k8s/base/secrets.yaml` and replace the placeholders.
Re-apply after every change:

```powershell
kubectl apply -k k8s/base/
```

### Minimum -- AI fix generation

You need **at least one** AI key:

```yaml
OPENAI_API_KEY: "sk-..."           # GPT-4o (primary)
ANTHROPIC_API_KEY: "sk-ant-..."    # Claude (fallback -- leave blank if not needed)
```

If only one key is set, that model is used as both primary and fallback.

### Full pipeline

```yaml
# SonarQube -- use the in-cluster instance (see Testing Guide Step 1)
SONARQUBE_TOKEN: "squ_..."
SCANNER_PROJECTS: "my-project-key"

# GitHub -- automatic PR creation
GITHUB_TOKEN: "ghp_..."
GITHUB_OWNER: "your-org-or-username"
GITHUB_REPO: "your-repo-name"

# Email notifications
SMTP_HOST: "smtp.gmail.com"
SMTP_PORT: "587"
SMTP_USERNAME: "you@gmail.com"
SMTP_PASSWORD: "your-app-password"
NOTIFIER_TEAM_EMAILS: "you@gmail.com,teammate@gmail.com"
```

> **API key for the REST API:** `CB_API_KEYS` is currently `dev-key-change-in-prod`.
> Use this value in every request header. Change it before exposing CB on a network.

---

## Testing Guide

### Access points

| URL                                                        | Description                  |
|------------------------------------------------------------|------------------------------|
| http://compliance-buddy.local/swagger-ui/index.html        | Interactive API docs          |
| http://compliance-buddy.local/actuator/health              | Health check (no auth)        |
| http://compliance-buddy.local/sonar                        | SonarQube UI (admin / admin)  |
| http://compliance-buddy.local/api/v1/vulnerabilities       | Vulnerabilities list          |
| http://compliance-buddy.local/api/v1/fixes                 | Generated fixes               |
| http://compliance-buddy.local/api/v1/metrics               | Dashboard summary             |

All API calls require this header:

```
X-API-Key: dev-key-change-in-prod
```

---

### Step 1 -- Set Up SonarQube

SonarQube runs inside the cluster at **http://compliance-buddy.local/sonar**

1. Login: `admin` / `admin` -- you will be prompted to set a new password.
2. Click **Projects** -> **Create Project** -> **Manually**.
3. Enter a **Project Key** (e.g. `my-app`). This key goes into the scan API call.
4. Click **Set Up** -> **Locally** -> **Generate a token** -> copy it.
5. Update `k8s/base/secrets.yaml`:
   ```yaml
   SONARQUBE_TOKEN: "squ_your_token_here"
   SCANNER_PROJECTS: "my-app"
   ```
6. Re-apply:
   ```powershell
   kubectl apply -k k8s/base/
   ```
7. Run the SonarQube scanner against your source code, pointing it at
   `http://compliance-buddy.local/sonar`. CB will pull the findings automatically.

---

### Step 2 -- Trigger a Scan

Once SonarQube has issues, tell CB to pull them:

**Via Swagger UI:**
1. Open http://compliance-buddy.local/swagger-ui/index.html
2. Click **Authorize** (padlock top-right) -> enter `dev-key-change-in-prod` -> **Authorize**
3. Expand **Scans** -> `POST /api/v1/scans/{projectKey}` -> **Try it out**
4. Enter `my-app` -> **Execute**

**Via curl (Git Bash or WSL):**
```bash
curl -X POST http://compliance-buddy.local/api/v1/scans/my-app \
     -H "X-API-Key: dev-key-change-in-prod"
```

**Via PowerShell:**
```powershell
$h = @{ "X-API-Key" = "dev-key-change-in-prod" }
Invoke-RestMethod -Method POST -Headers $h `
  -Uri "http://compliance-buddy.local/api/v1/scans/my-app"
```

Expected response:
```json
{ "projectKey": "my-app", "newFindings": 5, "status": "completed" }
```

---

### Step 3 -- Watch the Pipeline

After a scan, cb-agent picks up each vulnerability and runs the pipeline automatically.
Poll the list to watch state progress:

```powershell
$h = @{ "X-API-Key" = "dev-key-change-in-prod" }
$base = "http://compliance-buddy.local/api/v1"

# All vulnerabilities
Invoke-RestMethod -Uri "$base/vulnerabilities" -Headers $h

# Filter by state
Invoke-RestMethod -Uri "$base/vulnerabilities?status=DETECTED"      -Headers $h
Invoke-RestMethod -Uri "$base/vulnerabilities?status=FIX_GENERATED"  -Headers $h
Invoke-RestMethod -Uri "$base/vulnerabilities?status=RESOLVED"       -Headers $h

# Filter by severity
Invoke-RestMethod -Uri "$base/vulnerabilities?severity=CRITICAL" -Headers $h
```

Follow the logs to see it in real time:
```powershell
kubectl logs -n cb-system -l app=cb-agent   --tail=50 -f
kubectl logs -n cb-system -l app=cb-scanner --tail=20
kubectl logs -n cb-system -l app=cb-patcher --tail=20
```

---

### Step 4 -- Query Results

**All AI-generated fixes:**
```powershell
$h = @{ "X-API-Key" = "dev-key-change-in-prod" }
Invoke-RestMethod -Uri "http://compliance-buddy.local/api/v1/fixes" -Headers $h
```

**Fix by ID:**
```powershell
Invoke-RestMethod -Uri "http://compliance-buddy.local/api/v1/fixes/{id}" -Headers $h
```

**Metrics dashboard:**
```powershell
Invoke-RestMethod -Uri "http://compliance-buddy.local/api/v1/metrics" -Headers $h
```

Example response:
```json
{
  "vulnerabilities": {
    "DETECTED": 2,
    "IN_PROGRESS": 1,
    "FIX_GENERATED": 3,
    "RESOLVED": 7
  },
  "fixes": {
    "PENDING": 1,
    "APPLIED": 5,
    "FAILED": 1
  },
  "remediationRate": "53.8%"
}
```

**Audit trail:**
```powershell
Invoke-RestMethod -Uri "http://compliance-buddy.local/api/v1/audit" -Headers $h
```

**Retry a failed fix:**
```powershell
Invoke-RestMethod -Method POST -Headers $h `
  -Uri "http://compliance-buddy.local/api/v1/vulnerabilities/retry/{id}"
```

---

## API Reference

All endpoints require `X-API-Key: dev-key-change-in-prod` unless noted.

| Method | Path                                        | Description                                           |
|--------|---------------------------------------------|-------------------------------------------------------|
| POST   | /api/v1/scans/{projectKey}                  | Pull SonarQube issues for a project                   |
| GET    | /api/v1/vulnerabilities                     | List (optional: ?status= ?severity= ?projectKey=)     |
| GET    | /api/v1/vulnerabilities/{id}                | Get one vulnerability by ID                           |
| POST   | /api/v1/vulnerabilities/retry/{id}          | Re-queue AI fix generation                            |
| GET    | /api/v1/fixes                               | List all AI-generated fixes                           |
| GET    | /api/v1/fixes/{id}                          | Get one fix by ID                                     |
| GET    | /api/v1/metrics                             | Remediation dashboard (counts + rate)                 |
| GET    | /api/v1/audit                               | Full audit trail of every action                      |
| GET    | /actuator/health                            | Health status (no auth required)                      |

---

## Swagger UI

1. Open **http://compliance-buddy.local/swagger-ui/index.html**
2. Click the **Authorize** padlock (top right of the page)
3. Enter `dev-key-change-in-prod` in the `ApiKeyAuth` field -> **Authorize** -> **Close**
4. Use **Try it out** on any endpoint -- the key is sent automatically

---

## Configuration Reference

All secrets live in `k8s/base/secrets.yaml`.
After editing, apply and restart pods to pick up new values:

```powershell
kubectl apply -k k8s/base/
kubectl rollout restart deployment -n cb-system
```

| Variable                   | Module            | Description                              |
|----------------------------|-------------------|------------------------------------------|
| CB_API_KEYS                | cb-api            | Comma-separated valid REST API keys      |
| OPENAI_API_KEY             | cb-agent, embedder| GPT-4o (primary LLM)                     |
| ANTHROPIC_API_KEY          | cb-agent          | Claude (fallback -- optional)            |
| SONARQUBE_URL              | cb-scanner        | http://cb-sonarqube:9000/sonar           |
| SONARQUBE_TOKEN            | cb-scanner        | SonarQube user token                     |
| SCANNER_PROJECTS           | cb-scanner        | Comma-separated project keys             |
| GITHUB_TOKEN               | cb-pr             | GitHub PAT with repo scope               |
| GITHUB_OWNER               | cb-pr             | GitHub org or username                   |
| GITHUB_REPO                | cb-pr             | Repository name                          |
| GITHUB_API_URL             | cb-pr             | https://api.github.com (or GHE URL)      |
| SMTP_HOST / SMTP_PORT      | cb-notifier       | SMTP server details                      |
| SMTP_USERNAME / PASSWORD   | cb-notifier       | SMTP credentials                         |
| NOTIFIER_TEAM_EMAILS       | cb-notifier       | Comma-separated email recipients         |
| DATADOG_API_KEY            | cb-notifier       | Set to "none" to disable Datadog         |

---

## Event Flow

```
DETECTED       Vulnerability found in SonarQube by cb-scanner
    |
IN_PROGRESS    cb-agent is calling the LLM
    |
FIX_GENERATED  LLM returned a patch diff -- saved to MongoDB
    |
FIX_VALIDATED  cb-patcher applied diff and ran ./gradlew build test (green)
    |
PR_RAISED      cb-pr opened a GitHub pull request
    |
RESOLVED       PR merged

    +-- ESCALATED   Max retries exceeded or build failed -- team email sent
```

---

## CWE Coverage

| CWE     | Description              | Strategy                                |
|---------|--------------------------|-----------------------------------------|
| CWE-89  | SQL Injection            | PreparedStatement / JPA named params    |
| CWE-79  | XSS                      | HtmlUtils.htmlEscape + CSP header       |
| CWE-78  | Command Injection        | ProcessBuilder with arg list            |
| CWE-22  | Path Traversal           | Path canonicalization + base-dir check  |
| CWE-798 | Hardcoded Credentials    | Vault / @Value injection                |
| CWE-327 | Broken Crypto            | SHA-256+ / BCrypt                       |
| CWE-918 | SSRF                     | URL allow-list validation               |
| CWE-330 | Insufficient Randomness  | SecureRandom                            |

---

## Tech Stack

Java 17 - Spring Boot 3.3 - Spring AI 1.0 - MongoDB - Caffeine Cache - JGit -
Resilience4j - Python 3.12 - FastAPI - OpenAI text-embedding-3-large -
Docker - k3d - Kubernetes - NGINX Ingress
