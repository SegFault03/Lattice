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
    step = WORKFLOW.split("      - name: Upload signed ZIP to hidden smoke channel\n", 1)[1]
    return textwrap.dedent(step.split("        run: |\n", 1)[1].split("      - name:", 1)[0])


@unittest.skipUnless(BASH, "Bash required to exercise GitHub's upload step")
class MarketplaceWorkflowTests(unittest.TestCase):
    def run_upload(self, token="test-token", corrupt=False, transport_status=0):
        with tempfile.TemporaryDirectory(prefix="lattice-marketplace-test-") as directory:
            root = Path(directory)
            (root / "build/distributions").mkdir(parents=True)
            (root / "build/release").mkdir()
            (root / "bin").mkdir()
            archive = root / "build/distributions/Lattice-0.0.2-smoke.1.zip"
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
            env = dict(os.environ, MARKETPLACE_TOKEN=token, RELEASE_VERSION="0.0.2-smoke.1",
                       GITHUB_STEP_SUMMARY="summary.md", CURL_STATUS=str(transport_status))
            result = subprocess.run([BASH, "--noprofile", "--norc", "-e", "-o", "pipefail", "-c",
                                     'export PATH="$PWD/bin:$PATH"\n' + upload_script()],
                                    cwd=root, env=env, capture_output=True, text=True, timeout=30)
            def read(name):
                path = root / name
                return path.read_text() if path.exists() else None
            return result, read("curl-args.txt"), read("curl-header.txt"), read("summary.md")

    def test_upload_is_hidden_custom_channel_and_token_not_in_arguments(self):
        result, args, header, summary = self.run_upload()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("channel=smoke", args)
        self.assertIn("isHidden=true", args)
        self.assertIn("xmlId=com.segfault03.lattice", args)
        self.assertIn("file=@build/distributions/Lattice-0.0.2-smoke.1.zip", args)
        self.assertNotIn("test-token", args)
        self.assertIn("Authorization: Bearer test-token", header)
        self.assertIn("No GitHub Release was created", summary)

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

    def test_smoke_submission_requires_all_release_gates_and_manual_opt_in(self):
        job = WORKFLOW.split("  marketplace-smoke:\n", 1)[1]
        self.assertIn("needs: [build, cross-platform-checks, verify, database-compatibility]", job)
        self.assertIn("if: github.event_name == 'workflow_dispatch' && inputs.marketplace_smoke", job)
        self.assertNotIn("gh release", job)
        publish = WORKFLOW.split("  publish:\n", 1)[1].split("  marketplace-smoke:\n", 1)[0]
        self.assertIn("github.event_name == 'push'", publish)
