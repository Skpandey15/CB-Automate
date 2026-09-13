# Architecture Decision Records

Governing decision: [ADR-0013 — ADR-Driven Development Governance](0013-adr-driven-development-governance.md).

**AI reasons. Java controls. Policies authorize. Sandboxes execute. Humans remain
an explicit approval/escalation authority.** This guide governs how decisions are
made and linked to code. It does not claim that all target runtime invariants have
already been implemented.

## Decision index

| ID | Decision | Recorded status |
| --- | --- | --- |
| ADR-0001 | [Branch-scoped dependency remediation jobs](0001-branch-dependency-remediation.md) | Accepted for implementation (historical wording) |
| ADR-0002 | [Autonomous Remediation Control Plane](0002-autonomous-remediation-control-plane.md) | Accepted |
| ADR-0007 | [Fail-Closed Governance and Validation Semantics](0007-fail-closed-governance-and-validation-semantics.md) | Accepted |
| ADR-0013 | [ADR-Driven Development Governance](0013-adr-driven-development-governance.md) | Accepted |

The following numbers are reserved by the supplied target architecture. Their
records are not yet committed or Accepted; an entry in this list is not approval.

| Reserved ID | Planned decision |
| --- | --- |
| ADR-0003 | Java Deterministic Control Plane |
| ADR-0004 | Generic RemediationJob and Workflow Registry |
| ADR-0005 | Durable Agent Workflow Checkpointing |
| ADR-0006 | Isolated Remediation Execution |
| ADR-0008 | Transactional Outbox, Consumer Inbox, Idempotency and DLQ |
| ADR-0009 | Formal Remediation State Machine and Optimistic Concurrency |
| ADR-0010 | Enterprise Identity, RBAC and MCP Tool Authorization |
| ADR-0011 | Service Boundaries and Versioned Contracts |
| ADR-0012 | Agent Evaluation and Evidence-Based Model Routing |

`docs/CB_Architecture_HLD_v4.md` and `docs/CB_Implementation_LLD_v4.md` are not
currently committed. Until supplied, consult the task's target architecture and
the relevant Accepted ADR. Do not fabricate missing design decisions or rewrite
existing decisions. This first governance PR does not implement PR-2 through PR-6.

## When an ADR is mandatory

An architectural decision is required before implementation changes any of:

- Trust boundaries.
- Autonomous authority or who may authorize/execute an action.
- Databases or sources of truth.
- Events or Kafka contracts.
- Workflows or state machines.
- Cross-service contracts.
- Authentication or authorization.
- Governance.
- External infrastructure or platform dependencies.
- Deployment topology.

Examples: granting an agent PR-creation authority or replacing MongoDB requires
an ADR. Correcting a typo or fixing a bug within an existing accepted design does
not automatically require a new decision. An implementation may reference an
existing ADR when it already governs the change and was Accepted in the
implementation PR's BASE revision.

## Lifecycle and immutable history

1. Read this index, relevant ADRs, and the architecture/implementation documents
   available for the task. Copy [the template](template.md) to a new numbered file.
2. Start as `Proposed`. State context, alternatives, implications, and testable
   fitness functions. Record any decision it supersedes.
3. Obtain acceptance from the human architecture owner/maintainer before coding
   architecture-impacting behavior. Record the acceptance basis in the ADR.
   A passing check, AI recommendation, or self-entered status is not authorization.
4. Mark `Status: Accepted` after human review and merge the ADR-only PR to the
   target base branch. Then create the implementation branch. Its PR must reference
   a decision that already exists as Accepted in its BASE revision. A new or newly
   Accepted ADR in HEAD cannot authorize implementation in the same PR.
5. Accepted records are historical: do not edit their original decision. The CI
   check conservatively preserves the entire accepted file, including its path.
   Put clarifications in a new record or the guide. A changed decision needs a new
   Accepted ADR with `Supersedes: ADR-XXXX`; update the index's relationship without
   rewriting the old record. The old status remains its historical recorded status.
6. If implementation conflicts with the decision, stop that conflicting work and
   propose a superseding ADR. Never edit an Accepted ADR merely to justify code.

```text
ADR PR (Proposed) -> Human review -> Accepted -> Merge ADR
  -> Implementation branch -> Implementation PR references BASE-Accepted ADR
  -> CI validation -> Review / merge
```

```text
BAD: same PR
  + ADR-0020 Status: Accepted
  + implementation of ADR-0020

This is self-authorization and must fail.
```

The rule also applies to governance implementation. Adding ADR-0013 and its
validator in the same PR cannot pass by referencing ADR-0013; the decision must
be merged into BASE first. There is no bootstrap exemption.

Use four-digit numbers and filenames such as `0013-adr-driven-development-governance.md`.
Do not reuse identifiers. ADR-0001's original filename and `Accepted for
implementation` wording are recognized and preserved without migration.
Valid recorded statuses are `Proposed`, `Accepted`, `Accepted for implementation`
(legacy), `Rejected`, `Deprecated`, and `Superseded`. Historical Accepted records
remain immutable, including their status; record supersession in the new ADR/index.

## PR declarations and review

Use [the PR template](../../.github/pull_request_template.md). Complete:

```text
Architecture-Impact: yes
Architecture-Decision: ADR-0013
Implementation-Scope: what changes and what decision governs it
Migration-Impact: compatibility, state/data, rollout, or none with explanation
Rollback: concrete revert/recovery steps
Fitness-Functions: commands, invariants tested, actual results and limitations
```

Use plain, single-line field labels outside Markdown code fences in the actual PR
body. Multiple ADR references may be comma-separated. For harmless changes use
`Architecture-Impact: no`, `Architecture-Decision: none`, and explain why.
Fill out the template even when CI does not require its fields for an older PR.
The implementation example above is valid only after ADR-0013 is Accepted in BASE.

For a separate ADR decision PR, use the same fields:

```text
Architecture-Impact: yes
Architecture-Decision: none
Implementation-Scope: ADR-only decision record; no runtime implementation
Migration-Impact: Decision documentation only; implementation follows after merge
Rollback: Withdraw the unmerged proposal; supersede the decision once merged
Fitness-Functions: ADR document/history/index checks and human architecture review
```

This permits a Proposed or human-reviewed Accepted ADR without using it to
authorize implementation. The actual Git diff must change a numbered ADR and
contain only regular, non-executable Markdown files under `docs/`, the root
`README.md`, or `.github/pull_request_template.md`. Renames, deletions, file modes,
and symlinks are checked. Scripts, workflows, runtime and deployment changes do
not qualify, even when the scope claims "ADR-only". The diff restriction verifies
this exemption; it does not classify ordinary PRs as architecture-impacting.

The **ADR governance / validate** check treats a PR as architectural when its
body explicitly says `yes` or it has the `architecture-impacting` label. Reviewers
may create/apply that label when classification needs correction. A label overrides
a `no` declaration. No path or keyword classifier is used. Missing classification
produces a non-blocking notice; malformed/duplicate declarations are rejected.

Impacted implementation PRs must reference ADRs already Accepted in BASE and
complete the five fields. ADR-only PRs use the narrowly checked exemption above.
The check also preserves all base-Accepted ADRs and validates ADR index links and
unique IDs for every PR. It cannot verify design conformance, human approval, or
whether an external system executes content from an otherwise allowed Markdown
path. The documentation exemption is deliberately narrow and still needs review.
Reviewers must inspect the impact declaration and any changes to the check itself.
Repository administrators can make this check required after merge; no branch
protection setting is changed automatically.

## Impact analysis and implementation report

Before coding each PR, record governing ADR, current behavior, problem, affected
files, design, migration impact, tests, risk, and rollback. After coding, report:
summary, files changed, ADR implemented, before/after behavior, tests added, tests
actually executed and results, limitations, migration/rollback, and follow-up ADRs.

Keep each of the six hardening PRs on its own requested branch. Do not merge PRs
automatically. Do not claim tests passed or changes were pushed without evidence.

## Local validation

Python 3.11+ and Git are sufficient; no application services or new packages are
needed. From the repository root:

```powershell
python -m unittest discover -s scripts/adr-governance -p "test_*.py" -v
python scripts/adr-governance/validate.py --base origin/main --head HEAD
```

The second command checks committed ADR records and their history; it does not
check implementation authorization without the PR event. Add
`--event path/to/pull-request-event.json` to also validate the PR body/labels using
the same event shape as GitHub. CI reads `GITHUB_EVENT_PATH` and runs with read-only
permissions; PR text is parsed as data and is never executed.
