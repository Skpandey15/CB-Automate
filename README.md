# Compliance Buddy (CB)

Architecture-impacting contributions must follow the [ADR governance guide](docs/adr/README.md)
and reference an Accepted decision in the [PR template](.github/pull_request_template.md).

## Dependency remediation from a GitHub branch

For **repository + branch → dependency scan → verified upgrades → PR → email**, use
the [branch dependency remediation runner](docs/dependency-remediation.md):

```powershell
python scripts/dependency-remediation/remediate.py --repo Skpandey15/CB-Automate --branch main
```

This previews in an isolated checkout. Add `--publish --recipient team@example.com`
after configuring GitHub authentication and SMTP to publish and notify. Maven and
Gradle resolved dependencies are scanned, including transitives; unsupported fixes
are reported for manual remediation. See [ADR 0001](docs/adr/0001-branch-dependency-remediation.md)
for scope and design. This runner is independent of the legacy Java dependency
endpoints and the SonarQube pipeline described below.

## SonarQube and AI code remediation

Autonomous security remediation agent. Detects SonarQube vulnerabilities, generates
AI-powered fixes via a multi-agent LangGraph4j workflow (GPT-4o / Claude / Ollama),
applies patches, opens GitHub PRs, and escalates to Jira + Teams — all through a
Kafka-backed event-driven Spring microservices pipeline.

---

## Table of Contents

1. [Architecture](#architecture)
2. [Modules](#modules)
3. [Infrastructure](#infrastructure)
4. [Start / Stop the Stack](#start--stop-the-stack)
5. [First-Time Configuration](#first-time-configuration)
6. [Testing Guide](#testing-guide)
7. [API Reference](#api-reference)
8. [MCP Tools](#mcp-tools)
9. [Observability](#observability)
10. [Event Flow](#event-flow)
11. [CWE Coverage](#cwe-coverage)
12. [Configuration Reference](#configuration-reference)
13. [Tech Stack](#tech-stack)

---

## Architecture

```
SonarQube ──► cb-scanner ──[Kafka: vulnerabilities.detected]──► cb-agent
                                                                     │
                              ┌──────────────────────────────────────┘
                              │   LangGraph4j multi-agent workflow
                              │   ┌─ planner  (severity → model routing)
                              │   ├─ retriever (Qdrant + Elasticsearch RRF)
                              │   ├─ generator (GPT-4o / Claude / Ollama)
                              │   ├─ validator (JSON-LD + safety checks)
                              │   └─ reviewer  (Anthropic fallback review)
                              │
                              ▼
                     [Kafka: fixes.generated]
                              │
                              ▼
                         cb-patcher (JGit + ./gradlew build)
                              │
                    ┌─────────┴─────────┐
                    ▼                   ▼
         [Kafka: fixes.validated]  [Kafka: escalations.triggered]
                    │                   │
                    ▼                   ▼
              cb-pr (OPA gate      cb-escalation (Jira + Teams + RCA)
              + GitHub PR          cb-notifier   (email + Datadog)
              + Version1 ticket)
                    │
         [Kafka: review.feedback]
                    │
                    ▼
             cb-agent (RAG feedback loop — ACCEPTED fixes
                       indexed into Qdrant for future retrieval)
```

**Infrastructure:** Kafka (KRaft), MongoDB, Redis, Elasticsearch, Qdrant, OPA,
Ollama, Prometheus, Grafana, Tempo, Loki — all running in-cluster.

---

## Modules

| Module             | Port | Responsibility                                                      |
|--------------------|------|---------------------------------------------------------------------|
| cb-core            | —    | Domain records, Kafka events, repositories, async/cache config      |
| cb-scanner         | 8081 | SonarQube poller → publishes to `vulnerabilities.detected`          |
| cb-agent           | 8082 | LangGraph4j workflow: hybrid RAG + multi-model fix generation       |
| cb-patcher         | 8083 | Diff application (JGit) + build validation (Gradle/Maven)           |
| cb-pr              | 8084 | OPA governance gate + GitHub PR + Version1 ticket + AI review       |
| cb-notifier        | 8085 | Email (JavaMail) + Datadog event on PR raised / escalation          |
| cb-mcp-server      | 8086 | 12 MCP tools for AI assistants (fixes, PRs, traces, metrics)        |
| cb-escalation      | 8089 | Autonomous Jira ticket + Teams adaptive card + RCA report           |
| cb-api             | 8080 | REST API + Swagger UI + API-key auth + Prometheus metrics           |
| cb-python-embedder | 8090 | FastAPI — text-embedding-3-large (3072 dims) for vector indexing    |

---

## Infrastructure

All infrastructure runs in the `cb-system` Kubernetes namespace inside a local k3d cluster.

| Component      | Port (NodePort) | Purpose                                         |
|----------------|-----------------|-------------------------------------------------|
| Kafka (KRaft)  | 9092            | Event bus — no ZooKeeper                        |
| MongoDB        | 27017           | Primary datastore (vulnerabilities, fixes, cost)|
| Redis          | 6379            | LLM prompt cache (SHA-256, 24 h TTL)            |
| Elasticsearch  | 9200            | BM25 lexical search for hybrid RAG              |
| Qdrant         | 6333            | Vector store for semantic RAG (cb_fixes)        |
| OPA            | 8181            | Governance policy engine (Rego)                 |
| Ollama         | 11434           | Local LLM inference (llama3:8b, qwen2:7b)       |
| SonarQube      | —               | Code quality / security scanner                 |
| Prometheus     | 9090            | Metrics scraping                                |
| Grafana        | 3000            | Dashboards                                      |
| Tempo          | 4318            | OTLP distributed tracing                        |
| Loki           | 3100            | Log aggregation                                 |

---

## Start / Stop the Stack

The stack runs in a local k3d cluster backed by Docker Desktop.

> **After every machine restart**, Docker Desktop must be running before the cluster starts.

### Double-click (easiest)

| Script        | What it does                                                    |
|---------------|-----------------------------------------------------------------|
| start-cb.bat  | Starts Docker if needed → starts k3d → waits for pods → URLs   |
| stop-cb.bat   | Stops k3d cluster (all data preserved)                          |
| status-cb.bat | Shows current pod status and URLs                               |

### PowerShell directly

```powershell
PowerShell -ExecutionPolicy Bypass -File start-cb.ps1
PowerShell -ExecutionPolicy Bypass -File stop-cb.ps1
PowerShell -ExecutionPolicy Bypass -File status-cb.ps1
```

### Startup times after cluster restart

| Service                                | Ready after |
|----------------------------------------|-------------|
| Kafka, MongoDB, Redis, Elasticsearch   | ~30 s       |
| cb-scanner, cb-patcher, cb-pr          | ~60 s       |
| cb-agent, cb-api, cb-escalation        | ~90 s       |
| cb-notifier, cb-mcp-server             | ~2–3 min    |
| SonarQube                              | ~4 min      |

---

## First-Time Configuration

Edit `k8s/base/secrets.yaml` and replace the placeholders.
Re-apply after every change:

```powershell
kubectl apply -k k8s/base/
kubectl rollout restart deployment -n cb-system
```

### Minimum — AI fix generation

You need **at least one** AI key:

```yaml
OPENAI_API_KEY: "sk-..."           # GPT-4o (primary, used for CRITICAL/BLOCKER)
ANTHROPIC_API_KEY: "sk-ant-..."    # Claude (review + fallback — optional)
```

Ollama runs locally in-cluster for MAJOR/MINOR/INFO severities (no API key needed).

### Full pipeline

```yaml
# SonarQube — use the in-cluster instance (see Testing Guide Step 1)
SONARQUBE_TOKEN: "squ_..."
SCANNER_PROJECTS: "my-project-key"

# GitHub — automatic PR creation
GITHUB_TOKEN: "ghp_..."
GITHUB_OWNER: "your-org-or-username"
GITHUB_REPO: "your-repo-name"

# Version1 / VersionOne — task creation
VERSION1_API_URL: "https://your-instance.v1host.com"
VERSION1_TOKEN: "your-v1-token"

# Jira escalation (optional — disabled by default)
JIRA_ENABLED: "true"
JIRA_BASE_URL: "https://your-org.atlassian.net"
JIRA_EMAIL: "you@example.com"
JIRA_API_TOKEN: "your-jira-token"

# Teams notifications (optional — disabled by default)
TEAMS_ENABLED: "true"
TEAMS_WEBHOOK_URL: "https://your-org.webhook.office.com/..."

# Email notifications
SMTP_HOST: "smtp.gmail.com"
SMTP_PORT: "587"
SMTP_USERNAME: "you@gmail.com"
SMTP_PASSWORD: "your-app-password"
NOTIFIER_TEAM_EMAILS: "you@gmail.com,teammate@gmail.com"
```

> **REST API key:** `CB_API_KEYS` defaults to `dev-key-change-in-prod`.
> Change before exposing CB on any shared network.

---

## Testing Guide

### Access points

| URL                                                             | Description                     |
|-----------------------------------------------------------------|---------------------------------|
| http://compliance-buddy.local/swagger-ui/index.html            | Interactive API docs             |
| http://compliance-buddy.local/actuator/health                  | Health check (no auth)           |
| http://compliance-buddy.local/sonar                            | SonarQube UI (admin / admin)     |
| http://compliance-buddy.local/api/v1/vulnerabilities           | Vulnerability list               |
| http://compliance-buddy.local/api/v1/fixes                     | Generated fixes                  |
| http://compliance-buddy.local/api/v1/metrics                   | Dashboard summary                |
| http://compliance-buddy.local/api/v1/cost/summary              | LLM cost breakdown               |
| http://localhost:3000                                           | Grafana dashboards               |
| http://localhost:9090                                           | Prometheus                       |

All API calls require:
```
X-API-Key: dev-key-change-in-prod
```

---

### Step 1 — Set Up SonarQube

SonarQube runs inside the cluster at **http://compliance-buddy.local/sonar**

1. Login: `admin` / `admin` — set a new password when prompted.
2. **Projects → Create Project → Manually**
3. Enter a **Project Key** (e.g. `my-app`) and click **Set Up → Locally → Generate a token**.
4. Copy the token and update `k8s/base/secrets.yaml`:
   ```yaml
   SONARQUBE_TOKEN: "squ_your_token_here"
   SCANNER_PROJECTS: "my-app"
   ```
5. Re-apply:
   ```powershell
   kubectl apply -k k8s/base/
   kubectl rollout restart deployment/cb-scanner -n cb-system
   ```
6. Run the SonarQube scanner against your source code pointing at
   `http://compliance-buddy.local/sonar`. CB pulls findings on a 5-minute schedule.

---

### Step 2 — Trigger a Scan

**Via Swagger UI:**
1. Open http://compliance-buddy.local/swagger-ui/index.html
2. **Authorize** → enter `dev-key-change-in-prod` → **Authorize**
3. Expand **Scans → POST /api/v1/scans/{projectKey} → Try it out**
4. Enter `my-app` → **Execute**

**Via PowerShell:**
```powershell
$h = @{ "X-API-Key" = "dev-key-change-in-prod" }
Invoke-RestMethod -Method POST -Headers $h `
  -Uri "http://compliance-buddy.local/api/v1/scans/my-app"
```

**Via curl:**
```bash
curl -X POST http://compliance-buddy.local/api/v1/scans/my-app \
     -H "X-API-Key: dev-key-change-in-prod"
```

Expected response:
```json
{ "projectKey": "my-app", "newFindings": 5, "status": "completed" }
```

---

### Step 3 — Watch the Pipeline

Each vulnerability is processed by the 5-node LangGraph4j workflow automatically.

```powershell
$h   = @{ "X-API-Key" = "dev-key-change-in-prod" }
$base = "http://compliance-buddy.local/api/v1"

# All vulnerabilities
Invoke-RestMethod -Uri "$base/vulnerabilities" -Headers $h

# Filter by status
Invoke-RestMethod -Uri "$base/vulnerabilities?status=DETECTED"      -Headers $h
Invoke-RestMethod -Uri "$base/vulnerabilities?status=FIX_GENERATED"  -Headers $h
Invoke-RestMethod -Uri "$base/vulnerabilities?status=RESOLVED"       -Headers $h

# Filter by severity
Invoke-RestMethod -Uri "$base/vulnerabilities?severity=CRITICAL" -Headers $h
```

**Follow logs in real time:**
```powershell
kubectl logs -n cb-system -l app=cb-agent     --tail=50 -f
kubectl logs -n cb-system -l app=cb-scanner   --tail=20 -f
kubectl logs -n cb-system -l app=cb-patcher   --tail=20 -f
kubectl logs -n cb-system -l app=cb-pr        --tail=20 -f
kubectl logs -n cb-system -l app=cb-escalation --tail=20 -f
```

---

### Step 4 — Query Results

**Fixes:**
```powershell
$h = @{ "X-API-Key" = "dev-key-change-in-prod" }
Invoke-RestMethod -Uri "http://compliance-buddy.local/api/v1/fixes"      -Headers $h
Invoke-RestMethod -Uri "http://compliance-buddy.local/api/v1/fixes/{id}" -Headers $h
```

**Metrics dashboard:**
```powershell
Invoke-RestMethod -Uri "http://compliance-buddy.local/api/v1/metrics" -Headers $h
```
```json
{
  "vulnerabilities": { "DETECTED": 2, "IN_PROGRESS": 1, "FIX_GENERATED": 3, "RESOLVED": 7 },
  "fixes": { "PENDING": 1, "BUILD_VALIDATED": 5, "FAILED": 1 },
  "remediationRate": "53.8%"
}
```

**LLM cost breakdown:**
```powershell
Invoke-RestMethod -Uri "http://compliance-buddy.local/api/v1/cost/summary"   -Headers $h
Invoke-RestMethod -Uri "http://compliance-buddy.local/api/v1/cost/by-cwe"    -Headers $h
Invoke-RestMethod -Uri "http://compliance-buddy.local/api/v1/cost/by-model"  -Headers $h
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
| GET    | /api/v1/vulnerabilities/{id}                | Get one vulnerability                                 |
| POST   | /api/v1/vulnerabilities/retry/{id}          | Re-queue AI fix generation                            |
| GET    | /api/v1/fixes                               | List all AI-generated fixes                           |
| GET    | /api/v1/fixes/{id}                          | Get one fix by ID                                     |
| GET    | /api/v1/metrics                             | Remediation dashboard (counts + rate)                 |
| GET    | /api/v1/audit                               | Full audit trail                                      |
| GET    | /api/v1/audit/entity/{entityId}             | Audit events for a specific entity                    |
| GET    | /api/v1/cost/summary                        | LLM cost summary (default: last 7 days)               |
| GET    | /api/v1/cost/by-cwe                         | Cost breakdown by CWE (default: last 30 days)         |
| GET    | /api/v1/cost/by-model                       | Cost breakdown by model (default: last 30 days)       |
| POST   | /api/v1/remediation-runs                    | Start a dependency-CVE remediation run for any GitHub repo/branch (ADR-0002 Track A) |
| GET    | /api/v1/remediation-runs/{runId}            | Get the status/result of a remediation run             |
| GET    | /actuator/health                            | Health status (no auth)                               |
| GET    | /actuator/prometheus                        | Prometheus metrics scrape endpoint                    |

---

## MCP Tools

`cb-mcp-server` exposes 14 MCP (Model Context Protocol) tools that AI assistants
(Claude Desktop, Cursor, etc.) can call to interact with the CB pipeline.

| Tool                     | Description                                                    |
|--------------------------|------------------------------------------------------------------|
| findPreviousFixes        | Find validated fixes by CWE ID (limit 1–10)                    |
| getSonarIssue            | Get vulnerability detail by SonarQube issue key                |
| getPRDiff                | Fetch unified diff of a GitHub PR                               |
| buildProject             | Trigger Gradle build on a branch via cb-patcher                |
| createPR                 | Create a GitHub PR (title, body, source/target branch)         |
| getBuildLog              | Retrieve full build log by fix ID                               |
| searchPastIncidents      | Search vulnerabilities by keyword and optional CWE filter      |
| queryQdrant              | Natural-language search over the fix vector store               |
| regenerateFix            | Reset vulnerability to DETECTED for reprocessing                |
| getTrace                 | Fetch distributed trace from Tempo by trace ID                  |
| getMetrics               | Query Prometheus with PromQL                                     |
| triggerRollback          | Revert a merged fix via git revert on cb-patcher                |
| triggerRemediationRun    | Start a dependency-CVE remediation run for **any** GitHub repo/branch (ADR-0002 Track A) — not limited to CB's own configured repo |
| getRemediationRunStatus  | Poll the status/result of a run started with triggerRemediationRun |

**Connect to Claude Desktop** — add to `claude_desktop_config.json`:
```json
{
  "mcpServers": {
    "compliance-buddy": {
      "url": "http://compliance-buddy.local:8086/sse"
    }
  }
}
```

---

## Observability

| Tool       | URL                         | What to look at                              |
|------------|-----------------------------|----------------------------------------------|
| Grafana    | http://localhost:3000       | CB pipeline dashboards (admin / admin)        |
| Prometheus | http://localhost:9090       | Raw metrics, PromQL queries                   |
| Tempo      | via Grafana Explore → Tempo | Distributed traces for each vulnerability ID  |
| Loki       | via Grafana Explore → Loki  | Aggregated logs from all CB pods              |

**Key Prometheus metrics:**

| Metric                           | Description                         |
|----------------------------------|-------------------------------------|
| `cb_vulnerabilities_detected_total` | Counter — new vulns found        |
| `cb_fixes_generated_total`          | Counter — AI fixes produced      |
| `cb_prs_created_total`              | Counter — PRs opened             |
| `cb_escalations_triggered_total`    | Counter — Jira/Teams escalations |
| `cb_llm_cost_usd_total`             | Counter — cumulative LLM spend   |
| `cb_llm_tokens_total`               | Counter — tokens by model        |
| `cb_fix_confidence`                 | Gauge — last fix confidence score|
| `cb_pipeline_duration_seconds`      | Histogram — end-to-end latency   |

---

## Event Flow

**Kafka topics and the status each maps to:**

```
vulnerabilities.detected  →  cb-agent consumes  (vuln status: DETECTED → IN_PROGRESS)
fixes.generated           →  cb-patcher consumes (vuln status: FIX_GENERATED)
fixes.validated           →  cb-pr consumes      (vuln status: FIX_VALIDATED)
escalations.triggered     →  cb-escalation + cb-notifier consume
review.feedback           →  cb-agent consumes   (accepted fixes → Qdrant RAG index)
cost.tracked              →  cb-api persists LLM cost records
```

**Vulnerability lifecycle:**

```
DETECTED
    │
IN_PROGRESS    cb-agent LangGraph4j workflow running
    │
FIX_GENERATED  LLM patch diff saved to MongoDB
    │
FIX_VALIDATED  cb-patcher applied diff + build passed (./gradlew build test)
    │
PR_RAISED      cb-pr opened GitHub PR + OPA governance passed
    │
RESOLVED       PR merged

    ├─► ESCALATED   Retry limit exceeded / build failed → Jira + Teams + RCA
    └─► FAILED      OPA blocked / critical error
```

**Multi-model routing (cb-agent PlannerNode):**

| Severity        | Model        | Rationale                   |
|-----------------|--------------|-----------------------------|
| CRITICAL/BLOCKER| GPT-4o       | Highest accuracy required   |
| MAJOR           | qwen2:7b     | Balanced quality/cost        |
| MINOR/INFO      | llama3:8b    | Local inference, zero cost   |

---

## CWE Coverage

| CWE      | Description                        | Strategy                                         |
|----------|------------------------------------|--------------------------------------------------|
| CWE-89   | SQL Injection                      | PreparedStatement / JPA named params             |
| CWE-79   | XSS                                | HtmlUtils.htmlEscape + CSP header                |
| CWE-78   | Command Injection                  | ProcessBuilder with arg list                     |
| CWE-22   | Path Traversal                     | Path canonicalization + base-dir check           |
| CWE-798  | Hardcoded Credentials              | Vault / @Value injection                         |
| CWE-327  | Broken Crypto                      | SHA-256+ / BCrypt                                |
| CWE-918  | SSRF                               | URL allow-list validation                        |
| CWE-330  | Insufficient Randomness            | SecureRandom                                     |
| CWE-1104 | Vulnerable/Outdated Dependency     | Upgrade to latest stable via OSS Index + Maven Central |

---

## Dependency Vulnerability Scanning

CB now detects CVEs in Gradle dependencies (not just source-code issues) and automatically upgrades the vulnerable version in `build.gradle`.

### How it works

```
build.gradle files ──► GradleDependencyParser
                              │
                        (list of group:artifact:version)
                              │
                        OssIndexClient (pkg:maven purl query)
                              │
                        CVEs found? ──► MavenCentralClient (latest stable version)
                              │
                        Vulnerability(type=DEPENDENCY, safeVersion=X.Y.Z)
                              │
                        [Kafka: vulnerabilities.detected]
                              │
                        cb-agent (GPT-4o + getSafeVersion tool)
                              │
                        Fix(gradlePatch="group:artifact:old -> new", patchDiff=null)
                              │
                        cb-patcher (GradlePatcher walks ALL build.gradle / .kts / gradle.properties)
                              │
                        Build validation ──► PR raised ──► RESOLVED
```

### Trigger a dependency scan

```powershell
$h = @{ "X-API-Key" = "dev-key-change-in-prod" }

# Scan the configured repo root
Invoke-RestMethod -Method POST -Headers $h `
  -Uri "http://compliance-buddy.local/api/v1/scans/deps"

# Or with a custom project key for grouping
Invoke-RestMethod -Method POST -Headers $h `
  -Uri "http://compliance-buddy.local/api/v1/scans/deps/my-project"
```

### Configuration

Add to `k8s/base/secrets.yaml`:

```yaml
# Path to the repo CB should scan — must be mounted in the cb-scanner pod
DEP_SCANNER_REPO_ROOT: "/workspace/repo"

# Optional: OSS Index credentials for higher rate limits (free at ossindex.sonatype.org)
OSS_INDEX_USERNAME: ""
OSS_INDEX_TOKEN: ""
```

The scanner also runs automatically every 10 minutes (configurable via `DEP_SCANNER_POLL_INTERVAL_MS`).

---

## Configuration Reference

All secrets live in `k8s/base/secrets.yaml`. Apply and restart to pick up changes:

```powershell
kubectl apply -k k8s/base/
kubectl rollout restart deployment -n cb-system
```

| Variable                   | Module(s)                  | Description                                   |
|----------------------------|----------------------------|-----------------------------------------------|
| CB_API_KEYS                | cb-api                     | Comma-separated valid REST API keys           |
| OPENAI_API_KEY             | cb-agent, cb-pr            | GPT-4o (primary LLM, CRITICAL/BLOCKER)        |
| ANTHROPIC_API_KEY          | cb-agent, cb-pr            | Claude Sonnet (review + fallback)             |
| SONARQUBE_URL              | cb-scanner                 | http://cb-sonarqube:9000/sonar                |
| SONARQUBE_TOKEN            | cb-scanner                 | SonarQube user token                          |
| SCANNER_PROJECTS           | cb-scanner                 | Comma-separated SonarQube project keys        |
| GITHUB_TOKEN               | cb-pr, cb-mcp-server       | GitHub PAT with repo scope                    |
| GITHUB_OWNER               | cb-pr, cb-mcp-server       | GitHub org or username                        |
| GITHUB_REPO                | cb-pr, cb-mcp-server       | Repository name                               |
| GITHUB_API_URL             | cb-pr                      | https://api.github.com (or GHE URL)           |
| CB_API_URL                 | cb-mcp-server               | http://cb-api:8080 -- for triggerRemediationRun/getRemediationRunStatus |
| CB_API_KEY                 | cb-mcp-server               | A key from CB_API_KEYS, so cb-mcp-server can call cb-api's own auth-gated endpoints |
| GITHUB_REVIEWER_USERNAME   | cb-pr                      | GitHub user auto-requested as reviewer        |
| VERSION1_API_URL           | cb-pr                      | VersionOne/Version1 instance URL              |
| VERSION1_TOKEN             | cb-pr                      | Version1 API token                            |
| JIRA_ENABLED               | cb-escalation              | true to create Jira tickets on escalation     |
| JIRA_BASE_URL              | cb-escalation              | https://your-org.atlassian.net                |
| JIRA_EMAIL                 | cb-escalation              | Jira account email                            |
| JIRA_API_TOKEN             | cb-escalation              | Jira API token                                |
| JIRA_PROJECT_KEY           | cb-escalation              | Jira project key (default: CB)                |
| TEAMS_ENABLED              | cb-escalation              | true to post Teams adaptive cards             |
| TEAMS_WEBHOOK_URL          | cb-escalation              | Teams incoming webhook URL                    |
| SMTP_HOST / SMTP_PORT      | cb-notifier                | SMTP server host and port                     |
| SMTP_USERNAME / PASSWORD   | cb-notifier                | SMTP credentials                              |
| NOTIFIER_TEAM_EMAILS       | cb-notifier                | Comma-separated email recipients              |
| DATADOG_API_KEY            | cb-notifier                | Set to "none" to disable Datadog events       |
| OPA_ENABLED                | cb-pr                      | false to skip governance check (default: true)|
| KAFKA_BOOTSTRAP_SERVERS    | all consumers/producers    | kafka:9092 (in-cluster default)               |
| MONGODB_URI                | all services               | mongodb://cb-mongodb:27017/compliance_buddy   |

---

## Tech Stack

**Application:**
Java 17 · Spring Boot 3.3 · Spring AI 1.0 · LangGraph4j 1.5 · Spring Kafka 3.2 ·
Spring Security · SpringDoc OpenAPI · Resilience4j 2.2 · JGit 6.8 · Jackson

**AI / ML:**
OpenAI GPT-4o · Anthropic Claude Sonnet 4.6 · Ollama (llama3:8b, qwen2:7b) ·
Spring AI MCP SDK · text-embedding-3-small (OpenAI)

**Storage:**
MongoDB 7 · Redis 7 (Lettuce) · Elasticsearch 8.13 · Qdrant (vector store)

**Infrastructure:**
Docker Desktop · k3d 5.8 · k3s (Kubernetes 1.30) · Kafka KRaft · OPA (Rego) ·
NGINX Ingress

**Observability:**
Prometheus · Grafana · Tempo (OTLP) · Loki · Promtail · Micrometer

**Testing:**
WireMock · Testcontainers · JUnit 5
