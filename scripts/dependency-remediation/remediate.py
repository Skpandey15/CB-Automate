#!/usr/bin/env python3
"""Branch-scoped Maven/Gradle remediation. See docs/dependency-remediation.md."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import smtplib
import ssl
import subprocess
import sys
import time
import uuid
from datetime import datetime, timezone
from email.message import EmailMessage
from concurrent.futures import ThreadPoolExecutor
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


class JobError(RuntimeError):
    pass


def save_json(path, value):
    path = Path(path)
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')
    temporary.replace(path)


def build_environment():
    # Build scripts are executable project code. Keep publishing credentials away.
    return {k: v for k, v in os.environ.items()
            if not k.upper().startswith(('GH_', 'GITHUB_', 'SMTP_', 'NOTIFIER_'))}


class Commands:
    def __init__(self, log_dir, timeout=1200):
        self.log_dir = Path(log_dir)
        self.log_dir.mkdir(parents=True, exist_ok=True)
        self.timeout = timeout
        self.number = 0

    def run(self, args, cwd=None, build=False):
        self.number += 1
        log = self.log_dir / f'{self.number:03d}.log'
        env = build_environment() if build else os.environ.copy()
        env['GIT_TERMINAL_PROMPT'] = '0'
        # On Windows wrappers are batch files. Arguments here are runner-owned;
        # untrusted branch/repository input is only passed to git/gh executables.
        command = [str(a) for a in args]
        if os.name == 'nt' and command[0].lower().endswith(('.bat', '.cmd')):
            # cmd /s removes one outer quote pair. Quote every argument inside
            # that pair; list2cmdline alone follows different C-runtime rules.
            # Percent expansion happens even in quotes, so reject it explicitly.
            if any(any(c in arg for c in '"%\r\n') for arg in command):
                raise JobError('Unsupported quote, percent, or newline in Windows wrapper arguments')
            command = 'cmd.exe /d /s /v:off /c "' + ' '.join('"' + arg + '"' for arg in command) + '"'
        with log.open('w', encoding='utf-8') as output:
            process = subprocess.Popen(command, cwd=cwd, env=env, stdout=output,
                                       stderr=subprocess.STDOUT,
                                       start_new_session=os.name != 'nt')
            try:
                code = process.wait(timeout=self.timeout)
            except subprocess.TimeoutExpired:
                if os.name == 'nt':
                    subprocess.run(['taskkill', '/PID', str(process.pid), '/T', '/F'],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                else:
                    import signal
                    os.killpg(process.pid, signal.SIGKILL)
                process.wait()
                raise JobError(f'Command timed out; see {log}')
        if code:
            raise JobError(f'Command failed (exit {code}); see {log}')
        return log.read_text(encoding='utf-8', errors='replace').strip()


class Osv:
    def __init__(self):
        self.cache = {}

    def request(self, payload):
        data = json.dumps(payload).encode()
        endpoint = 'querybatch' if 'queries' in payload else 'query'
        for attempt in range(3):
            try:
                request = Request('https://api.osv.dev/v1/' + endpoint, data=data,
                                  headers={'Content-Type': 'application/json'})
                with urlopen(request, timeout=45) as response:
                    result = json.load(response)
                allowed = ('results',) if endpoint == 'querybatch' else ('vulns', 'next_page_token')
                if not isinstance(result, dict) or 'error' in result or any(key not in allowed for key in result):
                    raise JobError('Invalid OSV response')
                return result
            except HTTPError as exc:
                if exc.code not in (429, 500, 502, 503, 504) or attempt == 2:
                    raise JobError(f'OSV request failed: HTTP {exc.code}') from exc
            except (URLError, TimeoutError) as exc:
                if attempt == 2:
                    raise JobError('OSV unavailable; scan is incomplete') from exc
            except (ValueError, OSError) as exc:
                raise JobError('Could not read OSV response; scan is incomplete') from exc
            time.sleep(2 ** attempt)
        raise JobError('OSV unavailable')

    def query(self, name, version):
        key = (name, version)
        if key in self.cache:
            return self.cache[key]
        payload = {'package': {'ecosystem': 'Maven', 'name': name}, 'version': version}
        findings, tokens = {}, set()
        while True:
            page = self.request(payload)
            vulns = page.get('vulns', [])
            if not isinstance(vulns, list):
                raise JobError('Invalid OSV vulnerability list')
            for vuln in vulns:
                if not isinstance(vuln, dict) or not isinstance(vuln.get('id'), str):
                    raise JobError('Invalid OSV advisory')
                if not vuln.get('withdrawn'):
                    findings[vuln['id']] = vuln
            token = page.get('next_page_token')
            if not token:
                break
            if not isinstance(token, str) or token in tokens:
                raise JobError('Invalid OSV pagination')
            tokens.add(token)
            payload['page_token'] = token
        self.cache[key] = list(findings.values())
        return self.cache[key]

    def scan(self, dependencies):
        findings = []
        coordinates = sorted({(d['name'], d['version']) for d in dependencies})
        print(f'Checking {len(coordinates)} resolved package versions against OSV...', flush=True)
        affected = []
        for start in range(0, len(coordinates), 200):
            chunk = coordinates[start:start + 200]
            response = self.request({'queries': [
                {'package': {'ecosystem': 'Maven', 'name': name}, 'version': version}
                for name, version in chunk]})
            results = response.get('results')
            if not isinstance(results, list) or len(results) != len(chunk):
                raise JobError('Incomplete OSV batch response')
            for coordinate, result in zip(chunk, results):
                if not isinstance(result, dict) or any(k not in ('vulns', 'next_page_token') for k in result):
                    raise JobError('Invalid OSV batch result')
                if 'vulns' in result and not isinstance(result['vulns'], list):
                    raise JobError('Invalid OSV batch advisory list')
                if result.get('vulns') or result.get('next_page_token'):
                    # Fetch full metadata with query, following all its pages from
                    # the beginning. Batch only returns ids, not fixed versions.
                    self.cache.pop(coordinate, None)
                    affected.append(coordinate)
                else:
                    self.cache[coordinate] = []
        print(f'Loading advisory details for {len(affected)} affected package versions...', flush=True)
        with ThreadPoolExecutor(max_workers=4) as executor:
            results = executor.map(lambda coordinate: self.query(*coordinate), affected)
            for (name, version), advisories in zip(affected, results):
                for advisory in advisories:
                    findings.append({'name': name, 'version': version, 'id': advisory['id'],
                                     'summary': advisory.get('summary', ''),
                                     'aliases': advisory.get('aliases', []), 'advisory': advisory})
        return findings


def numeric_version(version):
    if not re.fullmatch(r'\d+(?:\.\d+){0,5}', version):
        return None
    parts = tuple(map(int, version.split('.')))
    return parts + (0,) * (6 - len(parts))


def candidates(name, current, findings, allow_major=False):
    old = numeric_version(current)
    if old is None:
        return []
    versions = set()
    for finding in findings:
        for affected in finding['advisory'].get('affected', []):
            package = affected.get('package', {})
            if package.get('name') != name or package.get('ecosystem') != 'Maven':
                continue
            for span in affected.get('ranges', []):
                if span.get('type') == 'GIT':
                    continue
                for event in span.get('events', []):
                    version = event.get('fixed', '')
                    parsed = numeric_version(version)
                    if parsed and parsed > old and (allow_major or parsed[0] == old[0]):
                        versions.add(version)
    return sorted(versions, key=numeric_version)


def parse_maven_tree(tree, module):
    rows = []
    def visit(node):
        for child in node.get('children', []):
            if not all(isinstance(child.get(k), str) and child[k]
                       for k in ('groupId', 'artifactId', 'version')):
                raise JobError('Incomplete Maven dependency graph')
            rows.append({'name': child['groupId'] + ':' + child['artifactId'],
                         'version': child['version'], 'module': module,
                         'scope': child.get('scope', '')})
            visit(child)
    if not isinstance(tree, dict) or 'artifactId' not in tree:
        raise JobError('Invalid Maven dependency tree')
    visit(tree)
    return rows


class Project:
    def __init__(self, root, commands, output):
        self.root, self.commands, self.output = Path(root), commands, Path(output)
        self.tracked = commands.run(['git', 'ls-files'], cwd=root).splitlines()
        if (self.root / 'pom.xml').exists():
            self.kind = 'maven'
            wrapper = self.root / ('mvnw.cmd' if os.name == 'nt' else 'mvnw')
            self.tool = self.build_tool(wrapper, 'mvn')
        elif any((self.root / n).exists() for n in ('build.gradle', 'build.gradle.kts',
                                                   'settings.gradle', 'settings.gradle.kts')):
            self.kind = 'gradle'
            wrapper = self.root / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
            self.tool = self.build_tool(wrapper, 'gradle')
        else:
            raise JobError('Only Maven/Gradle builds rooted at the repository root are supported')

    @staticmethod
    def build_tool(wrapper, fallback):
        if wrapper.exists():
            # Repositories created on Windows often track wrappers without +x.
            return ['sh', str(wrapper)] if os.name != 'nt' else [str(wrapper)]
        return [shutil.which(fallback) or fallback]

    def build(self):
        print('Running project build and tests...', flush=True)
        args = ['-B', 'verify'] if self.kind == 'maven' else ['build', '--no-daemon']
        self.commands.run([*self.tool, *args], cwd=self.root, build=True)

    def resolve(self):
        print('Resolving dependency graph...', flush=True)
        if self.kind == 'gradle':
            output = self.output / 'gradle-dependencies.json'
            output.unlink(missing_ok=True)
            init = Path(__file__).with_name('resolve.gradle').resolve()
            self.commands.run([*self.tool, '--no-daemon', '--no-configuration-cache',
                               '-I', str(init), f'-Dcb.dependencies.output={output}',
                               ':cbExportDependencies'], cwd=self.root, build=True)
            rows = json.loads(output.read_text(encoding='utf-8'))
        else:
            # Unique relative filename: Maven writes one per active reactor module.
            filename = '.cb-tree-' + uuid.uuid4().hex + '.json'
            self.commands.run([*self.tool, '-B',
                               'org.apache.maven.plugins:maven-dependency-plugin:3.11.0:tree',
                               '-DoutputType=json', f'-DoutputFile={filename}'],
                              cwd=self.root, build=True)
            rows = []
            outputs = list(self.root.rglob(filename))
            if not outputs:
                raise JobError('Maven produced no dependency graph')
            for output in outputs:
                rows.extend(parse_maven_tree(json.loads(output.read_text(encoding='utf-8')),
                                             str(output.parent.relative_to(self.root))))
                output.unlink()
        if not isinstance(rows, list) or any(
                not isinstance(d, dict) or not all(isinstance(d.get(k), str) and d[k]
                                                  for k in ('name', 'version')) for d in rows):
            raise JobError('Invalid resolved dependency graph')
        if not rows:
            raise JobError('Empty dependency graph; coverage cannot be confirmed')
        return rows

    def edits(self, name, old, new):
        """Exact literals or explicit properties equal to the resolved old version."""
        group, artifact = name.split(':', 1)
        edits = {}
        for relative in self.tracked:
            path = self.root / relative
            if path.name not in ('pom.xml', 'build.gradle', 'build.gradle.kts'):
                continue
            if path.is_symlink() or not path.is_file():
                continue
            content = path.read_bytes().decode('utf-8')
            updated = content
            if path.name == 'pom.xml':
                properties = set()
                # Preserve formatting; modify only standalone dependency blocks.
                def patch_dependency(match):
                    block = match.group()
                    if '<!--' in block:
                        return block
                    def value(tag):
                        found = re.search(r'<' + tag + r'>\s*([^<]+?)\s*</' + tag + '>', block)
                        return found.group(1).strip() if found else None
                    if value('scope') == 'import' or (value('groupId'), value('artifactId')) != (group, artifact):
                        return block
                    version = value('version') or ''
                    variable = re.fullmatch(r'\$\{([\w.-]+)}', version)
                    if variable:
                        properties.add(variable[1])
                        return block
                    if version != old:
                        return block
                    return re.sub(r'(<version>\s*)' + re.escape(old) + r'(\s*</version>)',
                                  lambda m: m[1] + new + m[2], block)
                # XML comments can contain realistic dependency declarations.
                chunks = re.split(r'(<!--.*?-->)', content, flags=re.S)
                updated = ''.join(chunk if chunk.startswith('<!--') else re.sub(
                    r'<dependency\b[^>]*>.*?</dependency>', patch_dependency, chunk, flags=re.S)
                                  for chunk in chunks)
                for variable in properties:
                    # Only a property defined in this POM. Inherited/BOM values
                    # require a model-aware migration, not a guessed replacement.
                    pattern = r'(<'+re.escape(variable)+r'>\s*)'+re.escape(old)+r'(\s*</'+re.escape(variable)+r'>)'
                    def patch_properties(match):
                        return re.sub(pattern, lambda m: m[1] + new + m[2], match.group())
                    chunks = re.split(r'(<!--.*?-->)', updated, flags=re.S)
                    updated = ''.join(chunk if chunk.startswith('<!--') else re.sub(
                        r'<properties\b[^>]*>.*?</properties>', patch_properties, chunk, flags=re.S)
                                      for chunk in chunks)
            else:
                # Explicit coordinate string, including Kotlin calls; skip comments.
                chunks = re.split(r'(/\*.*?\*/|//[^\r\n]*)', content, flags=re.S)
                pattern = r'([\'"])' + re.escape(name + ':' + old) + r'\1'
                updated = ''.join(chunk if chunk.startswith(('/*', '//')) else re.sub(
                    pattern, lambda m: m[1] + name + ':' + new + m[1], chunk) for chunk in chunks)
                properties_path = self.root / 'gradle.properties'
                if 'gradle.properties' in self.tracked and not properties_path.is_symlink():
                    properties_text = edits.get('gradle.properties', properties_path.read_bytes()).decode('utf-8')
                    variable_pattern = r'"' + re.escape(name) + r':\$(?:\{([\w]+)}|([\w]+))"'
                    for chunk in chunks:
                        if chunk.startswith(('/*', '//')):
                            continue
                        for variable in re.finditer(variable_pattern, chunk):
                            key = variable[1] or variable[2]
                            prop_pattern = r'(?m)^([ \t]*'+re.escape(key)+r'[ \t]*=[ \t]*)'+re.escape(old)+r'([ \t]*\r?$)'
                            properties_text = re.sub(prop_pattern, lambda m: m[1] + new + m[2], properties_text)
                    if properties_text.encode('utf-8') != properties_path.read_bytes():
                        edits['gradle.properties'] = properties_text.encode('utf-8')
            if updated != content:
                edits[relative] = updated.encode('utf-8')
        return edits


def pairs(findings):
    return {(f['name'], f['id']) for f in findings}


def remaining_actions(findings, attempted):
    reasons = {item['name']: item['reason'] for item in attempted}
    return [{'name': name, 'version': version,
             'reason': reasons.get(name, 'No verified automatic upgrade; review dependency management')}
            for name, version in sorted({(f['name'], f['version']) for f in findings})]


def validate_change(name, old, before, after, dependencies):
    if any(d['name'] == name and d['version'] == old for d in dependencies):
        raise JobError('Original vulnerable version is still resolved (another declaration or constraint)')
    if any(f['name'] == name for f in after):
        raise JobError('Target dependency still has known vulnerabilities')
    if pairs(after) - pairs(before):
        raise JobError('Upgrade introduced new package/advisory findings')


def report_markdown(report):
    lines = ['# Dependency remediation report', '',
             f"Repository: {report['repository']}", f"Target branch: {report['branch']}",
             f"Base commit: {report.get('base_sha', 'unavailable')}",
             f"Status: {report['status']}", f"PR: {report.get('pr_url') or 'not created'}", '',
             f"Mode: {'preview' if report.get('preview') else 'publish'}",
             f"Notification: {report.get('notification', 'not_requested')}",
             f"Validation: {report.get('validation', 'not completed')}", '',
             f"Findings before: {len(report.get('before', []))}",
             f"Findings after: {len(report['after']) if report.get('after') is not None else 'not verified'}", '',
             '## Verified upgrades', '']
    for fix in report.get('fixes', []):
        lines.append(f"- {fix['name']}: {fix['from']} → {fix['to']}")
    if not report.get('fixes'):
        lines.append('None.')
    lines += ['', '## Unresolved findings', '']
    for finding in report.get('after') or []:
        lines.append(f"- {finding['name']}@{finding['version']}: {finding['id']}")
    for item in report.get('manual', []):
        lines.append(f"- {item['name']}@{item['version']}: {item['reason']}")
    if report.get('error'):
        lines += ['', '## Failure', '', report['error']]
    if report.get('notification_error'):
        lines += ['', '## Notification failure', '', report['notification_error']]
    lines += ['', '## Validation and coverage', '',
              'Maven verify / Gradle build plus resolved-graph OSV scans. '
              'Only active project configurations are covered; no claim of zero unknown vulnerabilities.',
              'Property/BOM/catalog-managed and transitive-only upgrades may require manual changes.',
              'Review this PR before merging. No automatic merge is performed.', '']
    return '\n'.join(lines)


def persist(report, directory):
    save_json(directory / 'report.json', report)
    (directory / 'report.md').write_text(report_markdown(report), encoding='utf-8')


def notification_config(report):
    recipients = report.get('recipients', [])
    if not recipients:
        raise JobError('At least one recipient is required for email delivery')
    for recipient in recipients:
        if not re.fullmatch(r'[^\s@,;<>]+@[^\s@,;<>]+\.[^\s@,;<>]+', recipient):
            raise JobError('Invalid recipient email address')
    host = os.environ.get('SMTP_HOST')
    sender = os.environ.get('SMTP_FROM') or os.environ.get('SMTP_USERNAME')
    if not host or not sender:
        raise JobError('Set SMTP_HOST and SMTP_FROM (or SMTP_USERNAME)')
    if '\n' in sender or '\r' in sender:
        raise JobError('Invalid SMTP sender')
    port = int(os.environ.get('SMTP_PORT', '587'))
    if not 1 <= port <= 65535:
        raise JobError('Invalid SMTP port')
    return recipients, host, sender, port


def email_report(report, directory):
    recipients, host, sender, port = notification_config(report)
    message = EmailMessage()
    message['From'] = sender
    message['To'] = ', '.join(recipients)
    message['Subject'] = f"[CB] {report['status']}: {report['repository']} ({report['branch']})"
    message.set_content(report_markdown(report))
    message.add_attachment(json.dumps(report, indent=2).encode(), maintype='application',
                           subtype='json', filename='report.json')
    context = ssl.create_default_context()
    smtp_class = smtplib.SMTP_SSL if port == 465 else smtplib.SMTP
    kwargs = {'context': context} if port == 465 else {}
    with smtp_class(host, port, timeout=30, **kwargs) as smtp:
        if port != 465:
            smtp.starttls(context=context)
        username = os.environ.get('SMTP_USERNAME')
        if username:
            smtp.login(username, os.environ.get('SMTP_PASSWORD', ''))
        refused = smtp.send_message(message)
        if refused:
            raise JobError('SMTP rejected one or more recipients')
    report['notification'] = 'sent'
    report.pop('notification_error', None)
    persist(report, directory)


def publish(report, root, directory, commands, changed_files):
    repository, base = report['repository'], report['branch']
    current = commands.run(['git', 'ls-remote', 'origin', 'refs/heads/' + base], cwd=root)
    if not current or current.split()[0] != report['base_sha']:
        raise JobError('Target branch moved during validation; rerun on its new revision')
    commands.run(['git', 'add', '--', *sorted(changed_files)], cwd=root)
    staged = set(commands.run(['git', 'diff', '--cached', '--name-only'], cwd=root).splitlines())
    if staged - set(changed_files):
        raise JobError('Unrelated staged files; refusing publication')
    diff = commands.run(['git', 'diff', '--cached', '--binary'], cwd=root)
    if not diff:
        raise JobError('No tracked dependency changes to publish')
    fingerprint = hashlib.sha256((repository + base + report['base_sha'] + diff).encode()).hexdigest()[:20]
    head = 'cb/dependencies-' + fingerprint
    report['fix_branch'] = head
    existing = json.loads(commands.run(['gh', 'pr', 'list', '--repo', repository, '--state', 'all',
                                        '--head', head, '--base', base, '--json', 'url,state']))
    if existing:
        # Closed/rejected identical results must not produce another PR every schedule.
        report['pr_url'] = existing[0]['url']
        report['existing_pr_state'] = existing[0]['state']
        return
    commands.run(['git', 'switch', '-c', head], cwd=root)
    commands.run(['git', '-c', 'user.name=Compliance Buddy', '-c',
                  'user.email=cb-bot@users.noreply.github.com', 'commit', '-m',
                  'fix(deps): remediate verified dependency vulnerabilities'], cwd=root)
    # A retry after a successful push but failed PR request can reuse the branch,
    # only when its tree exactly matches the newly validated tree.
    remote = commands.run(['git', 'ls-remote', 'origin', 'refs/heads/' + head], cwd=root)
    if remote:
        commands.run(['git', 'fetch', 'origin', 'refs/heads/' + head], cwd=root)
        remote_tree = commands.run(['git', 'rev-parse', 'FETCH_HEAD^{tree}'], cwd=root)
        local_tree = commands.run(['git', 'rev-parse', 'HEAD^{tree}'], cwd=root)
        if remote_tree != local_tree:
            raise JobError('Existing remediation branch has different content; refusing overwrite')
    else:
        commands.run(['git', 'push', 'origin', 'HEAD:refs/heads/' + head], cwd=root)
    persist(report, directory)
    current = commands.run(['git', 'ls-remote', 'origin', 'refs/heads/' + base], cwd=root)
    if not current or current.split()[0] != report['base_sha']:
        raise JobError('Target branch moved before PR creation; rerun on its new revision')
    report['pr_url'] = commands.run(['gh', 'pr', 'create', '--repo', repository,
                                     '--base', base, '--head', head,
                                     '--title', '[CB] Remediate dependency vulnerabilities',
                                     '--body-file', str(directory / 'report.md')])


def execute(args, commands_factory=Commands, osv_factory=Osv, project_factory=Project):
    run_id = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + uuid.uuid4().hex[:8]
    directory = Path(args.output).resolve() / run_id
    directory.mkdir(parents=True)
    root = directory / 'repo'
    report = {'run_id': run_id, 'repository': args.repo, 'branch': args.branch,
              'recipients': args.recipient, 'status': 'running', 'notification': 'not_requested',
              'before': [], 'after': None, 'fixes': [], 'manual': [], 'preview': not args.publish}
    commands, osv = commands_factory(directory / 'logs', args.timeout), osv_factory()
    persist(report, directory)
    try:
        if args.publish:
            notification_config(report)
        commands.run(['git', 'check-ref-format', '--branch', args.branch])
        remote = commands.run(['git', 'ls-remote', '--heads',
                               f'https://github.com/{args.repo}.git', 'refs/heads/' + args.branch])
        if not remote:
            raise JobError('Requested branch does not exist or is not accessible')
        commands.run(['git', 'clone', '--single-branch', '--branch', args.branch,
                      '--', f'https://github.com/{args.repo}.git', str(root)])
        report['base_sha'] = commands.run(['git', 'rev-parse', 'HEAD'], cwd=root)
        project = project_factory(root, commands, directory)
        # Establish a passing baseline before accepting any upgrade.
        dependencies = project.resolve()
        report['dependencies_before'] = dependencies
        report['before'] = osv.scan(dependencies)
        report['after'] = list(report['before'])
        report['validation'] = 'baseline scan completed'
        persist(report, directory)
        changed_files = set()
        current = report['before']
        if current:
            project.build()
        targets = sorted({(f['name'], f['version']) for f in current})
        for name, version in targets:
            if not any(f['name'] == name and f['version'] == version for f in current):
                continue
            reason = 'No supported fixed version; review major, BOM/property or transitive upgrade'
            for candidate in candidates(name, version, current, args.allow_major):
                if osv.query(name, candidate):
                    continue
                edits = project.edits(name, version, candidate)
                if not edits:
                    reason = 'No supported declaration/property; update parent/BOM/catalog manually'
                    continue
                originals = {path: (root / path).read_bytes() for path in edits}
                try:
                    for path, data in edits.items():
                        (root / path).write_bytes(data)
                    project.build()
                    resolved = project.resolve()
                    osv.cache.clear()  # A cached earlier result is not a post-build rescan.
                    after = osv.scan(resolved)
                    validate_change(name, version, current, after, resolved)
                except JobError as exc:
                    for path, data in originals.items():
                        (root / path).write_bytes(data)
                    reason = str(exc)
                    continue
                changed_files.update(edits)
                report['fixes'].append({'name': name, 'from': version, 'to': candidate,
                                        'files': sorted(edits)})
                current = after
                report['after'] = after
                break
            else:
                report['manual'].append({'name': name, 'version': version, 'reason': reason})
            persist(report, directory)
        if report['fixes']:
            project.build()
            resolved = project.resolve()
            osv.cache.clear()
            final = osv.scan(resolved)
            for fix in report['fixes']:
                validate_change(fix['name'], fix['from'], report['before'], final, resolved)
            report['after'] = final
            report['dependencies_after'] = resolved
            report['validation'] = 'final build/tests and fresh resolved-graph scan passed'
            # Build tools must not sneak unrelated tracked changes into a PR.
            actual = set(commands.run(['git', 'diff', '--name-only'], cwd=root).splitlines())
            if actual - changed_files:
                raise JobError('Build modified unrelated tracked files; refusing publication')
            report['status'] = 'partial' if final else 'fixed'
            persist(report, directory)
            if args.publish:
                publish(report, root, directory, commands, changed_files)
            else:
                patch = commands.run(['git', 'diff', '--binary'], cwd=root)
                (directory / 'changes.patch').write_text(patch + '\n', encoding='utf-8')
        else:
            report['status'] = 'manual_required' if current else 'no_findings'
        report['manual'] = remaining_actions(report['after'], report['manual'])
    except Exception as exc:
        report['status'] = 'failed'
        report['error'] = str(exc)
    persist(report, directory)
    if args.publish:
        try:
            email_report(report, directory)
        except Exception as exc:
            report['notification'] = 'failed'
            report['notification_error'] = str(exc)
            persist(report, directory)
    print(f"{report['status']}: {directory / 'report.md'}", flush=True)
    if report['notification'] == 'failed' or report['status'] == 'failed':
        return 1
    return 2 if report['after'] else 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', help='GitHub owner/repository')
    parser.add_argument('--branch', help='Existing branch to scan and target with the PR')
    parser.add_argument('--recipient', action='append', default=[], help='Email recipient; repeat for multiple')
    parser.add_argument('--publish', action='store_true', help='Push verified fixes, open PR, and send email')
    parser.add_argument('--allow-major', action='store_true', help='Consider numeric stable major upgrades')
    parser.add_argument('--output', default='.cb-runs', help='Directory for isolated runs and reports')
    parser.add_argument('--timeout', type=int, default=1200, help='Seconds per command')
    parser.add_argument('--retry-email', type=Path, help='Resend a saved report.json without rescanning')
    args = parser.parse_args(argv)
    if args.retry_email:
        report = json.loads(args.retry_email.read_text(encoding='utf-8'))
        if args.recipient:
            report['recipients'] = args.recipient
        try:
            email_report(report, args.retry_email.resolve().parent)
            return 0
        except Exception as exc:
            report['notification'] = 'failed'
            report['notification_error'] = str(exc)
            persist(report, args.retry_email.resolve().parent)
            print(f'Email retry failed: {exc}', file=sys.stderr)
            return 1
    if not args.repo or not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', args.repo):
        parser.error('--repo must be owner/repository')
    if not args.branch or args.branch.startswith('-') or any(c in args.branch for c in '\r\n\x00'):
        parser.error('--branch must name an existing branch')
    if args.timeout < 1:
        parser.error('--timeout must be positive')
    if args.publish and not args.recipient:
        parser.error('--publish requires --recipient')
    for recipient in args.recipient:
        if not re.fullmatch(r'[^\s@,;<>]+@[^\s@,;<>]+\.[^\s@,;<>]+', recipient):
            parser.error('Invalid recipient email address')
    return execute(args)


if __name__ == '__main__':
    sys.exit(main())
