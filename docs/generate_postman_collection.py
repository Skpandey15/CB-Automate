"""
Generates docs/CB_v3.postman_collection.json and docs/CB_v3_local.postman_environment.json
"""

import json

# ── helpers ───────────────────────────────────────────────────────────────────

def req(name, method, raw_url, headers=None, body=None, params=None,
        description="", tests=None, pre_req=None, auth_override=None):
    """Build a Postman v2.1 request item."""
    url_parts = raw_url.replace("http://", "").replace("https://", "").split("/")
    # host may include port; keep as single token
    host_raw = url_parts[0]
    path_parts = url_parts[1:] if len(url_parts) > 1 else []

    url_obj = {
        "raw": raw_url,
        "host": [host_raw],
        "path": path_parts,
    }
    if params:
        url_obj["query"] = [
            {"key": k, "value": v, "disabled": d, "description": desc}
            for k, v, d, desc in params
        ]

    item = {
        "name": name,
        "request": {
            "method": method,
            "header": headers or [],
            "url": url_obj,
            "description": description,
        },
        "response": [],
    }
    if body:
        item["request"]["body"] = {
            "mode": "raw",
            "raw": json.dumps(body, indent=2),
            "options": {"raw": {"language": "json"}},
        }
    if auth_override:
        item["request"]["auth"] = auth_override

    events = []
    if pre_req:
        events.append({"listen": "prerequest",
                        "script": {"exec": pre_req, "type": "text/javascript"}})
    if tests:
        events.append({"listen": "test",
                        "script": {"exec": tests, "type": "text/javascript"}})
    if events:
        item["event"] = events
    return item


def folder(name, items, description=""):
    return {"name": name, "description": description, "item": items}


STD_TESTS = [
    "pm.test('Status 200', () => pm.response.to.have.status(200));",
    "pm.test('Response time < 3s', () => pm.expect(pm.response.responseTime).to.be.below(3000));",
    "pm.test('Content-Type is JSON', () => pm.expect(pm.response.headers.get('Content-Type')).to.include('application/json'));",
]

POST_TESTS = [
    "pm.test('Status 2xx', () => pm.response.to.have.status(200).or(pm.response.to.have.status(201)));",
    "pm.test('Response time < 5s', () => pm.expect(pm.response.responseTime).to.be.below(5000));",
]

NO_AUTH = {"type": "noauth"}
APIKEY_AUTH = {
    "type": "apikey",
    "apikey": [
        {"key": "key",   "value": "X-API-Key",   "type": "string"},
        {"key": "value", "value": "{{api_key}}", "type": "string"},
        {"key": "in",    "value": "header",       "type": "string"},
    ],
}

# ── Folders ───────────────────────────────────────────────────────────────────

health_items = [
    req(
        "cb-api — Health (via Ingress, no auth)",
        "GET", "{{base_url}}/actuator/health",
        auth_override=NO_AUTH,
        description="Health endpoint for cb-api. No auth required — used by k8s liveness/readiness probes.\n\n**Expected:** `{ status: 'UP' }`",
        tests=[
            "pm.test('Status 200', () => pm.response.to.have.status(200));",
            "pm.test('Health is UP', () => {",
            "    const json = pm.response.json();",
            "    pm.expect(json.status).to.equal('UP');",
            "});",
        ],
    ),
    req(
        "cb-api — Actuator Info",
        "GET", "{{base_url}}/actuator/info",
        auth_override=NO_AUTH,
        description="Returns application name, version, and build metadata.",
        tests=STD_TESTS,
    ),
    req(
        "cb-scanner — Health (port-forward :8081)",
        "GET", "{{cb_scanner_url}}/actuator/health",
        auth_override=NO_AUTH,
        description="**Requires port-forward:**\n```\nkubectl port-forward svc/cb-scanner 8081:8081 -n cb-system\n```",
        tests=[
            "pm.test('Status 200', () => pm.response.to.have.status(200));",
        ],
    ),
    req(
        "cb-agent — Health (port-forward :8082)",
        "GET", "{{cb_agent_url}}/actuator/health",
        auth_override=NO_AUTH,
        description="**Requires port-forward:**\n```\nkubectl port-forward svc/cb-agent 8082:8082 -n cb-system\n```",
        tests=["pm.test('Status 200', () => pm.response.to.have.status(200));"],
    ),
    req(
        "cb-patcher — Health (port-forward :8083)",
        "GET", "{{cb_patcher_url}}/actuator/health",
        auth_override=NO_AUTH,
        description="**Requires port-forward:**\n```\nkubectl port-forward svc/cb-patcher 8083:8083 -n cb-system\n```",
        tests=["pm.test('Status 200', () => pm.response.to.have.status(200));"],
    ),
    req(
        "cb-pr — Health (port-forward :8084)",
        "GET", "{{cb_pr_url}}/actuator/health",
        auth_override=NO_AUTH,
        description="**Requires port-forward:**\n```\nkubectl port-forward svc/cb-pr 8084:8084 -n cb-system\n```",
        tests=["pm.test('Status 200', () => pm.response.to.have.status(200));"],
    ),
    req(
        "cb-notifier — Health (port-forward :8085)",
        "GET", "{{cb_notifier_url}}/actuator/health",
        auth_override=NO_AUTH,
        description="**Requires port-forward:**\n```\nkubectl port-forward svc/cb-notifier 8085:8085 -n cb-system\n```",
        tests=["pm.test('Status 200', () => pm.response.to.have.status(200));"],
    ),
    req(
        "cb-mcp-server — Health (port-forward :8086)",
        "GET", "{{cb_mcp_url}}/actuator/health",
        auth_override=NO_AUTH,
        description="**Requires port-forward:**\n```\nkubectl port-forward svc/cb-mcp-server 8086:8086 -n cb-system\n```",
        tests=["pm.test('Status 200', () => pm.response.to.have.status(200));"],
    ),
    req(
        "cb-escalation — Health (port-forward :8089)",
        "GET", "{{cb_escalation_url}}/actuator/health",
        auth_override=NO_AUTH,
        description="**Requires port-forward:**\n```\nkubectl port-forward svc/cb-escalation 8089:8089 -n cb-system\n```",
        tests=["pm.test('Status 200', () => pm.response.to.have.status(200));"],
    ),
]

scan_items = [
    req(
        "Trigger Scan — Default Project",
        "POST", "{{base_url}}/api/v1/scans/{{project_key}}",
        description=(
            "Triggers an immediate SonarQube poll for the given project key "
            "(instead of waiting for the 5-minute scheduled poll).\n\n"
            "**No request body required.** The `{projectKey}` path variable must match "
            "a key in SCANNER_PROJECTS env var on cb-scanner.\n\n"
            "**Response:**\n```json\n"
            '{ "newFindings": 3, "status": "SCAN_COMPLETE", "projectKey": "my-app" }\n```'
        ),
        tests=[
            "pm.test('Status 200', () => pm.response.to.have.status(200));",
            "pm.test('Returns newFindings', () => {",
            "    const json = pm.response.json();",
            "    pm.expect(json).to.have.property('newFindings');",
            "    pm.expect(json.newFindings).to.be.a('number');",
            "    console.log('New findings:', json.newFindings);",
            "});",
        ],
    ),
    req(
        "Trigger Scan — payment-service",
        "POST", "{{base_url}}/api/v1/scans/payment-service",
        description="Triggers scan on the `payment-service` SonarQube project. Change the path to any project key.",
        tests=["pm.test('Status 200', () => pm.response.to.have.status(200));"],
    ),
]

vuln_items = [
    req(
        "List All Vulnerabilities",
        "GET", "{{base_url}}/api/v1/vulnerabilities",
        description=(
            "Returns a paginated list of all vulnerabilities across all projects.\n\n"
            "**Response fields per item:** id, projectKey, ruleKey, cweId, severity, "
            "status, component, line, message, createdAt, updatedAt"
        ),
        tests=[
            "pm.test('Status 200', () => pm.response.to.have.status(200));",
            "pm.test('Returns list', () => {",
            "    const json = pm.response.json();",
            "    // Spring Page response",
            "    const items = json.content || json;",
            "    pm.expect(Array.isArray(items)).to.be.true;",
            "    if (items.length > 0) {",
            "        pm.collectionVariables.set('vuln_id', items[0].id);",
            "        console.log('Saved vuln_id:', items[0].id);",
            "    }",
            "});",
        ],
    ),
    req(
        "Filter — Status: DETECTED",
        "GET", "{{base_url}}/api/v1/vulnerabilities",
        params=[
            ("status", "DETECTED", False, "Statuses: DETECTED | IN_PROGRESS | FIX_GENERATED | FIX_VALIDATED | PR_RAISED | RESOLVED | ESCALATED | FAILED"),
        ],
        description="Returns only vulnerabilities that have just been detected and are queued for AI fix generation.",
        tests=STD_TESTS,
    ),
    req(
        "Filter — Status: PR_RAISED",
        "GET", "{{base_url}}/api/v1/vulnerabilities",
        params=[("status", "PR_RAISED", False, "Awaiting PR review")],
        description="Vulnerabilities where a GitHub PR has been created and is awaiting human review.",
        tests=STD_TESTS,
    ),
    req(
        "Filter — Status: ESCALATED",
        "GET", "{{base_url}}/api/v1/vulnerabilities",
        params=[("status", "ESCALATED", False, "AI could not auto-fix — Jira + Teams escalated")],
        description="Vulnerabilities that exhausted all retries and were escalated to Jira + Teams.",
        tests=STD_TESTS,
    ),
    req(
        "Filter — Severity: CRITICAL",
        "GET", "{{base_url}}/api/v1/vulnerabilities",
        params=[("severity", "CRITICAL", False, "Severities: BLOCKER | CRITICAL | MAJOR | MINOR | INFO")],
        description="Returns only CRITICAL severity vulnerabilities (routed to GPT-4o for fix generation).",
        tests=STD_TESTS,
    ),
    req(
        "Filter — Severity: BLOCKER + Status: DETECTED",
        "GET", "{{base_url}}/api/v1/vulnerabilities",
        params=[
            ("severity", "BLOCKER", False, "BLOCKER routes to GPT-4o"),
            ("status",   "DETECTED", False, "Not yet picked up by cb-agent"),
        ],
        description="Blockers that haven't been processed yet — highest urgency queue.",
        tests=STD_TESTS,
    ),
    req(
        "Filter — By Project Key",
        "GET", "{{base_url}}/api/v1/vulnerabilities",
        params=[("projectKey", "{{project_key}}", False, "SonarQube project key")],
        description="All vulnerabilities for a specific project.",
        tests=STD_TESTS,
    ),
    req(
        "Filter — Severity: MAJOR (Ollama-routed)",
        "GET", "{{base_url}}/api/v1/vulnerabilities",
        params=[("severity", "MAJOR", False, "Routes to qwen2:7b local Ollama model")],
        description="MAJOR severity vulnerabilities — routed to qwen2:7b (Ollama) in v3.",
        tests=STD_TESTS,
    ),
    req(
        "Get Single Vulnerability",
        "GET", "{{base_url}}/api/v1/vulnerabilities/{{vuln_id}}",
        description=(
            "Returns full detail for one vulnerability. "
            "**Set `vuln_id`** collection variable first (auto-saved by 'List All Vulnerabilities' test script).\n\n"
            "**Response fields:** id, projectKey, ruleKey, cweId, severity, status, "
            "component, line, message, createdAt, updatedAt"
        ),
        tests=[
            "pm.test('Status 200', () => pm.response.to.have.status(200));",
            "pm.test('Has required fields', () => {",
            "    const v = pm.response.json();",
            "    pm.expect(v).to.have.property('id');",
            "    pm.expect(v).to.have.property('severity');",
            "    pm.expect(v).to.have.property('status');",
            "    pm.expect(v).to.have.property('cweId');",
            "    console.log('Vuln status:', v.status, '| Severity:', v.severity, '| CWE:', v.cweId);",
            "});",
        ],
    ),
    req(
        "Retry AI Fix (re-queue to Kafka)",
        "POST", "{{base_url}}/api/v1/vulnerabilities/retry/{{vuln_id}}",
        description=(
            "Re-queues a vulnerability for AI fix generation by publishing a new "
            "`VulnerabilityKafkaEvent` to the `vulnerabilities.detected` topic. "
            "Useful for vulnerabilities stuck in FAILED state or after model changes.\n\n"
            "**No request body required.**\n\n"
            "**Response:** `{ \"queued\": true, \"vulnerabilityId\": \"...\" }`"
        ),
        tests=[
            "pm.test('Status 200', () => pm.response.to.have.status(200));",
            "pm.test('Queued is true', () => {",
            "    const json = pm.response.json();",
            "    pm.expect(json.queued).to.be.true;",
            "});",
        ],
    ),
]

fix_items = [
    req(
        "List All Fixes",
        "GET", "{{base_url}}/api/v1/fixes",
        description=(
            "Returns all AI-generated fix records. Each fix contains the unified diff "
            "patch, the model used, confidence score, token count, and build log.\n\n"
            "**Response fields per item:** id, vulnerabilityId, diff (unified patch text), "
            "model, confidence (0.0–1.0), tokensUsed, cacheHit, status, buildLog, branch, "
            "patchedBranch, createdAt"
        ),
        tests=[
            "pm.test('Status 200', () => pm.response.to.have.status(200));",
            "pm.test('Returns list', () => {",
            "    const json = pm.response.json();",
            "    const items = json.content || json;",
            "    pm.expect(Array.isArray(items)).to.be.true;",
            "    if (items.length > 0) {",
            "        pm.collectionVariables.set('fix_id', items[0].id);",
            "        pm.collectionVariables.set('entity_id', items[0].id);",
            "        console.log('Saved fix_id:', items[0].id, '| model:', items[0].model);",
            "    }",
            "});",
        ],
    ),
    req(
        "Get Single Fix (with Diff)",
        "GET", "{{base_url}}/api/v1/fixes/{{fix_id}}",
        description=(
            "Returns a single fix including the full unified diff patch.\n\n"
            "**Set `fix_id`** variable first (auto-saved by 'List All Fixes' test script).\n\n"
            "**Key fields to inspect:**\n"
            "- `diff` — the unified patch applied to the source file\n"
            "- `confidence` — LLM confidence score (0–1). Values < 0.70 are blocked by OPA.\n"
            "- `model` — which LLM generated this fix\n"
            "- `cacheHit` — true if served from Redis prompt cache\n"
            "- `buildLog` — stdout from `./gradlew build test`"
        ),
        tests=[
            "pm.test('Status 200', () => pm.response.to.have.status(200));",
            "pm.test('Has diff field', () => {",
            "    const f = pm.response.json();",
            "    pm.expect(f).to.have.property('diff');",
            "    pm.expect(f).to.have.property('confidence');",
            "    pm.expect(f).to.have.property('model');",
            "    console.log('Model:', f.model, '| Confidence:', f.confidence, '| Cache hit:', f.cacheHit);",
            "});",
        ],
    ),
]

metrics_items = [
    req(
        "Dashboard Metrics (KPIs)",
        "GET", "{{base_url}}/api/v1/metrics",
        description=(
            "Returns aggregated pipeline KPIs for dashboards.\n\n"
            "**Response example:**\n"
            "```json\n"
            "{\n"
            '  "vulnerabilities": {\n'
            '    "total": 42, "detected": 5, "inProgress": 3,\n'
            '    "fixGenerated": 2, "prRaised": 8, "resolved": 20,\n'
            '    "escalated": 3, "failed": 1\n'
            "  },\n"
            '  "fixes": {\n'
            '    "total": 35, "byModel": { "gpt-4o": 20, "qwen2:7b": 10, "llama3:8b": 5 },\n'
            '    "avgConfidence": 0.912\n'
            "  },\n"
            '  "remediationRate": 0.714\n'
            "}\n```"
        ),
        tests=[
            "pm.test('Status 200', () => pm.response.to.have.status(200));",
            "pm.test('Has vulnerability counts', () => {",
            "    const m = pm.response.json();",
            "    pm.expect(m).to.have.property('vulnerabilities');",
            "    pm.expect(m).to.have.property('fixes');",
            "    pm.expect(m).to.have.property('remediationRate');",
            "    console.log('Remediation rate:', m.remediationRate);",
            "    console.log('Total resolved:', m.vulnerabilities?.resolved);",
            "});",
        ],
    ),
]

cost_items = [
    req(
        "Cost Summary — Last 7 Days",
        "GET", "{{base_url}}/api/v1/cost/summary",
        params=[("days", "7", False, "Number of days to aggregate (default: 7)")],
        description=(
            "Aggregated LLM spend for the last N days.\n\n"
            "**Response example:**\n"
            "```json\n"
            "{\n"
            '  "totalCostUsd": 1.42,\n'
            '  "totalTokens": 284000,\n'
            '  "days": 7,\n'
            '  "byModel": [\n'
            '    { "model": "gpt-4o", "costUsd": 1.38, "tokens": 276000 },\n'
            '    { "model": "qwen2:7b", "costUsd": 0.00, "tokens": 8000 }\n'
            "  ]\n"
            "}\n```"
        ),
        tests=[
            "pm.test('Status 200', () => pm.response.to.have.status(200));",
            "pm.test('Has cost fields', () => {",
            "    const c = pm.response.json();",
            "    pm.expect(c).to.have.property('totalCostUsd');",
            "    pm.expect(c).to.have.property('totalTokens');",
            "    console.log('Total cost (7d): $', c.totalCostUsd);",
            "    console.log('Total tokens:   ', c.totalTokens);",
            "});",
        ],
    ),
    req(
        "Cost Summary — Last 30 Days",
        "GET", "{{base_url}}/api/v1/cost/summary",
        params=[("days", "30", False, "30-day aggregation")],
        description="Monthly LLM cost breakdown across all models.",
        tests=STD_TESTS,
    ),
    req(
        "Cost by CWE (Last 30 Days)",
        "GET", "{{base_url}}/api/v1/cost/by-cwe",
        params=[("days", "30", False, "")],
        description=(
            "Shows which CWE types cost the most to remediate — useful for identifying "
            "CWEs where fine-tuned local models would save the most money.\n\n"
            "**Response:** `[{ cweId, totalCostUsd, totalTokens, fixCount }]` sorted by cost desc"
        ),
        tests=[
            "pm.test('Status 200', () => pm.response.to.have.status(200));",
            "pm.test('Returns array', () => {",
            "    const data = pm.response.json();",
            "    pm.expect(Array.isArray(data)).to.be.true;",
            "    if (data.length > 0) {",
            "        console.log('Most expensive CWE:', data[0].cweId, '| Cost: $', data[0].totalCostUsd);",
            "    }",
            "});",
        ],
    ),
    req(
        "Cost by Model (Last 30 Days)",
        "GET", "{{base_url}}/api/v1/cost/by-model",
        params=[("days", "30", False, "")],
        description=(
            "Shows spend per LLM model. Use this to confirm Ollama local models are "
            "actually being used for MAJOR/MINOR/INFO vulnerabilities (cost = $0.00).\n\n"
            "**Response:** `[{ model, totalCostUsd, totalInputTokens, totalOutputTokens, fixCount }]`"
        ),
        tests=[
            "pm.test('Status 200', () => pm.response.to.have.status(200));",
            "pm.test('Returns array', () => {",
            "    const data = pm.response.json();",
            "    pm.expect(Array.isArray(data)).to.be.true;",
            "    data.forEach(m => console.log('Model:', m.model, '| Cost: $', m.totalCostUsd));",
            "});",
        ],
    ),
]

audit_items = [
    req(
        "Full Audit Trail",
        "GET", "{{base_url}}/api/v1/audit",
        description=(
            "Returns all audit events across all modules. Includes vulnerability status "
            "transitions, fix generation, PR creation, OPA decisions, and escalations.\n\n"
            "**Response fields per event:** id, entityId, entityType, action, actor, details (JSON), timestamp"
        ),
        tests=[
            "pm.test('Status 200', () => pm.response.to.have.status(200));",
            "pm.test('Returns audit events', () => {",
            "    const items = pm.response.json();",
            "    pm.expect(Array.isArray(items)).to.be.true;",
            "    console.log('Total audit events:', items.length);",
            "    if (items.length > 0) {",
            "        console.log('Latest action:', items[0].action, '| Actor:', items[0].actor);",
            "    }",
            "});",
        ],
    ),
    req(
        "Audit by Entity (Vulnerability / Fix)",
        "GET", "{{base_url}}/api/v1/audit/entity/{{entity_id}}",
        description=(
            "Returns all audit events for a specific entity (vulnerability ID, fix ID, etc.).\n\n"
            "**Set `entity_id`** variable to any vulnerability or fix ID. "
            "Auto-saved by 'List All Fixes' test script.\n\n"
            "Useful for tracing the full lifecycle of one vulnerability from DETECTED → RESOLVED."
        ),
        tests=[
            "pm.test('Status 200', () => pm.response.to.have.status(200));",
            "pm.test('Returns events', () => {",
            "    const items = pm.response.json();",
            "    pm.expect(Array.isArray(items)).to.be.true;",
            "    items.forEach(e => console.log(e.timestamp, '|', e.action));",
            "});",
        ],
    ),
]

actuator_items = [
    req(
        "Prometheus Metrics Scrape",
        "GET", "{{base_url}}/actuator/prometheus",
        description=(
            "Returns all Prometheus metrics in text/plain format. "
            "Prometheus scrapes this endpoint every 15 seconds.\n\n"
            "**Key CB metrics to look for:**\n"
            "- `cb_vulnerabilities_detected_total`\n"
            "- `cb_fixes_generated_total`\n"
            "- `cb_prs_created_total`\n"
            "- `cb_escalations_triggered_total`\n"
            "- `cb_llm_cost_usd_total`\n"
            "- `cb_llm_tokens_total`\n"
            "- `cb_fix_confidence`\n"
            "- `cb_pipeline_duration_seconds`\n"
            "- `cb_redis_cache_hit_total`"
        ),
        tests=[
            "pm.test('Status 200', () => pm.response.to.have.status(200));",
            "pm.test('Contains CB metrics', () => {",
            "    const body = pm.response.text();",
            "    pm.expect(body).to.include('cb_vulnerabilities');",
            "});",
        ],
    ),
    req(
        "Spring Boot Env (actuator/env)",
        "GET", "{{base_url}}/actuator/env",
        description=(
            "Returns active Spring profiles and environment properties "
            "(sanitized — credentials are masked as *****).\n\n"
            "**Note:** Only accessible if `management.endpoints.web.exposure.include=env` "
            "is set in application.yml."
        ),
        tests=["pm.test('Status is 200 or 404', () => pm.expect([200,404]).to.include(pm.response.code));"],
    ),
    req(
        "Spring Boot Beans (actuator/beans)",
        "GET", "{{base_url}}/actuator/beans",
        description="Lists all Spring beans loaded in the application context. Useful for verifying Kafka listeners, ChatClient beans, and LangGraph4j workflow beans are registered.",
        tests=["pm.test('Status is 200 or 404', () => pm.expect([200,404]).to.include(pm.response.code));"],
    ),
]

mcp_items = [
    req(
        "MCP SSE Endpoint (cb-mcp-server)",
        "GET", "{{cb_mcp_url}}/sse",
        auth_override=NO_AUTH,
        description=(
            "Server-Sent Events (SSE) endpoint for the Model Context Protocol server.\n\n"
            "**Used by:** Claude Desktop, Cursor, and other MCP-compatible AI assistants.\n\n"
            "**Not a REST API** — this is a streaming SSE connection. "
            "Opening this in Postman shows the raw SSE stream. "
            "Use Claude Desktop or Cursor to invoke the 12 MCP tools.\n\n"
            "**12 Available MCP Tools:**\n"
            "1. `findPreviousFixes` — search past fixes by CWE\n"
            "2. `getSonarIssue` — fetch SonarQube issue detail\n"
            "3. `getPRDiff` — get GitHub PR diff\n"
            "4. `buildProject` — trigger Gradle/Maven build\n"
            "5. `createPR` — create a GitHub PR\n"
            "6. `getBuildLog` — fetch build log for a fix\n"
            "7. `searchPastIncidents` — semantic search over escalations\n"
            "8. `queryQdrant` — direct Qdrant vector search\n"
            "9. `regenerateFix` — force re-run LangGraph4j for a vulnerability\n"
            "10. `getTrace` — fetch Tempo trace by traceId\n"
            "11. `getMetrics` — fetch Prometheus metric values\n"
            "12. `triggerRollback` — rollback a merged CB PR\n\n"
            "**Port-forward:**\n"
            "```\nkubectl port-forward svc/cb-mcp-server 8086:8086 -n cb-system\n```\n"
            "Then set Claude Desktop MCP config to: `http://localhost:8086/sse`"
        ),
        tests=["pm.test('Status is 200 or 406', () => pm.expect([200,406,400]).to.include(pm.response.code));"],
    ),
    req(
        "MCP Server Health",
        "GET", "{{cb_mcp_url}}/actuator/health",
        auth_override=NO_AUTH,
        description="Health check for cb-mcp-server (requires port-forward to :8086).",
        tests=["pm.test('Status 200', () => pm.response.to.have.status(200));"],
    ),
]

# ── End-to-End workflow folder ────────────────────────────────────────────────
e2e_items = [
    req(
        "Step 1 — Trigger Scan",
        "POST", "{{base_url}}/api/v1/scans/{{project_key}}",
        description="E2E Step 1: Trigger SonarQube scan. Note the `newFindings` count.",
        tests=[
            "pm.test('Status 200', () => pm.response.to.have.status(200));",
            "const json = pm.response.json();",
            "pm.collectionVariables.set('last_scan_findings', json.newFindings || 0);",
            "console.log('E2E Step 1 complete. New findings:', json.newFindings);",
        ],
    ),
    req(
        "Step 2 — List DETECTED Vulnerabilities",
        "GET", "{{base_url}}/api/v1/vulnerabilities",
        params=[("status", "DETECTED", False, "Freshly detected, queued for AI")],
        description=(
            "E2E Step 2: Wait ~10–30s for cb-agent to pick up the scan results, "
            "then check DETECTED vulns.\n\n"
            "Test script saves the first vulnerability ID for subsequent steps."
        ),
        tests=[
            "pm.test('Status 200', () => pm.response.to.have.status(200));",
            "const items = pm.response.json().content || pm.response.json();",
            "if (items.length > 0) {",
            "    pm.collectionVariables.set('vuln_id', items[0].id);",
            "    console.log('E2E Step 2: Saved vuln_id:', items[0].id);",
            "    console.log('Severity:', items[0].severity, '| CWE:', items[0].cweId);",
            "} else {",
            "    console.log('No DETECTED vulns yet — wait and re-run');",
            "}",
        ],
    ),
    req(
        "Step 3 — Check Fix Generated",
        "GET", "{{base_url}}/api/v1/vulnerabilities/{{vuln_id}}",
        description=(
            "E2E Step 3: Wait ~30–120s for cb-agent LangGraph4j to complete, "
            "then check vulnerability status.\n\n"
            "Expected: status changes from IN_PROGRESS → FIX_GENERATED"
        ),
        tests=[
            "pm.test('Status 200', () => pm.response.to.have.status(200));",
            "const v = pm.response.json();",
            "console.log('E2E Step 3: Vuln status:', v.status);",
            "if (v.status === 'FIX_GENERATED' || v.status === 'FIX_VALIDATED' || v.status === 'PR_RAISED') {",
            "    console.log('Fix is progressing! Current status:', v.status);",
            "} else {",
            "    console.log('Status still:', v.status, '— LangGraph4j may still be running');",
            "}",
        ],
    ),
    req(
        "Step 4 — Check PR Raised",
        "GET", "{{base_url}}/api/v1/vulnerabilities",
        params=[("status", "PR_RAISED", False, "GitHub PR created by cb-pr")],
        description=(
            "E2E Step 4: Wait ~2–5 min for cb-patcher build+test and cb-pr OPA check.\n\n"
            "Expected: vulnerability status reaches PR_RAISED and a GitHub PR is visible "
            "in the repository."
        ),
        tests=[
            "pm.test('Status 200', () => pm.response.to.have.status(200));",
            "const items = pm.response.json().content || pm.response.json();",
            "console.log('E2E Step 4: PRs raised:', items.length);",
            "items.forEach(v => console.log(' -', v.id, '| CWE:', v.cweId));",
        ],
    ),
    req(
        "Step 5 — Check Cost",
        "GET", "{{base_url}}/api/v1/cost/summary",
        params=[("days", "1", False, "Last 24 hours")],
        description=(
            "E2E Step 5: Verify cost tracking is working. Should show non-zero spend "
            "if GPT-4o was used (CRITICAL/BLOCKER), or $0 if Ollama handled it (MAJOR/MINOR/INFO)."
        ),
        tests=[
            "pm.test('Status 200', () => pm.response.to.have.status(200));",
            "const c = pm.response.json();",
            "console.log('E2E Step 5 — Cost (24h): $', c.totalCostUsd, '| Tokens:', c.totalTokens);",
        ],
    ),
    req(
        "Step 6 — Check Escalations",
        "GET", "{{base_url}}/api/v1/vulnerabilities",
        params=[("status", "ESCALATED", False, "Failed after 3 retries → Jira + Teams")],
        description=(
            "E2E Step 6 (optional): Check if any vulnerabilities were escalated. "
            "Expected for CWEs with very complex fixes (confidence < 0.70 after 3 retries)."
        ),
        tests=[
            "pm.test('Status 200', () => pm.response.to.have.status(200));",
            "const items = pm.response.json().content || pm.response.json();",
            "console.log('Escalated vulnerabilities:', items.length);",
        ],
    ),
]

# ── Assemble collection ───────────────────────────────────────────────────────
collection = {
    "info": {
        "name": "Compliance Buddy v3",
        "_postman_id": "cb-v3-postman-collection",
        "description": (
            "# Compliance Buddy v3 — Full API Collection\n\n"
            "Autonomous AI-powered security remediation platform.\n\n"
            "## Setup\n"
            "1. Import this collection into Postman\n"
            "2. Import `CB_v3_local.postman_environment.json` as an environment\n"
            "3. Select the **CB Local (k3d)** environment\n"
            "4. Ensure `compliance-buddy.local` resolves (`127.0.0.1 compliance-buddy.local` in hosts file)\n"
            "5. Start the cluster: run `start-cb.ps1`\n\n"
            "## Auth\n"
            "All requests use **X-API-Key** header (set at collection level). "
            "Default value: `dev-key-change-in-prod`.\n\n"
            "## Internal Services (port-forward required)\n"
            "Services other than cb-api are not exposed via Ingress. "
            "Use kubectl port-forward to access their health/actuator endpoints:\n"
            "```\n"
            "kubectl port-forward svc/<service> <local>:<remote> -n cb-system\n"
            "```\n\n"
            "## Swagger UI\n"
            "http://compliance-buddy.local/swagger-ui/index.html\n\n"
            "## Grafana / Prometheus\n"
            "- Grafana: http://localhost:3000 (admin/admin)\n"
            "- Prometheus: http://localhost:9090"
        ),
        "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json",
    },
    "auth": APIKEY_AUTH,
    "variable": [
        {"key": "base_url",          "value": "http://compliance-buddy.local",      "type": "string",
         "description": "Ingress URL (cb-api). Add to hosts: 127.0.0.1 compliance-buddy.local"},
        {"key": "api_key",           "value": "dev-key-change-in-prod",             "type": "string",
         "description": "X-API-Key header value. Change in CB_API_KEYS env var."},
        {"key": "project_key",       "value": "my-app",                             "type": "string",
         "description": "Default SonarQube project key to scan"},
        {"key": "vuln_id",           "value": "",                                   "type": "string",
         "description": "Auto-saved by 'List All Vulnerabilities' test script"},
        {"key": "fix_id",            "value": "",                                   "type": "string",
         "description": "Auto-saved by 'List All Fixes' test script"},
        {"key": "entity_id",         "value": "",                                   "type": "string",
         "description": "Auto-saved by 'List All Fixes' test script for audit lookup"},
        {"key": "cb_scanner_url",    "value": "http://localhost:8081",              "type": "string",
         "description": "kubectl port-forward svc/cb-scanner 8081:8081 -n cb-system"},
        {"key": "cb_agent_url",      "value": "http://localhost:8082",              "type": "string",
         "description": "kubectl port-forward svc/cb-agent 8082:8082 -n cb-system"},
        {"key": "cb_patcher_url",    "value": "http://localhost:8083",              "type": "string",
         "description": "kubectl port-forward svc/cb-patcher 8083:8083 -n cb-system"},
        {"key": "cb_pr_url",         "value": "http://localhost:8084",              "type": "string",
         "description": "kubectl port-forward svc/cb-pr 8084:8084 -n cb-system"},
        {"key": "cb_notifier_url",   "value": "http://localhost:8085",              "type": "string",
         "description": "kubectl port-forward svc/cb-notifier 8085:8085 -n cb-system"},
        {"key": "cb_mcp_url",        "value": "http://localhost:8086",              "type": "string",
         "description": "kubectl port-forward svc/cb-mcp-server 8086:8086 -n cb-system"},
        {"key": "cb_escalation_url", "value": "http://localhost:8089",              "type": "string",
         "description": "kubectl port-forward svc/cb-escalation 8089:8089 -n cb-system"},
    ],
    "item": [
        folder(
            "🏥 Health Checks",
            health_items,
            "Health endpoints for all 8 CB modules. cb-api is via Ingress. "
            "All others require kubectl port-forward."
        ),
        folder(
            "🔍 Scanner — Trigger Scans",
            scan_items,
            "Trigger on-demand SonarQube scans (cb-scanner). "
            "Publishes VulnerabilityKafkaEvent to vulnerabilities.detected topic."
        ),
        folder(
            "🔐 Vulnerabilities",
            vuln_items,
            "CRUD and filtering for vulnerability records. "
            "Covers all 8 status values and all 5 severity levels."
        ),
        folder(
            "🔧 Fixes",
            fix_items,
            "AI-generated fix records including unified diff patches, "
            "model used, confidence scores, and build logs."
        ),
        folder(
            "📊 Metrics & Dashboard",
            metrics_items,
            "Pipeline KPIs: vulnerability counts by status, fix counts by model, "
            "remediation rate."
        ),
        folder(
            "💰 Cost Tracking",
            cost_items,
            "LLM API spend tracking. Breaks down cost by model and by CWE type. "
            "Useful for verifying Ollama local inference is reducing GPT-4o spend."
        ),
        folder(
            "📋 Audit Trail",
            audit_items,
            "Full audit trail of all pipeline actions: status transitions, "
            "OPA decisions, PR creation, escalations."
        ),
        folder(
            "⚙️ Actuator & Monitoring",
            actuator_items,
            "Spring Boot Actuator endpoints: Prometheus metrics scrape, "
            "application info, beans, environment."
        ),
        folder(
            "🤖 MCP Server (AI Tools)",
            mcp_items,
            "Model Context Protocol SSE endpoint for AI assistants. "
            "Connect Claude Desktop or Cursor to interact with 12 CB tools via natural language."
        ),
        folder(
            "🧪 End-to-End Workflow",
            e2e_items,
            "Run these 6 steps in sequence to test the full pipeline: "
            "Trigger scan → detect vulns → AI fix → build validate → PR raised → cost check."
        ),
    ],
}

# ── Environment file ──────────────────────────────────────────────────────────
environment = {
    "id": "cb-v3-local-env",
    "name": "CB Local (k3d)",
    "values": [
        {"key": "base_url",          "value": "http://compliance-buddy.local", "enabled": True,
         "description": "Ingress → cb-api. Requires: 127.0.0.1 compliance-buddy.local in hosts file"},
        {"key": "api_key",           "value": "dev-key-change-in-prod",       "enabled": True,
         "description": "X-API-Key header. Change via CB_API_KEYS env var in k8s secret."},
        {"key": "project_key",       "value": "my-app",                       "enabled": True,
         "description": "Default SonarQube project key to scan"},
        {"key": "vuln_id",           "value": "",                             "enabled": True,
         "description": "Auto-populated by test scripts"},
        {"key": "fix_id",            "value": "",                             "enabled": True,
         "description": "Auto-populated by test scripts"},
        {"key": "entity_id",         "value": "",                             "enabled": True,
         "description": "Auto-populated by test scripts"},
        {"key": "cb_scanner_url",    "value": "http://localhost:8081",        "enabled": True,
         "description": "Port-forward: kubectl port-forward svc/cb-scanner 8081:8081 -n cb-system"},
        {"key": "cb_agent_url",      "value": "http://localhost:8082",        "enabled": True,
         "description": "Port-forward: kubectl port-forward svc/cb-agent 8082:8082 -n cb-system"},
        {"key": "cb_patcher_url",    "value": "http://localhost:8083",        "enabled": True,
         "description": "Port-forward: kubectl port-forward svc/cb-patcher 8083:8083 -n cb-system"},
        {"key": "cb_pr_url",         "value": "http://localhost:8084",        "enabled": True,
         "description": "Port-forward: kubectl port-forward svc/cb-pr 8084:8084 -n cb-system"},
        {"key": "cb_notifier_url",   "value": "http://localhost:8085",        "enabled": True,
         "description": "Port-forward: kubectl port-forward svc/cb-notifier 8085:8085 -n cb-system"},
        {"key": "cb_mcp_url",        "value": "http://localhost:8086",        "enabled": True,
         "description": "Port-forward: kubectl port-forward svc/cb-mcp-server 8086:8086 -n cb-system"},
        {"key": "cb_escalation_url", "value": "http://localhost:8089",        "enabled": True,
         "description": "Port-forward: kubectl port-forward svc/cb-escalation 8089:8089 -n cb-system"},
    ],
    "_postman_variable_scope": "environment",
}

# ── Write files ───────────────────────────────────────────────────────────────
import os

base = "E:/CB_Automate/docs"
col_path = f"{base}/CB_v3.postman_collection.json"
env_path = f"{base}/CB_v3_local.postman_environment.json"

with open(col_path, "w", encoding="utf-8") as f:
    json.dump(collection, f, indent=2, ensure_ascii=False)

with open(env_path, "w", encoding="utf-8") as f:
    json.dump(environment, f, indent=2, ensure_ascii=False)

col_size = os.path.getsize(col_path)
env_size = os.path.getsize(env_path)

# Count total requests
total = sum(len(folder["item"]) for folder in collection["item"])
print(f"Collection : {col_path}")
print(f"           : {col_size/1024:.1f} KB — {len(collection['item'])} folders, {total} requests")
print(f"Environment: {env_path}")
print(f"           : {env_size/1024:.1f} KB — {len(environment['values'])} variables")
