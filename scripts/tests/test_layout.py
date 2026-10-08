"""Keep repository documentation links and GitHub entry points intact."""
from pathlib import Path
import re
import unittest
from urllib.parse import unquote, urlsplit


ROOT = Path(__file__).resolve().parents[2]


def documentation_target_exists(document, target):
    parsed = urlsplit(target)
    if parsed.scheme or parsed.netloc or not parsed.path:
        return True
    destination = (document.parent / unquote(parsed.path)).resolve()
    # Screenshots/reports are generated locally; fresh CI checkouts have no build outputs.
    # Resolve first so ../build/../docs/missing.md still fails as a broken source link.
    return destination.is_relative_to((ROOT / "build").resolve()) or destination.exists()


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
                with self.subTest(document=document.relative_to(ROOT), target=target):
                    self.assertTrue(documentation_target_exists(document, target))

    def test_generated_report_links_do_not_require_build_outputs_in_a_fresh_checkout(self):
        document = ROOT / "docs" / "report.md"
        self.assertTrue(documentation_target_exists(document, "../build/ui-review/never-generated.png"))
        self.assertTrue(documentation_target_exists(document, "SCRIPTS.md"))
        self.assertFalse(documentation_target_exists(document, "never-generated.md"))
        self.assertFalse(documentation_target_exists(document, "../build/../docs/never-generated.md"))


if __name__ == "__main__":
    unittest.main()
