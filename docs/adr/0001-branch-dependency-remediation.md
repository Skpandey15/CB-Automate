# ADR 0001: Branch-scoped dependency remediation jobs

Date: 2026-09-06

Status: Accepted for implementation

## Context

The existing Java services handle SonarQube findings and AI-generated code patches.
Their dependency path parses build declarations, guesses some BOM versions, calls
the latest release safe, shares mutable workspaces, and starts PRs from `main`.
PR notification uses a process-local Spring event between separately deployed
services. Those assumptions do not meet the requested input of repository, branch,
and email recipient with verified dependency fixes as output.

## Decision

Add a dedicated, standard-library Python job runner for dependency remediation.
This is the supported entry point for the new branch-input workflow. The Java
SonarQube/AI pipeline is retained; it is not used by this job and its old dependency
endpoints must not be presented as equivalent. Do not run both against the same
repository for dependency remediation. This bounded job avoids extending every
legacy event with repository, branch, revision, recipient, and run metadata.

1. Clone the requested branch into a new run directory and record its commit SHA.
   Never mutate the user's existing checkout. Validate against the same revision
   used for the PR. Refuse publication if the target branch has moved.
2. Ask Maven or Gradle to resolve the dependency graph, including transitive and
   test dependencies. Do not infer a library version from a framework version.
3. Query OSV by exact Maven coordinate/version, following pagination. A timeout,
   malformed response, or unresolved configuration is a failed scan, not a clean
   result. Coverage is known OSV advisories in the selected build's graph, not all
   possible vulnerabilities or every optional build profile.
4. Choose explicit fixed versions from advisory metadata, check those candidates
   against OSV, and prefer the lowest numeric stable upgrade in the same major.
   Major upgrades require an explicit option. Apply only unambiguous literal
   dependency declarations or explicit matching version properties. BOM/catalog-managed and transitive-only
   upgrades are reported for manual remediation rather than guessed. Maven properties
   must be defined in the same POM; Gradle properties must be in the tracked root
   `gradle.properties`. Inherited values and arbitrary build expressions are not guessed.
5. Validate each candidate with the project's build/tests and a fresh resolved
   graph scan. Keep only changes that remove the target vulnerable coordinate and
   introduce no new package/advisory pairs. Restore rejected edits. Validate the
   final combined diff again before publication. Never skip validation.
6. Publish only tracked dependency-file edits on a deterministic fix branch.
   Never force-push or merge. Reuse an existing PR for identical results. Reports
   distinguish fixed, unresolved, failed, and no-findings outcomes.
7. Persist JSON and Markdown reports before notifying. SMTP runs in the same job
   after PR publication; it does not depend on Spring in-process events. Failed
   email leaves a non-success status and can be retried from the saved report
   without scanning or creating another PR. SMTP is at-least-once: a connection
   failure after server acceptance can produce a duplicate on retry.
8. Preview is the default. `--publish` explicitly enables GitHub writes and email.
   No credentials are stored in reports or source. Run trusted builds on isolated
   CI workers; build subprocesses do not receive GitHub or SMTP credentials.

## Consequences

The workflow is usable from PowerShell or a scheduled CI job without deploying
Kafka, MongoDB, an LLM, or the Java services. It adds a Python 3.11+ requirement
alongside Git, GitHub CLI, the project's compatible JDK, and Maven/Gradle.
Build resolution and testing can be expensive. Unsupported or ambiguous updates
remain visible in the report. npm, Python, container images, independent nested
builds, build plugins, and inactive Maven profiles are outside initial coverage.
A future REST front end should enqueue this job with the same immutable inputs.

## References

- [OSV query API and pagination](https://google.github.io/osv.dev/post-v1-query/)
- [Maven dependency tree JSON output](https://maven.apache.org/plugins/maven-dependency-plugin/tree-mojo.html)
- [Gradle initialization scripts](https://docs.gradle.org/current/userguide/init_scripts.html)
