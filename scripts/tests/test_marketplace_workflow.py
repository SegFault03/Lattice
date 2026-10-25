"""Exercise the actual upload shell step with a fake transport, never Marketplace."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
BASH = shutil.which("bash") if os.name != "nt" else next(
    (str(path) for path in (Path("C:/Program Files/Git/bin/bash.exe"),
                           Path("C:/Program Files/Git/usr/bin/bash.exe")) if path.exists()), None)


def upload_script():
    step = WORKFLOW.split("      - name: Upload signed ZIP to Marketplace\n", 1)[1]
    return textwrap.dedent(step.split("        run: |\n", 1)[1].split("      - name:", 1)[0])


@unittest.skipUnless(BASH, "Bash required to exercise GitHub's upload step")
class MarketplaceWorkflowTests(unittest.TestCase):
    def run_upload(self, token="test-token", corrupt=False, transport_status=0,
                   channel="", hidden="false", version=None, errexit=True):
        version = version or ("0.0.2-smoke.1" if channel else "0.0.2-alpha")
        with tempfile.TemporaryDirectory(prefix="lattice-marketplace-test-") as directory:
            root = Path(directory)
            (root / "build/distributions").mkdir(parents=True)
            (root / "build/release").mkdir()
            (root / "bin").mkdir()
            archive = root / f"build/distributions/Lattice-{version}.zip"
            archive.write_bytes(b"signed candidate fixture")
            digest = hashlib.sha256(archive.read_bytes()).hexdigest()
            (root / "build/release/SHA256SUMS").write_bytes(
                (f"{digest}  {archive.name}\n" + "0" * 64 + "  corresponding-source.tar.gz\n").encode())
            if corrupt:
                archive.write_bytes(b"changed")
            curl = root / "bin/curl"
            curl.write_text('#!/bin/sh\ncat > curl-header.txt\nprintf "%s\\n" "$@" > curl-args.txt\n'
                            'printf \'{"id":123}\' > build/release/marketplace-upload.json\n'
                            'exit "${CURL_STATUS:-0}"\n', encoding="utf-8", newline="\n")
            curl.chmod(0o755)
            env = dict(os.environ, MARKETPLACE_TOKEN=token, RELEASE_VERSION=version,
                       MARKETPLACE_CHANNEL=channel, MARKETPLACE_HIDDEN=hidden,
                       GITHUB_STEP_SUMMARY="summary.md", CURL_STATUS=str(transport_status))
            result = subprocess.run([BASH, "--noprofile", "--norc", *(["-e"] if errexit else []), "-o", "pipefail", "-c",
                                     'export PATH="$PWD/bin:$PATH"\n' + upload_script()],
                                    cwd=root, env=env, capture_output=True, text=True, timeout=30)
            def read(name):
                path = root / name
                return path.read_text() if path.exists() else None
            return result, read("curl-args.txt"), read("curl-header.txt"), read("summary.md")

    def test_tagged_release_uses_public_stable_channel(self):
        result, args, header, summary = self.run_upload(channel="", hidden="false")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn("test-token", args)
        self.assertIn("Authorization: Bearer test-token", header)
        self.assertIn("xmlId=com.segfault03.lattice", args)
        self.assertIn("channel=\n", args)
        self.assertIn("isHidden=false", args)
        self.assertIn("file=@build/distributions/Lattice-0.0.2-alpha.zip", args)
        self.assertIn("channel `Stable`, with `isHidden=false`", summary)

    def test_invalid_channel_visibility_combination_prevents_upload(self):
        for options in ({"hidden": "true"}, {"channel": "smoke"}, {"channel": "unknown"}):
            with self.subTest(options=options):
                result, args, _, summary = self.run_upload(**options)
                self.assertNotEqual(0, result.returncode)
                self.assertIsNone(args)
                self.assertIsNone(summary)

    def test_invalid_uploads_stop_even_without_shell_errexit(self):
        # macOS Bash 3.2 does not apply -e to failed [[ ... ]] conditional commands.
        for options in ({"hidden": "true"}, {"channel": "smoke"}, {"channel": "unknown"}):
            with self.subTest(options=options):
                result, args, _, summary = self.run_upload(errexit=False, **options)
                self.assertEqual(1, result.returncode)
                self.assertIsNone(args)
                self.assertIsNone(summary)

    def test_missing_token_or_corrupt_candidate_prevents_upload(self):
        for options in ({"token": ""}, {"corrupt": True}):
            with self.subTest(options=options):
                result, args, _, summary = self.run_upload(**options)
                self.assertNotEqual(0, result.returncode)
                self.assertIsNone(args)
                self.assertIsNone(summary)

    def test_failed_transport_is_not_reported_as_success(self):
        result, args, _, summary = self.run_upload(transport_status=22)
        self.assertEqual(22, result.returncode)
        self.assertIsNotNone(args)
        self.assertIsNone(summary)

    def test_submission_requires_main_guard_build_checks_and_github_publish(self):
        job = WORKFLOW.split("  marketplace:\n", 1)[1]
        self.assertIn("needs: [release-ref, build, cross-platform-checks, publish]", job)
        self.assertNotIn("always()", job.split("    steps:", 1)[0])
        self.assertIn("github.event_name == 'push' && startsWith(github.ref, 'refs/tags/v')", job)
        self.assertIn("MARKETPLACE_CHANNEL: ''", job)
        self.assertIn("MARKETPLACE_HIDDEN: 'false'", job)
        self.assertNotIn("gh release", job)
        self.assertNotIn("workflow_dispatch", WORKFLOW)
