#!/usr/bin/env python3
"""Read-only ADR-0013 fitness checks. PR contents are data, never shell code."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import posixpath
import re
import subprocess


ACCEPTED = {'Accepted', 'Accepted for implementation'}
VALID_STATUSES = ACCEPTED | {'Proposed', 'Rejected', 'Deprecated', 'Superseded'}
REQUIRED = ('Architecture-Decision', 'Implementation-Scope', 'Migration-Impact',
            'Rollback', 'Fitness-Functions')
NUMBERED = re.compile(r'^docs/adr/(\d{4})-[^/]+\.md$')


class ValidationError(ValueError):
    pass


def status(text):
    values = re.findall(r'^Status:[ \t]*(.+?)[ \t]*$', text, re.M)
    return values[0] if len(values) == 1 else None


def declarations(body):
    """Ignore code examples and comments, which do not classify the actual PR."""
    body = re.sub(r'<!--.*?-->', '', body, flags=re.S)
    result = {}
    fence = None
    for line in body.splitlines():
        marker = re.match(r'^\s*(`{3,}|~{3,})', line)
        if marker:
            token = marker[1]
            if fence is None:
                fence = (token[0], len(token))
            elif token[0] == fence[0] and len(token) >= fence[1]:
                fence = None
            continue
        if fence is not None:
            continue
        match = re.match(r'^(' + '|'.join(('Architecture-Impact', *REQUIRED)) + r'):[ \t]*(.*)$', line)
        if match:
            result.setdefault(match[1], []).append(match[2].strip())
    return result


def validate_pr(body, labels, accepted_in_base, decision_only_diff=False):
    fields = declarations(body or '')
    impact = fields.get('Architecture-Impact', [])
    if len(impact) > 1:
        raise ValidationError('Architecture-Impact must appear exactly once when declared')
    if impact and impact[0].lower() not in ('yes', 'no'):
        raise ValidationError('Architecture-Impact must be yes or no')
    architectural = 'architecture-impacting' in labels or bool(impact and impact[0].lower() == 'yes')
    if not architectural:
        return ('Non-architectural PR: no ADR reference required.' if impact else
                'NOTICE: impact is undeclared; reviewer classification is required. No path heuristic applied.')
    for key in REQUIRED:
        values = fields.get(key, [])
        if len(values) != 1 or not values[0] or re.search(r'<[^>]*>|\b(?:TBD|TODO)\b', values[0], re.I):
            raise ValidationError(f'{key} must contain one completed declaration')
    decision = fields['Architecture-Decision'][0]
    if decision.lower() == 'none':
        if not decision_only_diff:
            raise ValidationError('Architecture-Decision: none requires an ADR-only documentation diff; '
                                  'implementation must reference an ADR already Accepted in BASE')
        return 'ADR-only decision PR: no implementation authorization granted.'
    ids = set(re.findall(r'\bADR-\d{4}\b', decision))
    if not ids or 'ADR-XXXX' in decision or re.search(r'\b(?:none|n/a)\b', decision, re.I):
        raise ValidationError('Architecture-Decision must reference an Accepted ADR-XXXX')
    missing = ids - accepted_in_base
    if missing:
        raise ValidationError('ADR references were not already Accepted in BASE: ' + ', '.join(sorted(missing)))
    return 'Architectural implementation references Accepted decisions in BASE: ' + ', '.join(sorted(ids))


class GitRepository:
    def __init__(self, root):
        self.root = Path(root)

    def git(self, *args):
        process = subprocess.run(['git', *args], cwd=self.root, capture_output=True)
        if process.returncode:
            raise ValidationError('Git could not read the requested revision or ADR path')
        return process.stdout

    def revision(self, ref):
        return self.git('rev-parse', '--verify', '--end-of-options', ref + '^{commit}').decode().strip()

    def files(self, revision):
        entries = {}
        for entry in self.git('ls-tree', '-r', '-z', revision).split(b'\0'):
            if not entry:
                continue
            metadata, path = entry.split(b'\t', 1)
            mode, kind, oid = metadata.decode().split()
            entries[path.decode('utf-8')] = (mode, kind, oid)
        return entries

    def text(self, entry):
        if entry[1] != 'blob' or entry[0] not in ('100644', '100755'):
            raise ValidationError('ADR documents must be regular text files')
        return self.git('cat-file', 'blob', entry[2]).decode('utf-8').replace('\r\n', '\n')


def decision_only_changes(repo, base, head):
    """Verify the narrow ADR-only exemption from Git, never from scope prose.

    This is not an architecture classifier. It only checks whether a PR explicitly
    using Architecture-Decision: none can qualify as a decision/documentation PR.
    Compare both sides of renames/deletions and reject executable docs/symlinks.
    """
    before, after = repo.files(base), repo.files(head)
    changed = {path for path in before.keys() | after.keys() if before.get(path) != after.get(path)}
    if not any(NUMBERED.fullmatch(path) for path in changed):
        return False
    for path in changed:
        documentation = ((path.startswith('docs/') and path.endswith('.md')) or
                         path in ('README.md', '.github/pull_request_template.md'))
        if not documentation:
            return False
        for entry in (before.get(path), after.get(path)):
            if entry and entry[:2] != ('100644', 'blob'):
                return False
    return True


def validate_repository(repo, base, head):
    """Validate HEAD integrity, but return authorization exclusively from BASE."""
    before, after = repo.files(base), repo.files(head)
    accepted_in_base = set()
    for path, entry in before.items():
        if NUMBERED.fullmatch(path) and status(repo.text(entry)) in ACCEPTED:
            if after.get(path) != entry:
                raise ValidationError(f'Accepted ADR is immutable: {path}. Add a superseding ADR instead.')
            accepted_in_base.add('ADR-' + NUMBERED.fullmatch(path)[1])
    for path in ('docs/adr/README.md', 'docs/adr/template.md'):
        if path not in after:
            raise ValidationError(f'Missing governance document: {path}')
    identifiers = set()
    numbered_paths = set()
    for path, entry in after.items():
        match = NUMBERED.fullmatch(path)
        if not match:
            continue
        identifier = 'ADR-' + match[1]
        if identifier in identifiers:
            raise ValidationError(f'Duplicate ADR identifier: {identifier}')
        identifiers.add(identifier)
        numbered_paths.add(path)
        content = repo.text(entry)
        heading = re.match(r'^# ADR[- ](\d{4}):\s+\S', content)
        if not heading or heading[1] != match[1] or status(content) not in VALID_STATUSES:
            raise ValidationError(f'ADR heading or status missing/mismatched: {path}')
    index = repo.text(after['docs/adr/README.md'])
    linked = set()
    for target in re.findall(r'\]\(([^)]+)\)', index):
        target = target.strip('<>').split('#', 1)[0]
        if not target or re.match(r'^[a-zA-Z][a-zA-Z0-9+.-]*:', target):
            continue
        path = posixpath.normpath(posixpath.join('docs/adr', target))
        if path.startswith(('/', '../')) or path not in after:
            raise ValidationError(f'Broken local ADR index link: {target}')
        linked.add(path)
    if numbered_paths - linked:
        raise ValidationError('Unindexed ADR records: ' + ', '.join(sorted(numbered_paths - linked)))
    return accepted_in_base


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', required=True)
    parser.add_argument('--head', default='HEAD')
    parser.add_argument('--repo', type=Path, default=Path('.'))
    parser.add_argument('--event', type=Path)
    args = parser.parse_args(argv)
    try:
        repo = GitRepository(args.repo)
        base, head = repo.revision(args.base), repo.revision(args.head)
        accepted_in_base = validate_repository(repo, base, head)
        if args.event:
            event = json.loads(args.event.read_text(encoding='utf-8'))
            pr = event.get('pull_request')
            if not isinstance(pr, dict):
                raise ValidationError('Expected a pull_request event')
            labels = [label['name'] for label in pr.get('labels', [])]
            print(validate_pr(pr.get('body'), labels, accepted_in_base,
                              decision_only_diff=decision_only_changes(repo, base, head)))
        print('ADR history, identifiers, and index links passed.')
        return 0
    except (ValidationError, UnicodeError, OSError, ValueError, KeyError, TypeError) as exc:
        print(f'ADR governance failed: {exc}')
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
