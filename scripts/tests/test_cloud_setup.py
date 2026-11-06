"""The Gradle mirror init script in the cloud setup script matches the one documented, and keeps Central out."""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def setup_init_script():
    text = (ROOT / 'scripts/linux/cloud-setup.sh').read_text(encoding='utf-8')
    match = re.search(r"mirror\.gradle\.kts\" <<'EOF'\n(.*?)\nEOF\n", text, re.S)
    assert match, 'cloud-setup.sh no longer writes the init script with a quoted heredoc'
    return match.group(1)


def documented_init_script():
    text = (ROOT / 'docs/CLOUD_BUILDS.md').read_text(encoding='utf-8')
    match = re.search(r"```kotlin\n(.*?)\n```", text, re.S)
    assert match, 'docs/CLOUD_BUILDS.md has no kotlin init script block'
    return match.group(1)


class CloudSetupTest(unittest.TestCase):
    def test_setup_writes_the_documented_init_script(self):
        def code(script):
            # Comments may differ in wording between the two; the code may not.
            return [line.split('//')[0].rstrip() for line in script.splitlines() if line.split('//')[0].strip()]
        self.assertEqual(code(documented_init_script()), code(setup_init_script()))

    def test_central_is_removed_again_after_the_build_script_runs(self):
        script = setup_init_script()
        after = script[script.index('afterProject {'):]
        self.assertIn('repositories.removeAll', after)
        self.assertIn('repo.maven.apache.org', after)


if __name__ == '__main__':
    unittest.main()
