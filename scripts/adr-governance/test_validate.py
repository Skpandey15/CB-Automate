import json
from pathlib import Path
import subprocess
import tempfile
import unittest

from validate import (GitRepository, ValidationError, main, validate_pr,
                      validate_repository)


BODY = '''Architecture-Impact: yes
Architecture-Decision: ADR-0013
Implementation-Scope: Require Accepted ADR references for architectural changes.
Migration-Impact: No runtime or data migration.
Rollback: Revert tooling and retain historical decisions.
Fitness-Functions: Unit and local Git integration tests passed.
'''


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

    def test_new_accepted_adr_is_allowed_in_same_pr(self):
        self.add_adr()
        accepted = validate_repository(self.repo, self.base, self.commit())
        validate_pr(BODY, [], accepted)

    def test_proposed_adr_can_be_documented_but_cannot_govern_implementation(self):
        self.add_adr('Proposed')
        accepted = validate_repository(self.repo, self.base, self.commit())
        with self.assertRaises(ValidationError):
            validate_pr(BODY, [], accepted)

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
        head = self.commit()
        event = self.root / 'event.json'
        event.write_text(json.dumps({'pull_request': {'body': BODY, 'labels': []}}))
        self.assertEqual(main(['--repo', str(self.root), '--base', self.base,
                               '--head', head, '--event', str(event)]), 0)

    def test_cli_fails_on_invalid_revision_or_event(self):
        self.assertEqual(main(['--repo', str(self.root), '--base', 'not-a-ref']), 1)
        event = self.root / 'event.json'
        event.write_text('{}')
        self.assertEqual(main(['--repo', str(self.root), '--base', self.base, '--event', str(event)]), 1)


if __name__ == '__main__':
    unittest.main()
