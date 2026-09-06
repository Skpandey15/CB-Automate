"""Opt-in Gradle + live OSV test. Never calls GitHub or SMTP."""
import argparse
import json
import os
from pathlib import Path
import shutil
import tempfile
import unittest

import remediate as r


@unittest.skipUnless(os.environ.get('CB_RUN_NETWORK_TESTS') == '1',
                     'Set CB_RUN_NETWORK_TESTS=1 for a real Gradle/OSV integration test')
class NetworkIntegrationTests(unittest.TestCase):
    def test_real_gradle_upgrade_and_rescan_on_release_branch(self):
        repository = Path(__file__).resolve().parents[2]
        with tempfile.TemporaryDirectory(prefix='cb-network-') as directory:
            parent = Path(directory)
            source = parent / 'source'
            source.mkdir()
            shutil.copy(repository / 'gradlew', source / 'gradlew')
            shutil.copy(repository / 'gradlew.bat', source / 'gradlew.bat')
            shutil.copytree(repository / 'gradle/wrapper', source / 'gradle/wrapper')
            (source / 'settings.gradle').write_text("rootProject.name = 'cb-network-fixture'\n")
            (source / 'build.gradle').write_text("""plugins { id 'java' }
repositories { mavenCentral() }
dependencies {
    implementation 'org.apache.commons:commons-text:1.9'
    testImplementation 'junit:junit:4.13.2'
}
""")
            test = source / 'src/test/java/LibraryTest.java'
            test.parent.mkdir(parents=True)
            test.write_text('''import org.junit.Test;
import static org.junit.Assert.assertEquals;
import org.apache.commons.text.StringEscapeUtils;
public class LibraryTest {
    @Test public void preservesEscapingBehavior() {
        assertEquals("&lt;hello&gt;", StringEscapeUtils.escapeHtml4("<hello>"));
    }
}
''')
            (source / '.gitignore').write_text('.gradle/\nbuild/\n')
            setup = r.Commands(parent / 'setup-logs', 300)
            setup.run(['git', 'init', '-b', 'release/fixture'], cwd=source)
            setup.run(['git', 'add', '.'], cwd=source)
            setup.run(['git', '-c', 'user.name=Test', '-c', 'user.email=test@example.com',
                       'commit', '-m', 'Known vulnerable fixture'], cwd=source)

            class LocalClone(r.Commands):
                def run(self, args, **kwargs):
                    if args[:2] == ['git', 'clone']:
                        args = [*args[:-2], str(source), args[-1]]
                    if args[:3] == ['git', 'ls-remote', '--heads']:
                        args = [*args[:3], str(source), args[-1]]
                    return super().run(args, **kwargs)

            args = argparse.Namespace(repo='fixture/library', branch='release/fixture',
                                      recipient=[], publish=False, output=str(parent / 'runs'),
                                      timeout=300, allow_major=False)
            code = r.execute(args, commands_factory=LocalClone)
            report_path = next((parent / 'runs').glob('*/report.json'))
            report = json.loads(report_path.read_text())
            # Retain reviewable evidence without retaining another source checkout.
            evidence = repository / '.cb-runs/network-evidence'
            evidence.mkdir(parents=True, exist_ok=True)
            r.persist(report, evidence)
            if code == 1:
                for log in sorted((report_path.parent / 'logs').glob('*.log')):
                    print(log.read_text(encoding='utf-8', errors='replace')[-3000:])
            self.assertIn(code, (0, 2), report.get('error'))
            self.assertTrue(any(f['id'] == 'GHSA-599f-7c49-w659' for f in report['before']))
            self.assertFalse(any(f['name'] == 'org.apache.commons:commons-text' for f in report['after']))
            self.assertTrue(any(f['name'] == 'org.apache.commons:commons-text' for f in report['fixes']))
            self.assertEqual(report['branch'], 'release/fixture')
            self.assertIsNone(report.get('pr_url'))


if __name__ == '__main__':
    unittest.main()
