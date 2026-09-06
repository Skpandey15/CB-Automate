# Dependency remediation validation

Validated on Windows on 2026-09-06 with Python 3.14.3 and Temurin JDK 21.0.11.

## Results

- 30 unit/local integration tests passed. These include real local Git clone,
  commit and push operations, publication retry after a push, branch movement,
  candidate rollback, scanner outages, property edits, Windows wrapper quoting,
  and SMTP retry behavior with a mocked mail server.
- Live Gradle/OSV integration passed on an isolated `release/fixture` branch.
  Commons Text upgraded from 1.9 to 1.10.0; a real JUnit test passed before and
  after the change. Rescanning confirmed removal of GHSA-599f-7c49-w659.
  A remaining transitive Commons Lang advisory was reported as manual work.
- The full repository `gradlew.bat build --no-daemon` passed (48 tasks) after
  updating stale test constructors and the scanner's missing Kafka mock.
  Existing deprecation/unchecked warnings remain.
- Real dependency resolution on the original repository graph returned 6,254
  configuration rows representing 294 distinct package versions. Its initial
  live scan returned 140 package/advisory findings; the original branch's stale
  test constructors caused the baseline build gate to reject remediation.
  Those test fixtures are corrected in this change. This was a workflow test,
  not a completed remediation of all dependencies in CB-Automate itself.
- GitHub Actions YAML parsed and its embedded Python compiled.

## Boundaries

No real PR was published and no real email was sent. GitHub Actions has not run
on GitHub; repository variables and SMTP secrets still need to be configured.
Maven graph parsing/editing was tested with fixtures, but a live Maven build was
not run on this machine. Linux wrapper invocation has a unit check, not a live
Linux execution in this validation session.

Use the commands in [the runbook](../dependency-remediation.md) to reproduce the
tests. The opt-in network test never publishes or emails. Unsupported dependency
updates remain manual; the runner does not promise to fix every vulnerability.
