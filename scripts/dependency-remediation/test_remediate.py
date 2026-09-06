import argparse
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch

import remediate as r


def advisory(name='org.example:library', fixed='1.2.3', identifier='GHSA-test'):
    return {'id': identifier, 'affected': [{'package': {'ecosystem': 'Maven', 'name': name},
            'ranges': [{'type': 'ECOSYSTEM', 'events': [{'introduced': '0'}, {'fixed': fixed}]}]}]}


def finding(version='1.0.0', **kwargs):
    data = advisory(**kwargs)
    return {'name': 'org.example:library', 'version': version, 'id': data['id'], 'advisory': data}


class OsvTests(unittest.TestCase):
    def test_batch_pagination_fetches_full_metadata_and_keeps_clean_coordinates(self):
        osv = r.Osv()
        osv.request = Mock(side_effect=[{'results': [{}, {'next_page_token': 'more'}]},
                                       {'vulns': [advisory()]}])
        rows = [{'name': 'a:clean', 'version': '1'}, {'name': 'org.example:library', 'version': '1'}]
        findings = osv.scan(rows)
        self.assertEqual([f['name'] for f in findings], ['org.example:library'])
        self.assertEqual(osv.query('a:clean', '1'), [])
        self.assertEqual(osv.request.call_count, 2)

    def test_incomplete_batch_response_is_not_clean(self):
        osv = r.Osv()
        osv.request = Mock(return_value={'results': []})
        with self.assertRaises(r.JobError):
            osv.scan([{'name': 'a:b', 'version': '1'}])

    def test_pagination_and_withdrawn(self):
        osv = r.Osv()
        osv.request = Mock(side_effect=[{'vulns': [advisory()], 'next_page_token': 'next'},
                                       {'vulns': [{**advisory(identifier='withdrawn'), 'withdrawn': 'date'}]}])
        self.assertEqual([v['id'] for v in osv.query('org.example:library', '1.0')], ['GHSA-test'])
        self.assertEqual(osv.request.call_count, 2)

    def test_repeated_pagination_is_not_clean(self):
        osv = r.Osv()
        osv.request = Mock(return_value={'next_page_token': 'repeat'})
        with self.assertRaises(r.JobError):
            osv.query('org.example:library', '1.0')

    def test_malformed_advisory_is_not_clean(self):
        osv = r.Osv()
        osv.request = Mock(return_value={'vulns': [{}]})
        with self.assertRaises(r.JobError):
            osv.query('org.example:library', '1.0')

    def test_fixed_versions_only_same_major_and_numeric(self):
        findings = [finding(fixed=v) for v in ('1.9.0', '1.2.3', '2.0.0', '1.3-rc1')]
        self.assertEqual(r.candidates('org.example:library', '1.0.0', findings), ['1.2.3', '1.9.0'])
        self.assertEqual(r.candidates('org.example:library', '1.0.0', findings, True)[-1], '2.0.0')

    def test_fixed_version_for_other_package_not_used(self):
        self.assertEqual(r.candidates('org.example:library', '1.0',
                                      [finding(name='other:package')]), [])

    def test_lookup_failure_propagates(self):
        osv = r.Osv()
        osv.request = Mock(side_effect=r.JobError('offline'))
        with self.assertRaises(r.JobError):
            osv.scan([{'name': 'org.example:library', 'version': '1.0'}])


class ProjectTests(unittest.TestCase):
    @unittest.skipUnless(r.os.name == 'nt', 'Windows batch wrapper behavior')
    def test_windows_wrapper_in_directory_with_spaces(self):
        with tempfile.TemporaryDirectory(prefix='cb wrapper & ') as folder:
            wrapper = Path(folder) / 'test.cmd'
            wrapper.write_text('@echo off\necho %~1\n')
            commands = r.Commands(Path(folder) / 'logs')
            self.assertEqual(commands.run([str(wrapper), 'argument with spaces'], cwd=folder), 'argument with spaces')

    @unittest.skipUnless(r.os.name == 'nt', 'Windows percent expansion')
    def test_windows_wrapper_rejects_percent_expansion(self):
        with tempfile.TemporaryDirectory() as folder:
            commands = r.Commands(Path(folder) / 'logs')
            with self.assertRaises(r.JobError):
                commands.run(['wrapper.cmd', '%PATH%'])

    def test_unix_wrapper_does_not_require_executable_bit(self):
        wrapper = Mock()
        wrapper.exists.return_value = True
        wrapper.__str__ = Mock(return_value='/tmp/project/gradlew')
        with patch.object(r.os, 'name', 'posix'):
            self.assertEqual(r.Project.build_tool(wrapper, 'gradle'), ['sh', '/tmp/project/gradlew'])

    def test_manual_actions_follow_current_transitive_version(self):
        result = r.remaining_actions([{'name': 'g:lib', 'version': '1.1', 'id': 'GHSA-x'}],
                                     [{'name': 'g:lib', 'version': '1.0', 'reason': 'Update parent'}])
        self.assertEqual(result, [{'name': 'g:lib', 'version': '1.1', 'reason': 'Update parent'}])

    def test_explicit_maven_property_is_upgraded_without_guessing_inherited_values(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'pom.xml').write_text('''<project><properties><library.version>1.0</library.version></properties>
<dependencies><dependency><groupId>org.example</groupId><artifactId>library</artifactId>
<version>${library.version}</version></dependency></dependencies></project>''')
            commands = Mock()
            commands.run.return_value = 'pom.xml'
            project = r.Project(root, commands, root)
            edits = project.edits('org.example:library', '1.0', '1.1')
            self.assertIn(b'<library.version>1.1</library.version>', edits['pom.xml'])
            self.assertIn(b'${library.version}', edits['pom.xml'])
            self.assertEqual(project.edits('org.example:library', '2.0', '2.1'), {})

    def test_gradle_shared_property_tracks_the_correct_file(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'build.gradle').write_text('implementation "org.example:library:${libraryVersion}"')
            (root / 'gradle.properties').write_bytes(b'libraryVersion=1.0\r\nunrelatedVersion=1.0\r\n')
            commands = Mock()
            commands.run.return_value = 'build.gradle\ngradle.properties'
            edits = r.Project(root, commands, root).edits('org.example:library', '1.0', '1.1')
            self.assertEqual(edits, {'gradle.properties': b'libraryVersion=1.1\r\nunrelatedVersion=1.0\r\n'})

    def test_maven_includes_transitive(self):
        tree = {'artifactId': 'root', 'children': [
            {'groupId': 'g', 'artifactId': 'direct', 'version': '1', 'children': [
                {'groupId': 'g', 'artifactId': 'transitive', 'version': '2', 'scope': 'test'}]}]}
        self.assertEqual([d['name'] for d in r.parse_maven_tree(tree, '.')], ['g:direct', 'g:transitive'])

    def test_maven_updates_all_literal_occurrences_and_preserves_comments(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            block = '<dependency><groupId>org.example</groupId><artifactId>library</artifactId><version>1.0</version></dependency>'
            content = '<project><!--' + block + '--><dependencies>' + block + '</dependencies></project>'
            (root / 'pom.xml').write_text(content)
            commands = Mock()
            commands.run.return_value = 'pom.xml\nmodule/pom.xml'
            (root / 'module').mkdir()
            (root / 'module/pom.xml').write_text('<project>' + block + '</project>')
            project = r.Project(root, commands, root)
            edits = project.edits('org.example:library', '1.0', '1.1')
            self.assertEqual(len(edits), 2)
            self.assertIn(('<!--' + block + '-->').encode(), edits['pom.xml'])
            self.assertIn(b'<version>1.1</version>', edits['pom.xml'])

    def test_gradle_does_not_guess_property_or_bom_versions(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'build.gradle').write_text('''// implementation 'org.example:library:1.0'
implementation "org.example:library:$libraryVersion"
implementation 'org.example:library'
implementation('org.example:library:1.0')
''')
            commands = Mock()
            commands.run.return_value = 'build.gradle'
            edits = r.Project(root, commands, root).edits('org.example:library', '1.0', '1.1')
            content = edits['build.gradle'].decode()
            self.assertIn("// implementation 'org.example:library:1.0'", content)
            self.assertIn('$libraryVersion', content)
            self.assertIn("implementation('org.example:library:1.1')", content)

    def test_validation_rejects_unremoved_version_or_new_advisory(self):
        with self.assertRaises(r.JobError):
            r.validate_change('org.example:library', '1', [], [],
                              [{'name': 'org.example:library', 'version': '1'}])
        with self.assertRaises(r.JobError):
            r.validate_change('org.example:library', '1', [],
                              [{'name': 'other:library', 'id': 'new'}], [])


class PublicationTests(unittest.TestCase):
    def test_publish_to_local_remote_uses_requested_base_and_can_resume_after_push(self):
        with tempfile.TemporaryDirectory() as folder:
            parent = Path(folder)
            remote, root = parent / 'remote.git', parent / 'work'
            subprocess.run(['git', 'init', '--bare', str(remote)], check=True, capture_output=True)
            subprocess.run(['git', 'init', '-b', 'release/test', str(root)], check=True, capture_output=True)
            commands = r.Commands(parent / 'logs')
            (root / 'pom.xml').write_text('<version>1.0</version>')
            commands.run(['git', 'add', '.'], cwd=root)
            commands.run(['git', '-c', 'user.name=Test', '-c', 'user.email=t@example.com',
                          'commit', '-m', 'initial'], cwd=root)
            base_sha = commands.run(['git', 'rev-parse', 'HEAD'], cwd=root)
            commands.run(['git', 'remote', 'add', 'origin', str(remote)], cwd=root)
            commands.run(['git', 'push', 'origin', 'release/test'], cwd=root)
            (root / 'pom.xml').write_text('<version>1.1</version>')

            class LocalGitHub(r.Commands):
                fail_create = True
                calls = []
                def run(self, args, **kwargs):
                    if args[0] == 'gh':
                        self.calls.append(args)
                        if args[1:3] == ['pr', 'list']:
                            return '[]'
                        if self.fail_create:
                            raise r.JobError('Simulated GitHub failure after push')
                        return 'https://github.com/example/repo/pull/12'
                    return super().run(args, **kwargs)

            github = LocalGitHub(parent / 'publish-logs')
            report = {'repository': 'example/repo', 'branch': 'release/test', 'base_sha': base_sha,
                      'status': 'fixed', 'preview': False, 'after': []}
            with self.assertRaises(r.JobError):
                r.publish(report, root, parent, github, {'pom.xml'})
            head = report['fix_branch']
            self.assertTrue(commands.run(['git', 'ls-remote', str(remote), 'refs/heads/' + head]))
            self.assertEqual(commands.run(['git', 'ls-remote', str(remote), 'refs/heads/release/test']).split()[0], base_sha)

            # A later run starts again from the unchanged requested base.
            retry = parent / 'retry'
            commands.run(['git', 'clone', '--branch', 'release/test', str(remote), str(retry)])
            (retry / 'pom.xml').write_text('<version>1.1</version>')
            github.fail_create = False
            r.publish(report, retry, parent, github, {'pom.xml'})
            self.assertEqual(report['fix_branch'], head)
            self.assertTrue(report['pr_url'].endswith('/12'))
            create = github.calls[-1]
            self.assertEqual(create[create.index('--base') + 1], 'release/test')
            self.assertEqual(create[create.index('--head') + 1], head)

    def test_email_retry_does_not_scan_or_recreate_pr(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'report.json'
            r.save_json(path, {'repository': 'a/b', 'branch': 'main', 'status': 'fixed',
                               'recipients': ['dev@example.com'], 'notification': 'failed',
                               'notification_error': 'offline', 'pr_url': 'https://github.com/a/b/pull/1'})
            with patch.dict(r.os.environ, {'SMTP_HOST': 'smtp.example.com', 'SMTP_FROM': 'bot@example.com'}), \
                 patch.object(r.smtplib, 'SMTP') as smtp, patch.object(r, 'execute') as execute:
                smtp.return_value.__enter__.return_value.send_message.return_value = {}
                self.assertEqual(r.main(['--retry-email', str(path)]), 0)
                execute.assert_not_called()
            report = json.loads(path.read_text())
            self.assertEqual(report['notification'], 'sent')
            self.assertNotIn('notification_error', report)
            self.assertTrue(report['pr_url'].endswith('/1'))

    def test_partial_smtp_rejection_is_failure(self):
        report = {'repository': 'a/b', 'branch': 'main', 'status': 'fixed',
                  'recipients': ['dev@example.com']}
        with patch.dict(r.os.environ, {'SMTP_HOST': 'mail.example.com', 'SMTP_FROM': 'bot@example.com'}), \
             patch.object(r.smtplib, 'SMTP') as smtp:
            smtp.return_value.__enter__.return_value.send_message.return_value = {'dev@example.com': (550, b'rejected')}
            with self.assertRaises(r.JobError):
                r.email_report(report, Path('.'))

    def test_unrelated_staged_files_block_publication(self):
        commands = Mock()
        commands.run.side_effect = ['sha refs/heads/main', '', 'pom.xml\nsource.java']
        with self.assertRaises(r.JobError):
            r.publish({'repository': 'a/b', 'branch': 'main', 'base_sha': 'sha'},
                      Path('.'), Path('.'), commands, {'pom.xml'})
        self.assertEqual(commands.run.call_count, 3)

    def test_moved_base_never_pushes(self):
        commands = Mock()
        commands.run.return_value = 'new-sha refs/heads/release/1'
        with self.assertRaises(r.JobError):
            r.publish({'repository': 'a/b', 'branch': 'release/1', 'base_sha': 'old'},
                      Path('.'), Path('.'), commands, {'pom.xml'})
        self.assertEqual(commands.run.call_count, 1)

    def test_existing_pr_reused_without_push_even_when_closed(self):
        commands = Mock()
        commands.run.side_effect = ['sha refs/heads/release/1', '', 'pom.xml', 'diff',
                                    '[{"url":"https://github.com/a/b/pull/1","state":"CLOSED"}]']
        report = {'repository': 'a/b', 'branch': 'release/1', 'base_sha': 'sha'}
        r.publish(report, Path('.'), Path('.'), commands, {'pom.xml'})
        self.assertEqual(report['existing_pr_state'], 'CLOSED')
        self.assertFalse(any('push' in call.args[0] for call in commands.run.call_args_list))
        self.assertIn('release/1', commands.run.call_args_list[-1].args[0])

    def test_smtp_failure_not_recorded_as_sent(self):
        report = {'repository': 'a/b', 'branch': 'main', 'status': 'fixed',
                  'recipients': ['dev@example.com'], 'notification': 'not_requested'}
        with patch.dict(r.os.environ, {'SMTP_HOST': 'mail.example.com', 'SMTP_FROM': 'bot@example.com'}):
            with patch.object(r.smtplib, 'SMTP', side_effect=OSError('offline')):
                with self.assertRaises(OSError):
                    r.email_report(report, Path('.'))
        self.assertEqual(report['notification'], 'not_requested')

    def test_build_environment_strips_publish_credentials(self):
        with patch.dict(r.os.environ, {'GH_TOKEN': 'secret', 'SMTP_PASSWORD': 'secret', 'JAVA_HOME': 'jdk'}):
            env = r.build_environment()
            self.assertNotIn('GH_TOKEN', env)
            self.assertNotIn('SMTP_PASSWORD', env)
            self.assertEqual(env['JAVA_HOME'], 'jdk')


class JobIntegrationTests(unittest.TestCase):
    """Exercise the orchestration with real local Git and fake build/advisory edges."""
    def run_job(self, directory, broken_build=False, scan_error=False, publish=False):
        directory = Path(directory)
        source = directory / 'source'
        source.mkdir()
        subprocess.run(['git', 'init', '-b', 'release/test', str(source)], check=True, capture_output=True)
        (source / 'pom.xml').write_text('''<project><dependencies><dependency>
<groupId>org.example</groupId><artifactId>library</artifactId><version>1.0.0</version>
</dependency></dependencies></project>''')
        subprocess.run(['git', '-C', str(source), 'add', '.'], check=True, capture_output=True)
        subprocess.run(['git', '-C', str(source), '-c', 'user.name=Test', '-c', 'user.email=test@example.com',
                        'commit', '-m', 'fixture'], check=True, capture_output=True)

        class LocalCommands(r.Commands):
            def run(self, args, **kwargs):
                if args[:2] == ['git', 'clone']:
                    args = [*args[:-2], str(source), args[-1]]
                if args[:3] == ['git', 'ls-remote', '--heads']:
                    args = [*args[:3], str(source), args[-1]]
                return super().run(args, **kwargs)

        class FakeProject(r.Project):
            def build(self):
                if broken_build and '1.2.3' in (self.root / 'pom.xml').read_text():
                    raise r.JobError('Candidate tests failed')

            def resolve(self):
                version = '1.2.3' if '1.2.3' in (self.root / 'pom.xml').read_text() else '1.0.0'
                return [{'name': 'org.example:library', 'version': version}]

        class FakeOsv(r.Osv):
            def request(self, payload):
                if scan_error:
                    raise r.JobError('OSV offline')
                if 'queries' in payload:
                    return {'results': [{'vulns': [advisory()] if q['version'] == '1.0.0' else []}
                                        for q in payload['queries']]}
                return {'vulns': [advisory()] if payload['version'] == '1.0.0' else []}

        args = argparse.Namespace(repo='example/repo', branch='release/test', recipient=['dev@example.com'],
                                  publish=publish, output=str(directory / 'runs'), timeout=30, allow_major=False)
        code = r.execute(args, LocalCommands, FakeOsv, FakeProject)
        report_path = next((directory / 'runs').glob('*/report.json'))
        return code, json.loads(report_path.read_text()), report_path.parent

    def test_successful_preview_has_verified_patch_and_unchanged_source(self):
        with tempfile.TemporaryDirectory() as folder:
            code, report, directory = self.run_job(folder)
            self.assertEqual(code, 0)
            self.assertEqual(report['status'], 'fixed')
            self.assertEqual(report['branch'], 'release/test')
            self.assertEqual(report['after'], [])
            self.assertIn('1.2.3', (directory / 'changes.patch').read_text())
            self.assertIn('1.0.0', (Path(folder) / 'source/pom.xml').read_text())

    def test_failed_candidate_restores_original_and_reports_manual(self):
        with tempfile.TemporaryDirectory() as folder:
            code, report, directory = self.run_job(folder, broken_build=True)
            self.assertEqual(code, 2)
            self.assertEqual(report['status'], 'manual_required')
            self.assertIn('1.0.0', (directory / 'repo/pom.xml').read_text())
            self.assertFalse(report['fixes'])

    def test_api_outage_is_failure_not_zero_findings(self):
        with tempfile.TemporaryDirectory() as folder:
            code, report, _ = self.run_job(folder, scan_error=True)
            self.assertEqual(code, 1)
            self.assertEqual(report['status'], 'failed')
            self.assertIsNone(report['after'])

    def test_failed_email_keeps_pr_and_can_be_retried(self):
        with tempfile.TemporaryDirectory() as folder:
            def published(report, *args):
                report['pr_url'] = 'https://github.com/example/repo/pull/1'
            with patch.object(r, 'publish', side_effect=published), \
                 patch.object(r, 'notification_config'), \
                 patch.object(r, 'email_report', side_effect=OSError('offline')):
                code, report, _ = self.run_job(folder, publish=True)
            self.assertEqual(code, 1)
            self.assertEqual(report['notification'], 'failed')
            self.assertEqual(report['pr_url'], 'https://github.com/example/repo/pull/1')


if __name__ == '__main__':
    unittest.main()
