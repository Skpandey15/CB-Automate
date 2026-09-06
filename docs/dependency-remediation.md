# Branch dependency remediation

Use `scripts/dependency-remediation/remediate.py` for the repository + branch →
dependency fixes → PR → email workflow. This command runs independently of the
legacy Java/SonarQube pipeline. See [ADR 0001](adr/0001-branch-dependency-remediation.md).

## Prerequisites

- Python 3.11+, Git, and GitHub CLI (`gh auth login`, with repository write access
  for publication). Configure Git authentication with `gh auth setup-git` if needed.
- A JDK compatible with the target repository and its Maven/Gradle wrapper.
  Set `JAVA_HOME` and put its `bin` directory first on `PATH`.
- Maven or Gradle installed if the target repository has no wrapper.
- Access to package repositories and `https://api.osv.dev`.
- For email: `SMTP_HOST`, `SMTP_PORT` (587 for STARTTLS, 465 for TLS), `SMTP_FROM`,
  and, if required, `SMTP_USERNAME` and `SMTP_PASSWORD` from a secret store.

Only run builds from trusted repositories/branches, preferably in disposable CI
workers. Maven/Gradle builds execute project code. The runner removes GitHub/SMTP
environment credentials from build subprocesses, but does not sandbox the OS or
hide credentials stored elsewhere on the host.

## Preview locally

From `D:\CB-Automate` in PowerShell:

```powershell
python scripts/dependency-remediation/remediate.py --repo Skpandey15/CB-Automate --branch main --output D:\CB-remediation-runs
```

Preview still resolves dependencies, performs upgrades, and runs tests in an
isolated clone. It does not push, create a PR, or email. Each run writes
`report.json`, `report.md`, command logs, the checkout, and (when changes pass)
`changes.patch`. The report records the exact target commit.

## Publish and email

```powershell
python scripts/dependency-remediation/remediate.py --repo your-org/your-product --branch release/1.0 --recipient team@example.com --publish --output D:\CB-remediation-runs
```

Repeat `--recipient` for additional recipients. SMTP credentials come from the
environment, not command arguments. The PR targets the branch passed above and
includes the findings, upgrades, unresolved items, and validation summary.
Nothing is merged automatically. If no verified changes are possible, the runner
sends the report without creating an empty PR. Identical results reuse an existing
PR, including a closed/rejected PR, rather than reopening it on every schedule.

If email fails after PR creation, the report retains the PR URL. Retry just email:

```powershell
python scripts/dependency-remediation/remediate.py --retry-email D:\CB-remediation-runs\RUN-ID\report.json
```

`--allow-major` permits candidate major-version upgrades, still gated by builds and
rescans. The default considers stable numeric same-major fixed versions only.

## Coverage and validation

- Maven: active reactor dependency graph, all scopes, including transitives.
  Uses pinned Maven Dependency Plugin 3.11.0 JSON output and `mvn verify`.
- Gradle: all resolvable configurations of projects in the root build, including
  tests/transitives. Uses an init script and `gradle build`.
- OSV: exact resolved Maven package/version queries with pagination and bounded
  retries. An unavailable scanner fails the run rather than reporting clean.
- Upgrade targets come from advisory fixed-version events, and are checked again
  against OSV before editing. The entire resulting graph is scanned after tests.
- Literal Maven dependency versions and Gradle coordinate strings can be updated,
  along with explicit same-POM properties or referenced root `gradle.properties`
  values that match the actual resolved version. All matching tracked declarations
  are edited together. Inherited properties, BOMs, Gradle catalogs/map notation, transitive-only updates, and
  nonnumeric release schemes remain in the manual-action report.
- Inactive Maven profiles, independent nested builds, Gradle included builds,
  build-tool/plugin dependencies, other ecosystems, and unknown vulnerabilities
  are not claimed as covered. A successful scan means no findings within the
  stated scope, not that the product is vulnerability-free.
- A passing build means the repository's configured verification tasks passed;
  it cannot supply missing tests or override a project's own test exclusions.

An upgrade is retained only if it removes the target vulnerable version, leaves
no known advisories on that package, and introduces no new package/advisory pairs.
Rejected edits are restored. The combined result is validated again. Publication
fails if the target branch moved; rerun to test the new revision.

Exit codes: `0` completed with no remaining findings; `2` unresolved/manual work;
`1` scan/build/publication/notification failure. Reports distinguish preview from
publication and preserve errors. Run directories are retained for audit/retry;
apply your company's retention policy to them (they contain source and logs).

## Periodic execution

The included **Dependency remediation** GitHub Actions workflow provides a branch
input and a `publish` checkbox. It runs the controller from the default branch,
then clones the selected target branch. After this change is merged, configure:

- Repository variables: `CB_TARGET_BRANCH`, `CB_RECIPIENTS` (comma-separated),
  optional `CB_JAVA_VERSION` (default 21) and `SMTP_PORT` (default 587).
- Repository secrets: `SMTP_HOST`, `SMTP_FROM`, `SMTP_USERNAME`, `SMTP_PASSWORD`.
- Repository Actions settings must allow GitHub Actions to create pull requests.
- To enable the daily 02:23 UTC run, set repository variable
  `CB_DEPENDENCY_AUTOMATION_ENABLED=true`. Until then, the schedule does no work.

Scheduled runs publish verified fixes and send email. Manual runs default to
preview. Runs for the same repository/branch are serialized. JSON/Markdown reports
are saved as workflow artifacts for 30 days, even for failed or partial runs.
The automatic GitHub token is scoped to this repository; use the CLI with separate
authorized credentials for other repositories. PRs made with that token may not
trigger other workflows automatically; this runner performs its own build/test gate.

Schedule the publish command on a trusted CI worker (for example Jenkins or Windows
Task Scheduler), using the same repository, branch, and recipients each time.
Store SMTP and GitHub credentials in that scheduler's secret store. Serialize runs
per repository/branch. Repeated runs re-query OSV even if the branch is unchanged.
No repository variables, secrets, or external scheduler settings are changed by
this repository change.

## Tests

```powershell
python -m unittest discover -s scripts/dependency-remediation -p "test_*.py" -v
```

Tests include real local Git clones on a non-main branch with fake advisory/build
services, failed-candidate rollback, scanner outages, existing PR reuse, branch
movement, and failed-email recovery. They never publish or send real email.

For the opt-in integration test that downloads Gradle dependencies, runs a real
JUnit test, upgrades Commons Text, and rescans against live OSV:

```powershell
$env:CB_RUN_NETWORK_TESTS = '1'
python -m unittest discover -s scripts/dependency-remediation -p test_network.py -v
```

This uses a temporary local repository and never calls GitHub or sends email.

See the [validation record](validation/branch-dependency-remediation.md) for the
checks performed and the external integrations not exercised live.
