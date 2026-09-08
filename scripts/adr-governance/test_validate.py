import json
from pathlib import Path
import subprocess
import tempfile
import unittest

from validate import (GitRepository, ValidationError, decision_only_changes, main, validate_pr,
                      validate_repository)


BODY = '''Architecture-Impact: yes
Architecture-Decision: ADR-0013
Implementation-Scope: Require Accepted ADR references for architectural changes.
Migration-Impact: No runtime or data migration.
Rollback: Revert tooling and retain historical decisions.
Fitness-Functions: Unit and local Git integration tests passed.
'''

ADR_ONLY_BODY = BODY.replace('Architecture-Decision: ADR-0013', 'Architecture-Decision: none').replace(
    'Implementation-Scope: Require Accepted ADR references for architectural changes.',
    'Implementation-Scope: ADR-only decision record; no implementation.')


class DeclarationTests(unittest.TestCase):
    def test_accepted_reference_passes(self):
        self.assertIn('Accepted', validate_pr(BODY, [], {'ADR-0013'}))

    def test_ordinary_pr_does_not_require_adr(self):
        self.assertIn('Non-architectural', validate_pr('Architecture-Impact: no\nFix a typo', [], set()))

    def test_unclassified_old_pr_is_nonblocking_even_with_architecture_words(self):
        self.assertIn('NOTICE', validate_pr('Fix spelling of Kafka in Java authorization docs', [], set()))

    def test_label_overrides_no_declaration(self):
        with self.assertRaises(ValidationError):
            validate_pr('Architecture-Impact: no', ['architecture-impacting'], set())

    def test_label_can_classify_without_impact_field(self):
        validate_pr(BODY.replace('Architecture-Impact: yes\n', ''), ['architecture-impacting'], {'ADR-0013'})

    def test_unknown_or_proposed_reference_fails(self):
        for value in ('ADR-0007', 'ADR-XXXX', 'none', ''):
            with self.subTest(value=value), self.assertRaises(ValidationError):
                validate_pr(BODY.replace('ADR-0013', value), [], {'ADR-0013'})

    def test_every_multiple_reference_must_be_accepted(self):
        body = BODY.replace('ADR-0013', 'ADR-0013, ADR-0007')
        with self.assertRaises(ValidationError):
            validate_pr(body, [], {'ADR-0013'})
        validate_pr(body, [], {'ADR-0013', 'ADR-0007'})

    def test_missing_or_placeholder_fields_fail(self):
        for key in ('Implementation-Scope', 'Migration-Impact', 'Rollback', 'Fitness-Functions'):
            with self.subTest(key=key):
                lines = BODY.splitlines()
                with self.assertRaises(ValidationError):
                    validate_pr('\n'.join(line for line in lines if not line.startswith(key + ':')), [], {'ADR-0013'})
                with self.assertRaises(ValidationError):
                    validate_pr('\n'.join(key + ': <fill this>' if line.startswith(key + ':') else line
                                          for line in lines), [], {'ADR-0013'})

    def test_conflicting_and_invalid_declarations_fail(self):
        for body in (BODY + '\nArchitecture-Impact: no', BODY.replace('Impact: yes', 'Impact: maybe'),
                     BODY + '\nArchitecture-Decision: ADR-0007'):
            with self.subTest(body=body), self.assertRaises(ValidationError):
                validate_pr(body, [], {'ADR-0013'})

    def test_examples_and_comments_do_not_classify_pr(self):
        for body in ('```text\n' + BODY + '```', '<!--\n' + BODY + '-->', '~~~\n' + BODY + '~~~'):
            self.assertIn('NOTICE', validate_pr(body, [], set()))

    def test_embedded_commands_are_treated_as_plain_data(self):
        validate_pr(BODY.replace('No runtime or data migration.', '$(touch /tmp/pwned); no migration'),
                    [], {'ADR-0013'})


class GitHistoryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.git('init', '-b', 'main')
        self.git('config', 'user.name', 'Governance Test')
        self.git('config', 'user.email', 'test@example.com')
        self.write('docs/adr/0001-original.md', '# ADR 0001: Original\n\nStatus: Accepted for implementation\n\nOriginal decision.\n')
        self.write('docs/adr/template.md', '# ADR-XXXX: Title\n\nStatus: Proposed\n')
        self.write('docs/adr/README.md', '[Original](0001-original.md)\n[Template](template.md)\n')
        self.base = self.commit()
        self.repo = GitRepository(self.root)

    def git(self, *args):
        result = subprocess.run(['git', *args], cwd=self.root, capture_output=True, check=True)
        return result.stdout.decode().strip()

    def write(self, path, content):
        file = self.root / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(content, encoding='utf-8')

    def commit(self):
        self.git('add', '.')
        self.git('commit', '--allow-empty', '-m', 'Test revision')
        return self.git('rev-parse', 'HEAD')

    def add_adr(self, status='Accepted', path='docs/adr/0013-governance.md'):
        self.write(path, '# ADR-0013: Governance\n\nStatus: ' + status + '\n\nSupersedes: none\n')
        with (self.root / 'docs/adr/README.md').open('a', encoding='utf-8') as index:
            index.write('[Governance](' + Path(path).name + ')\n')

    def test_existing_legacy_adr_is_recognized_without_edits(self):
        self.assertEqual(validate_repository(self.repo, self.base, self.base), {'ADR-0001'})

    def test_implementation_references_adr_already_accepted_in_base(self):
        self.add_adr()
        base = self.commit()
        self.write('example.java', '// subsequent implementation')
        accepted = validate_repository(self.repo, base, self.commit())
        validate_pr(BODY, [], accepted)

    def test_implementation_cannot_self_authorize_with_head_accepted_adr(self):
        self.add_adr()
        self.write('example.java', '// implementation in the same PR')
        accepted = validate_repository(self.repo, self.base, self.commit())
        self.assertNotIn('ADR-0013', accepted)
        with self.assertRaisesRegex(ValidationError, 'Accepted in BASE'):
            validate_pr(BODY, [], accepted)

    def test_proposed_adr_can_be_documented_but_cannot_govern_implementation(self):
        self.add_adr('Proposed')
        self.write('example.java', '// implementation')
        accepted = validate_repository(self.repo, self.base, self.commit())
        with self.assertRaises(ValidationError):
            validate_pr(BODY, [], accepted)

    def test_adr_only_pr_can_introduce_proposed_decision(self):
        self.add_adr('Proposed')
        head = self.commit()
        accepted = validate_repository(self.repo, self.base, head)
        self.assertIn('ADR-only', validate_pr(ADR_ONLY_BODY, [], accepted,
                          decision_only_diff=decision_only_changes(self.repo, self.base, head)))

    def test_adr_only_pr_can_introduce_human_reviewed_accepted_decision(self):
        self.add_adr()
        head = self.commit()
        accepted = validate_repository(self.repo, self.base, head)
        self.assertNotIn('ADR-0013', accepted)
        self.assertIn('ADR-only', validate_pr(ADR_ONLY_BODY, [], accepted,
                          decision_only_diff=decision_only_changes(self.repo, self.base, head)))

    def test_promoting_base_proposed_to_head_accepted_does_not_authorize_implementation(self):
        self.add_adr('Proposed')
        base = self.commit()
        path = self.root / 'docs/adr/0013-governance.md'
        path.write_text(path.read_text().replace('Status: Proposed', 'Status: Accepted'))
        self.write('example.java', '// implementation')
        accepted = validate_repository(self.repo, base, self.commit())
        with self.assertRaisesRegex(ValidationError, 'Accepted in BASE'):
            validate_pr(BODY, [], accepted)

    def test_adr_only_declaration_cannot_hide_implementation_files(self):
        self.add_adr()
        for path in ('example.java', '.github/workflows/check.yml', 'scripts/check.py', 'docs/execute.sh'):
            self.write(path, 'implementation')
            head = self.commit()
            accepted = validate_repository(self.repo, self.base, head)
            with self.subTest(path=path), self.assertRaisesRegex(ValidationError, 'documentation diff'):
                validate_pr(ADR_ONLY_BODY, [], accepted,
                            decision_only_diff=decision_only_changes(self.repo, self.base, head))
            (self.root / path).unlink()

    def test_adr_only_exemption_requires_an_actual_decision_change(self):
        self.write('README.md', 'Documentation only, no decision')
        head = self.commit()
        self.assertFalse(decision_only_changes(self.repo, self.base, head))

    def test_adr_only_exemption_checks_deleted_implementation_files(self):
        self.write('example.java', '// implementation')
        base = self.commit()
        (self.root / 'example.java').unlink()
        self.add_adr()
        self.assertFalse(decision_only_changes(self.repo, base, self.commit()))

    def test_adr_only_exemption_checks_source_of_rename_into_docs(self):
        self.write('example.java', '// implementation')
        base = self.commit()
        (self.root / 'example.java').rename(self.root / 'docs/example.md')
        self.add_adr()
        self.assertFalse(decision_only_changes(self.repo, base, self.commit()))

    def test_adr_only_exemption_rejects_executable_document(self):
        self.add_adr()
        self.commit()
        self.git('update-index', '--chmod=+x', 'docs/adr/0013-governance.md')
        self.git('commit', '-m', 'Executable document')
        self.assertFalse(decision_only_changes(self.repo, self.base, self.git('rev-parse', 'HEAD')))

    def test_invalid_status_fails_head_validation(self):
        self.add_adr('Automatically approved')
        with self.assertRaisesRegex(ValidationError, 'status'):
            validate_repository(self.repo, self.base, self.commit())

    def test_editing_accepted_decision_fails_even_for_unclassified_pr(self):
        with (self.root / 'docs/adr/0001-original.md').open('a') as file:
            file.write('Rewritten decision.')
        with self.assertRaisesRegex(ValidationError, 'immutable'):
            validate_repository(self.repo, self.base, self.commit())

    def test_deleting_accepted_decision_fails(self):
        (self.root / 'docs/adr/0001-original.md').unlink()
        with self.assertRaisesRegex(ValidationError, 'immutable'):
            validate_repository(self.repo, self.base, self.commit())

    def test_renaming_accepted_decision_fails(self):
        (self.root / 'docs/adr/0001-original.md').rename(self.root / 'docs/adr/0001-renamed.md')
        with self.assertRaisesRegex(ValidationError, 'immutable'):
            validate_repository(self.repo, self.base, self.commit())

    def test_superseding_in_new_record_preserves_history(self):
        self.add_adr()
        path = self.root / 'docs/adr/0013-governance.md'
        path.write_text(path.read_text().replace('Supersedes: none', 'Supersedes: ADR-0001'))
        validate_repository(self.repo, self.base, self.commit())

    def test_duplicate_identifier_fails(self):
        self.add_adr()
        self.add_adr(path='docs/adr/0013-duplicate.md')
        with self.assertRaisesRegex(ValidationError, 'Duplicate'):
            validate_repository(self.repo, self.base, self.commit())

    def test_broken_local_index_link_fails(self):
        with (self.root / 'docs/adr/README.md').open('a') as file:
            file.write('[Missing](0099-missing.md)')
        with self.assertRaisesRegex(ValidationError, 'Broken'):
            validate_repository(self.repo, self.base, self.commit())

    def test_unindexed_adr_fails(self):
        self.write('docs/adr/0013-governance.md', '# ADR-0013: Governance\n\nStatus: Proposed\n')
        with self.assertRaisesRegex(ValidationError, 'Unindexed'):
            validate_repository(self.repo, self.base, self.commit())

    def test_heading_must_match_filename(self):
        self.add_adr()
        self.write('docs/adr/0013-governance.md', '# ADR-0007: Wrong ID\n\nStatus: Accepted\n')
        with self.assertRaisesRegex(ValidationError, 'heading'):
            validate_repository(self.repo, self.base, self.commit())

    def test_cli_reads_pr_event_without_executing_body(self):
        self.add_adr()
        base = self.commit()
        self.write('example.java', '// implementation')
        head = self.commit()
        event = self.root / 'event.json'
        event.write_text(json.dumps({'pull_request': {'body': BODY, 'labels': []}}))
        self.assertEqual(main(['--repo', str(self.root), '--base', base,
                               '--head', head, '--event', str(event)]), 0)

    def test_cli_fails_actual_pr_event_with_new_head_accepted_governing_adr(self):
        self.add_adr()
        self.write('scripts/validator.py', '# governance implementation')
        head = self.commit()
        event = self.root / 'event.json'
        event.write_text(json.dumps({'pull_request': {'body': BODY, 'labels': []}}))
        self.assertEqual(main(['--repo', str(self.root), '--base', self.base,
                               '--head', head, '--event', str(event)]), 1)

    def test_cli_allows_adr_only_event(self):
        self.add_adr()
        head = self.commit()
        event = self.root / 'event.json'
        event.write_text(json.dumps({'pull_request': {'body': ADR_ONLY_BODY, 'labels': []}}))
        self.assertEqual(main(['--repo', str(self.root), '--base', self.base,
                               '--head', head, '--event', str(event)]), 0)

    def test_cli_fails_on_invalid_revision_or_event(self):
        self.assertEqual(main(['--repo', str(self.root), '--base', 'not-a-ref']), 1)
        event = self.root / 'event.json'
        event.write_text('{}')
        self.assertEqual(main(['--repo', str(self.root), '--base', self.base, '--event', str(event)]), 1)


if __name__ == '__main__':
    unittest.main()
