# ADR-0002: Autonomous Remediation Control Plane

Date: 2026-09-13

Status: Accepted

Acceptance basis: repository owner (skpandey15) reviewed and merged this
decision via PR #6 into main (merge commit 3e1507378c2634334556d22256ae954eab7b968c,
2026-09-13), then explicitly confirmed the merge in conversation with the
requesting session before any implementation branch was created.

Supersedes: none

## Context

CB today has two remediation entry points, and neither matches the stated
product goal: **a user supplies a GitHub repository URL and branch, and
receives PR(s) covering dependency CVEs, code-level security issues, and
general code-quality/coding-standard issues that block production-grade
code — without pre-provisioning anything.**

What exists instead:

1. **SonarQube-driven pipeline** (`cb-scanner` → Kafka `vulnerabilities.detected`
   → `cb-agent` → `cb-patcher` → `cb-pr`). `SonarQubeClient.java:37` queries
   `types=VULNERABILITY,BUG,CODE_SMELL` — broader than security alone, it does
   cover coding-standard/maintainability issues — but only for `languages=java`,
   and only after a human has manually created the project in SonarQube and run
   `sonar-scanner` against a checked-out copy of the source (README "Testing
   Guide, Step 1"). There is no repo-URL-only trigger for this path today.

2. **Legacy Java dependency scanner**, also feeding the same Kafka pipeline
   (`GradleDependencyParser` → `OssIndexClient`/`DepsDevClient` →
   `MavenCentralClient`, `Vulnerability.type=DEPENDENCY`, CWE-1104).

3. **Standalone Python branch-remediation job** (ADR-0001), the supported entry
   point for `{repository, branch} -> verified dependency fixes -> PR -> email`.
   ADR-0001 explicitly states: *"The Java SonarQube/AI pipeline is retained; it
   is not used by this job... Do not run both against the same repository for
   dependency remediation."* — i.e. track 2 and track 3 must not run
   concurrently against the same repo.

No existing component clones an arbitrary repo, analyzes it, and remediates
both dependency CVEs and static-analysis findings from a single request.
Closing that gap is the subject of this decision.

## Decision

Add a new control-plane entry point — a deterministic Java orchestrator,
consistent with ADR-0007's trust model (**AI reasons. Java controls. Policies
authorize. Sandboxes execute.**) — that accepts `{repoUrl, branch}` and runs
two independent, already-governed tracks per request:

**Track A — Dependency CVEs.** Invoke the existing ADR-0001 job (or its
underlying logic) for the given repo/branch. This remains the sole path for
dependency-CVE remediation. The legacy Java dependency scanner (track 2 above)
is **not** invoked by the control plane for a given run — this preserves
ADR-0001's "do not run both against the same repository" rule rather than
violating it by routing dependency fixes through two mechanisms.

**Track B — Code-level issues.** The control plane performs the SonarQube
analysis itself instead of requiring the user to pre-register a project:

1. Shallow-clone the requested branch into an isolated, non-mutated workspace
   and record its commit SHA (same discipline as ADR-0001 step 1).
2. Auto-provision a throwaway SonarQube project keyed by `{repo, runId}` via
   the SonarQube API, run `sonar-scanner` against the clone, and poll for
   analysis completion.
3. Feed the result through the existing, unchanged `SonarQubeClient` →
   `ScannerService` → Kafka → `cb-agent` → `cb-patcher` → `cb-pr` pipeline.
4. Delete the ephemeral SonarQube project once the run's findings have been
   ingested, so ephemeral runs do not accumulate permanent projects.

The two tracks converge only at reporting: the control plane returns one run
report referencing both outcomes (dependency PR and/or code-fix PR, or
no-findings). It does not merge the two into a single diff — a deterministic,
build-tested dependency bump and an AI-generated code patch carry different
verification guarantees and should stay independently reviewable and
independently revertible.

All existing safety semantics are unchanged by this decision: build
validation before publication, the OPA governance gate on every PR-creation
path (including this new one), and no direct LLM-to-GitHub authorization
path. This control plane is additive — no existing service, contract, or
manual entry point is removed or altered.

Per the product owner's stated priority ("functionality is first priority,
[infrastructure simplification] is secondary"), this decision keeps the
current Kafka/microservice/datastore footprint rather than trading
capability for a smaller footprint. Footprint reduction, if pursued later, is
a separate decision (see ADR-0011, reserved).

## Alternatives considered

- **Keep requiring manual SonarQube pre-registration.** Rejected — does not
  meet the "just a URL" goal that motivated this decision.
- **Route dependency CVEs through the legacy Java CWE-1104 scanner instead of
  the ADR-0001 job.** Rejected — violates ADR-0001's explicit prohibition on
  running both dependency mechanisms against the same repository, and forfeits
  ADR-0001's build-validated, non-guessed-version guarantees.
- **Consolidate the ten existing microservices into a single job-runner
  process for this control plane.** Considered and not pursued in this
  decision at the product owner's direction (functionality prioritized over
  footprint reduction this round); may be revisited under a future
  Service Boundaries decision (ADR-0011, reserved) without blocking this one.
- **Merge dependency and code fixes into one combined PR.** Rejected — mixes
  two different verification regimes into a single diff, complicating review
  and rollback; two independent PRs preserve clean audit/rollback boundaries
  consistent with ADR-0001.

## Consequences and risk

- **New attack surface / credential**: auto-provisioning ephemeral SonarQube
  projects requires a SonarQube admin/service token available to the control
  plane. Scoping that token and guaranteeing cleanup of ephemeral projects
  needs explicit implementation attention; an interrupted run must not leak a
  permanent project.
- **Wider blast radius on an existing gap**: prior review of this codebase
  found near-zero automated test coverage on `cb-patcher`, `cb-pr`, and
  `cb-agent` (7 of 9 Java modules have no `src/test` directory at all). This
  decision makes those modules reachable from a much larger "any public repo"
  surface than today's manually-curated SonarQube projects. Closing that test
  gap is not in this ADR's scope, but is a named prerequisite risk — this
  control plane should not be exposed broadly until it is addressed.
- **Operational cost**: an ephemeral `sonar-scanner` run per request is
  materially heavier (clone + analyze + poll) than polling an
  already-registered project every 5 minutes. Needs a per-run timeout and
  resource ceiling to bound cost/abuse from large or malicious repos.
- **No infra reduction**: Kafka, MongoDB, Redis, Elasticsearch, Qdrant, OPA,
  and Ollama all remain required dependencies; this decision does not reduce
  that footprint.
- **Still Java-only** for Track B — the `languages=java` restriction in
  `SonarQubeClient` is not addressed here.

## Migration and rollback

Purely additive. The existing manual SonarQube-registration flow and the
existing standalone dependency-remediation script remain usable unchanged;
no existing contract, topic, or schema is modified. Rollback is disabling or
removing the new control-plane entry point — no data migration is required
since it reuses the existing `Vulnerability`/`Fix` domain models and Kafka
topics for Track B, and the existing ADR-0001 job unchanged for Track A.

## Fitness functions

- A run against a public repo with at least one known dependency CVE and one
  known SonarQube-detectable finding (e.g. a deliberate CWE-89 sample)
  produces both a dependency-fix PR and a code-fix PR (or a report correctly
  stating no findings for a track that has none).
- Running the control plane twice against an unchanged branch does not create
  duplicate PRs (idempotency, mirroring ADR-0001's "reuse an existing PR for
  identical results").
- After a run completes (success or failure), no ephemeral SonarQube project
  from that run remains registered on the SonarQube server.
- A run that fails mid-analysis (e.g. SonarQube unreachable) does not
  publish partial/unvalidated findings and is reported as a failed run, not a
  clean no-findings result — consistent with ADR-0001's scan-failure
  semantics.

## Related decisions and references

- Builds on [ADR-0001](0001-branch-dependency-remediation.md) (dependency job
  contract — Track A reuses it as-is; its "do not run both" constraint is
  preserved).
- Constrained by [ADR-0007](0007-fail-closed-governance-and-validation-semantics.md)
  (trust model and evidence semantics apply unchanged to this control plane).
- Governed by [ADR-0013](0013-adr-driven-development-governance.md) (this
  decision's own lifecycle).
- Natural follow-on: ADR-0004 (Generic RemediationJob and Workflow Registry,
  reserved) could formalize "run" as a first-class entity across both tracks;
  ADR-0011 (Service Boundaries and Versioned Contracts, reserved) is the
  right place to revisit infra footprint if priorities change later. Neither
  is required by, or authorized by, this decision.
