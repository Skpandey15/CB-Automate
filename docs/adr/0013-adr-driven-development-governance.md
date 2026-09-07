# ADR-0013: ADR-Driven Development Governance

Date: 2026-09-08

Status: Accepted

Acceptance basis: explicit PR-1 governance requirements supplied with this task.

## Context

Compliance Buddy is evolving toward an Autonomous Remediation Control Plane.
The architectural principle is: **AI reasons. Java controls. Policies authorize.
Sandboxes execute. Humans remain an explicit approval/escalation authority.**

The repository currently has ADR-0001, but no ADR lifecycle guide, shared template,
required PR decision fields, or architectural fitness check. The supplied v4 HLD
and LLD paths are not committed at the time of this decision. The supplied target
architecture and PR-1 instructions govern this governance change; this decision
does not invent missing v4 specifications or accept the remaining planned ADRs.

## Decision

1. Architecture-impacting implementation must reference an Accepted ADR before
   implementation starts. An ADR is mandatory for a trust-boundary, autonomous
   authority, database/source-of-truth, event/Kafka contract, workflow/state-machine,
   cross-service contract, authentication/authorization, governance, external
   infrastructure/platform dependency, or deployment topology change.
2. Keep ADRs in `docs/adr/` with stable four-digit identifiers, a date, a status,
   context, a decision, consequences, migration, rollback, and fitness functions.
   Record acceptance by a human architecture owner/maintainer before implementing.
   AI-generated proposals and a passing CI check do not constitute acceptance.
3. Accepted ADRs are historical records. Do not rewrite their original decisions
   to match implementation. A changed decision requires a new Accepted ADR that
   names the old decision in `Supersedes:`. Track the current relationship in the
   ADR index and the new ADR; do not edit, rename, or delete the historical file.
   Preserve ADR-0001 and its existing identifier, filename, and acceptance wording.
4. Every PR uses the fields `Architecture-Decision`, `Implementation-Scope`,
   `Migration-Impact`, `Rollback`, and `Fitness-Functions`. Authors explicitly
   declare `Architecture-Impact: yes` or `Architecture-Impact: no`. For changes
   without architectural impact, use `Architecture-Decision: none` and explain
   why in the implementation scope. Reviewers validate the classification.
5. CI validates impacted PRs when the declaration says `yes` or the reviewer label
   `architecture-impacting` is present. Require the five completed fields and
   verify each referenced ADR exists and is Accepted. New ADRs accepted in the
   same PR are permitted when the human acceptance basis is recorded; reviewers
   remain responsible for checking it. Do not infer architecture impact from
   file paths, keywords in prose, or ordinary code/dependency changes.
6. Missing impact declarations on older/harmless PRs produce an advisory message,
   not a blocking heuristic. Explicit invalid or contradictory declarations must
   be corrected. A reviewer-applied architecture label takes precedence over a
   `no` declaration. Preserve already-Accepted ADR files by comparing the base and
   head revisions, including content and path. Validate identifier uniqueness and
   local ADR index links independently of PR classification.
7. Run the check in a read-only `pull_request` workflow with no secrets, write
   permissions, or interpolation of PR text into shell commands. It has no
   authority to merge, approve policy, or perform production actions. A maintainer
   may configure the check as required in repository branch protection; this PR
   does not change repository-level settings.
8. Before each implementation PR, record impact analysis covering governing ADR,
   current behavior, problem, files, design, migration, tests, risk, and rollback.
   After implementation, record behavior changes, actual test results, limitations,
   migration/rollback, and any necessary follow-up ADR. Stop conflicting work and
   propose a superseding decision instead of silently changing the architecture.

## Alternatives considered

- Informal prose-only references: cannot reliably establish which decision was
  implemented or distinguish acceptance from an AI proposal.
- Automatic path/keyword classification: blocks routine edits and misses subtle
  authority changes. Explicit author declarations plus human review are preferable.
- Enforcing new fields on every historical PR: unnecessary adoption friction.
  Limit blocking checks to explicitly classified architectural changes.

## Consequences and risk

Decisions become traceable and immutable. Authors and reviewers have a small
documentation burden. CI validates syntax and declared status; it cannot prove
human acceptance, classify undeclared architecture changes, or prove implementation
conformance. Changes to the validator/workflow require maintainer review like other
governance changes. Existing runtime gaps remain work for later ADRs and PRs.

## Migration and rollback

Add templates and CI without changing Java services, Python remediation behavior,
state, data, contracts, infrastructure, or existing capabilities. ADR-0001 remains
unchanged. Reserved ADRs 0002–0012 are not implicitly Accepted by this decision.

Rollback the templates/check using a reviewed revert if adoption causes problems.
Retain this decision as history; a changed governance decision requires a new ADR
that supersedes ADR-0013. No database or runtime rollback is needed.

## Fitness functions

- Architecture declaration or label + missing/Proposed/unknown ADR fails.
- Accepted ADR + all required fields passes.
- Harmless/unclassified PR passes without path-based classification.
- Editing, deleting, or renaming a base-Accepted ADR fails.
- ADR identifiers and local index links are valid and unique.
- Unit and local Git integration tests cover these rules; CI runs them before
  evaluating the PR event JSON.

## Related decisions

- [ADR-0001](0001-branch-dependency-remediation.md) is preserved, not superseded.
- ADRs 0002–0012 remain reserved for the supplied target architecture. They must
  be separately recorded and accepted before their implementation changes.
