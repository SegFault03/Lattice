"""Keep repository documentation links and GitHub entry points intact."""
from pathlib import Path
import re
import unittest
from urllib.parse import unquote, urlsplit


ROOT = Path(__file__).resolve().parents[2]


class LayoutTests(unittest.TestCase):
    def test_guides_are_under_docs(self):
        for name in ("CHANGELOG.md", "CONTRIBUTING.md", "ISSUE.md", "RELEASING.md",
                     "SECURITY.md", "THIRD_PARTY_NOTICES.md"):
            with self.subTest(name=name):
                self.assertTrue((ROOT / "docs" / name).is_file())
                self.assertFalse((ROOT / name).exists())
        self.assertTrue((ROOT / "docs/SCRIPTS.md").is_file())
        self.assertFalse((ROOT / "scripts/README.md").exists())

    def test_github_and_root_entry_points_exist(self):
        for name in ("README.md", "LICENSE", "gradlew", "gradlew.bat",
                     ".github/pull_request_template.md", ".github/ISSUE_TEMPLATE/bug_report.yml",
                     ".github/ISSUE_TEMPLATE/feature_request.yml", ".github/workflows/ci.yml",
                     ".github/workflows/release.yml"):
            with self.subTest(name=name):
                self.assertTrue((ROOT / name).is_file())

    def test_public_documentation_links_resolve(self):
        documents = [ROOT / "README.md", *sorted((ROOT / "docs").glob("*.md")),
                     *sorted((ROOT / ".github").glob("*.md"))]
        for document in documents:
            contents = document.read_text(encoding="utf-8")
            targets = re.findall(r"\]\(([^\s)]+)\)", contents)
            targets += re.findall(r'<img\b[^>]*\bsrc="([^"]+)"', contents)
            for target in targets:
                parsed = urlsplit(target)
                if parsed.scheme or parsed.netloc or not parsed.path:
                    continue
                with self.subTest(document=document.relative_to(ROOT), target=target):
                    self.assertTrue((document.parent / unquote(parsed.path)).exists())


if __name__ == "__main__":
    unittest.main()
