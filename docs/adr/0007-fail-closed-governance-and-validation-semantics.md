# ADR-0007: Fail-Closed Governance and Validation Semantics

Date: 2026-09-08

Status: Accepted

Acceptance basis: explicit architecture decision supplied by the repository owner
for this ADR-only PR, submitted for human author/reviewer review. CI validates
document integrity; it does not accept architecture. This decision may govern
implementation only after human review and merge make it Accepted in BASE.

Supersedes: none

## Context

The Java remediation pipeline currently conflates patch application, build
validation and permission to create external changes. Inspection of main at
`3a86cd46770545276e935fd9a85cdc5524de70cc` identified these paths:

- `FixPollerService.applyAndValidate` applies a nonblank diff but also accepts an
  empty patch. Both paths call `fix.withBuildResult(true, ...)` with a log stating
  that the build was skipped, set the vulnerability to `FIX_VALIDATED`, and emit
  a successful validation audit entry. No build is run in this polling path.
- `BuildValidator.runBuild` returns `BuildResult(true, "Build validation skipped")`
  when `patcher.skip-build-validation` is enabled. `PatcherService.onFixGenerated`
  consumes that boolean, marks the vulnerability validated, and publishes
  successful validation events. The ordinary build path does execute a build;
  the problem is that a skipped build shares its success representation.
- `Fix.withBuildResult` converts a boolean to `BUILD_VALIDATED` or `BUILD_FAILED`.
  `Fix.buildValidated`, `FixStatus` and `VulnerabilityStatus` do not distinguish
  skipped/incomplete evidence, policy denial and unknown governance outcomes.
- `OpaGovernanceService.evaluate` returns allow when OPA is disabled, its response
  or result is null, or an exception occurs. Malformed responses that cause an
  exception therefore fail open. A nonnull result with a missing/non-true `allow`
  value instead evaluates false; not every malformed shape currently allows.
- `PRService.onFixValidated` blocks an explicit denial but trusts the allow result
  before pushing a branch and creating a PR. Separately, `PRPollerService` selects
  `FIX_VALIDATED`/`BUILD_VALIDATED` records and creates batch PRs without invoking
  `OpaGovernanceService`. These entry points must obey the same authorization rule.

Patch applicability proves only that a change could be applied to a selected
source revision/workspace. It does not prove compilation, passing tests,
regression safety, security resolution or policy compliance. An unavailable
policy engine means authorization is unknown, not granted. LLM confidence or a
review recommendation cannot supply either missing proof.

The separate dependency job described by ADR-0001 already requires build/test
and security validation before publication. This decision preserves that contract
and records semantics for the next Java hardening work; it does not replace the
runner, change its behavior, or claim the Java gaps have been fixed.

## Decision

### Trust and authority

**AI reasons. Java controls. Policies authorize. Sandboxes execute. Humans remain
an explicit approval/escalation authority.**

```text
LLM recommendation / review
  -> Java deterministic control plane
  -> validation evidence
  -> OPA / deterministic governance
  -> authorization decision
  -> external action
```

There is no direct LLM-to-GitHub authorization path. This is the required trust
model for subsequent implementation, not a claim about current enforcement.

### Explicit evidence semantics

Evidence and authorization must be distinguishable, even if represented by
separate records rather than a single lifecycle enum. The following names are
conceptual and do not freeze Java enum names or database/event schemas:

| Outcome | Meaning |
| --- | --- |
| `PATCH_APPLIED` | Candidate applied to a specified revision/workspace; no validation claim. |
| `BUILD_PENDING` / `BUILD_RUNNING` | Build evidence is not yet available. |
| `BUILD_PASSED` | Required build/test checks actually executed and passed for this candidate. |
| `BUILD_FAILED` | Build/test validation failed; cannot be promoted to success. |
| `BUILD_SKIPPED` | Build checks did not execute; evidence is incomplete. |
| `SECURITY_VALIDATION_PASSED` | Required security checks executed and met the remediation class's criteria. |
| `SECURITY_VALIDATION_FAILED` | Required security checks failed. |
| `SECURITY_VALIDATION_SKIPPED` | Required security evidence was not produced. |
| `GOVERNANCE_REVIEW` | Authorization evaluation is pending; no external action is authorized. |
| `APPROVED` | Required evidence, validation, policy allow and risk conditions all satisfied for this action. |
| `POLICY_REJECTED` | Policy explicitly denies the proposed action. |
| `GOVERNANCE_UNKNOWN` | No trustworthy authorization result exists. |
| `HUMAN_REVIEW_REQUIRED` | Automation is blocked pending the required human decision. |

```text
BUILD_SKIPPED != BUILD_PASSED
PATCH_APPLIED != VALIDATED
```

Skipped checks must remain skipped in persistence, events, audit records and
metrics. A log explaining that a build was skipped cannot justify a successful
boolean/status. An empty or no-op patch is not automatically validated and must
not manufacture a successful remediation.

After skipped validation, the controller may block, perform bounded retries,
escalate to human review, or evaluate an explicit policy-based exception. An
exception must be scoped, attributable and recorded, with policy-defined evidence
or compensating controls and any required human approval. It must never relabel
skipped/failed checks as passed or use an LLM recommendation as a waiver. Evidence
still required under that authorized exception must be complete. No exception
may turn an unavailable policy engine into an allow result.

### Fail-closed governance

| OPA result | Required control-plane outcome |
| --- | --- |
| Explicit, well-formed allow | May continue only if all evidence and risk gates also pass. |
| Explicit deny | `POLICY_REJECTED`; block autonomous external action. |
| Unavailable / connection failure | `GOVERNANCE_UNKNOWN`; block autonomous external action. |
| Timeout | `GOVERNANCE_UNKNOWN`; block autonomous external action. |
| Malformed / missing or invalid decision fields | `GOVERNANCE_UNKNOWN`; block autonomous external action. |
| Null response / null result | `GOVERNANCE_UNKNOWN`; block autonomous external action. |

Only a valid explicit allow is positive policy evidence. Truthy strings, missing
fields, parsing failures and service errors are not authorization. Unknown is
distinct from an explicit rejection for diagnostics and retry decisions, but
both block autonomous external action. Unknown results may trigger bounded retry,
escalation or human review; exhausting retries must not default to allow.

### LLM recommendations cannot override gates

```text
LLM reviewer output != authorization
```

An LLM may critique the remediation, assess quality, identify risks, recommend a
retry and generate explanations. It cannot override deterministic validation
failure, policy denial, missing required evidence or governance unavailability.
Human review remains explicit and risk/policy-driven; it does not silently convert
missing validation into success. A denied proposal must be revised or follow an
authorized policy/exception process and be reevaluated before autonomous action.

### Evidence completeness and external actions

```text
required evidence complete for the remediation class
  + required validation passed
  + explicit valid policy allow
  + risk policy satisfied (including required human approval)
  -> autonomous action permitted

incomplete required evidence -> BLOCK / RETRY / HUMAN_REVIEW, never ALLOW
```

Evidence must identify the candidate/source revision it validates, check outcomes,
and supporting results; governance must identify the applicable policy decision
and any exception/approval. Evidence for a different or subsequently modified
candidate cannot authorize the current change. A build pass alone does not prove
security resolution; required security checks must independently pass.

Java must enforce these conditions before autonomous remote branch pushes, PR
creation and other governed external writes, across event, polling and batch
entry points. Batch changes require evidence for the actual combined candidate;
individual fix success cannot substitute for validating the combined change.
An `APPROVED` label without the supporting evidence is insufficient.

### Explicit development behavior

Disabling build validation or OPA for local/dev use must be explicitly configured,
environment-scoped and observable. Record `DEV validation mode = SKIPPED`, not
`validation = PASSED`. Disabled governance supplies no production authorization.
Local preview may continue without governed external writes; autonomous external
action remains blocked unless the required evidence and authorization exist.
There must be no hidden production fail-open path or automatic dev-mode fallback.

## Alternatives considered

- **Fail-open for availability — rejected.** Automation availability is less
  important than preventing unauthorized or unvalidated security changes.
- **Treat skipped builds as pass — rejected.** Absence of evidence is not evidence
  of correctness, and false success corrupts downstream decisions and metrics.
- **Let an AI reviewer override OPA — rejected.** Probabilistic reasoning must not
  override deterministic authorization or validation failures.
- **Always require human review — not selected as the default.** It prevents
  useful bounded autonomy. Instead, require human approval according to risk and
  policy, and escalate when evidence or authorization cannot be established.

## Consequences and risk

Positive consequences include safer autonomous operation, deterministic governance
semantics, clearer audit trails, meaningful validation metrics, easier enterprise
review and prevention of false success.

More remediations may stop or escalate. OPA outages reduce automation availability;
skipped-build environments cannot claim successful validation. Additional evidence
and outcome handling, operational visibility and tests are required. Existing
successful statuses may lack trustworthy evidence and cannot be assumed safe.
**Safety takes priority over autonomous throughput.**

## Migration and rollback

This ADR-only PR changes no runtime, data, Kafka contracts or deployment settings.
After this decision is reviewed and merged, create separate implementation PRs
from a base where ADR-0007 is already Accepted:

| Implementation branch | Initial scope | Goal |
| --- | --- | --- |
| `fix/build-validation-semantics` | `cb-patcher`: `FixPollerService`, `PatcherService`, `BuildValidator`; `Fix`/`Vulnerability` validation state; tests. | Eliminate false validation. |
| `fix/opa-fail-closed` | `cb-pr`: `OpaGovernanceService`, governance result model, PR/action gates including polling/batch paths; tests. | Make policy evaluation fail-closed. |

Neither implementation is included or started here. Each implementation PR must
document compatibility, rollout, handling of legacy evidence and recovery. Do not
convert legacy booleans into proven success without supporting evidence; block or
revalidate affected candidates before autonomous publication. Schema/contract
mechanics belong in the later implementation analysis and relevant separate ADRs.

Before merge, withdraw or revise this decision through human review. After merge,
retain ADR-0007 unchanged and supersede it with a new ADR if the decision changes.
Runtime rollback must preserve fail-closed behavior: pause autonomous external
actions while recovering rather than reenable an unsafe allow-by-default path.

## Fitness functions

The following are required automated assertions for later implementation PRs;
they are not implemented or reported as passing by this documentation PR.

| Scenario | Required assertion |
| --- | --- |
| Build skipped | Never `BUILD_PASSED`, successful validation, or autonomous publication with required evidence missing. |
| Build failure | Never successful validation or autonomous action based on that failed candidate. |
| Empty/no-op patch | Never automatically validated or reported as a successful remediation. |
| OPA explicit valid allow | May continue only when all required validation, evidence and risk gates pass. |
| OPA explicit deny | Block external action; preserve a policy rejection outcome. |
| OPA timeout | Block autonomous action; record unknown governance. |
| OPA connection failure | Block autonomous action; bounded retries never become default allow. |
| OPA null/malformed response | Block autonomous action; record unknown governance. |
| LLM reviewer approval plus OPA denial | Block autonomous action regardless of model confidence or recommendation. |
| Incomplete required evidence | Zero autonomous PR creations, remote pushes or other governed external writes. |
| Skipped/failed security validation | Never claim security validation passed; block when that evidence is required. |
| Dev validation/governance disabled | Explicit skipped/unknown outcome; no successful production validation claim. |
| Polling/event/batch entry point | Same evidence and authorization gates hold on every publication path. |
| Candidate changed after validation | Prior evidence cannot authorize the changed candidate; require current evidence. |

This decision PR must pass ADR document/index/history validation, ADR-only PR
declaration checks, the existing governance test suite and `git diff --check`.
These checks demonstrate documentation integrity, not runtime conformance.

## Related decisions and references

- [ADR-0013](0013-adr-driven-development-governance.md) governs the development
  process: decision review and merge precede implementation authorization.
- ADR-0007 governs validation and governance semantics, without prescribing a
  complete lifecycle engine or sandbox architecture.
- ADR-0006 will separately govern isolated execution; it remains reserved and
  unaccepted. This ADR does not select an isolation platform.
- ADR-0009 will separately govern formal lifecycle/state-machine mechanics; it
  remains reserved and unaccepted. The outcome names here define semantics only.
- [ADR-0001](0001-branch-dependency-remediation.md) remains unchanged and is not
  superseded. Its dedicated dependency-runner contract is preserved.
- Source evidence: [FixPollerService](../../cb-patcher/src/main/java/in/techseva/cb/patcher/service/FixPollerService.java),
  [PatcherService](../../cb-patcher/src/main/java/in/techseva/cb/patcher/service/PatcherService.java),
  [BuildValidator](../../cb-patcher/src/main/java/in/techseva/cb/patcher/service/BuildValidator.java),
  [Fix](../../cb-core/src/main/java/in/techseva/cb/core/domain/Fix.java),
  [Vulnerability](../../cb-core/src/main/java/in/techseva/cb/core/domain/Vulnerability.java),
  [FixStatus](../../cb-core/src/main/java/in/techseva/cb/core/domain/FixStatus.java),
  [VulnerabilityStatus](../../cb-core/src/main/java/in/techseva/cb/core/domain/VulnerabilityStatus.java),
  [OpaGovernanceService](../../cb-pr/src/main/java/in/techseva/cb/pr/service/OpaGovernanceService.java),
  [PRService](../../cb-pr/src/main/java/in/techseva/cb/pr/service/PRService.java) and
  [PRPollerService](../../cb-pr/src/main/java/in/techseva/cb/pr/service/PRPollerService.java).
