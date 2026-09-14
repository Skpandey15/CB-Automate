"""Fake stand-in for scripts/dependency-remediation/remediate.py, used only
by RemediationRunServiceTest to exercise the real ProcessBuilder plumbing
(subprocess spawn, output capture, report.json parsing) without needing a
real GitHub repo, network access, or the real job's full scan/build logic.

Mimics remediate.py's CLI contract exactly: --repo/--branch/--output/
--timeout/--publish/--recipient, a report.json written to
{output}/{run_id}/report.json, and a final stdout line "{status}: {path}".
The outcome is selected by --repo so each test can pick a scenario without
needing env vars or extra flags outside the real contract.
"""
import argparse
import json
import sys
import uuid
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--repo', required=True)
    parser.add_argument('--branch', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--timeout', type=int, default=1200)
    parser.add_argument('--publish', action='store_true')
    parser.add_argument('--recipient', action='append', default=[])
    args = parser.parse_args()

    if args.repo == 'fixture/crash-repo':
        print('fatal: simulated crash before any report was written', file=sys.stderr)
        return 1

    run_id = 'fake-' + uuid.uuid4().hex[:8]
    directory = Path(args.output) / run_id
    directory.mkdir(parents=True)

    if args.repo == 'fixture/fixed-repo':
        report = {'status': 'fixed', 'fixes': [{'coordinate': 'org.example:lib:1.2.3'}], 'after': []}
    elif args.repo == 'fixture/partial-repo':
        report = {'status': 'partial', 'fixes': [{'coordinate': 'a:b:1'}],
                   'after': [{'coordinate': 'c:d:2'}]}
    elif args.repo == 'fixture/no-findings-repo':
        report = {'status': 'no_findings', 'fixes': [], 'after': []}
    elif args.repo == 'fixture/failed-repo':
        report = {'status': 'failed', 'error': 'branch does not exist', 'fixes': [], 'after': []}
    else:
        report = {'status': 'failed', 'error': 'unknown fixture repo: ' + args.repo, 'fixes': [], 'after': []}

    (directory / 'report.json').write_text(json.dumps(report), encoding='utf-8')
    (directory / 'report.md').write_text('# fake report\n', encoding='utf-8')
    print(f"{report['status']}: {directory / 'report.md'}", flush=True)
    return 0


if __name__ == '__main__':
    sys.exit(main())
