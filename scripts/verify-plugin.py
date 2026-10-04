#!/usr/bin/env python3
"""Verify the exact release ZIP against a local or checksum-verified official SDK."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tarfile
import urllib.request
import uuid

VERIFIER_SHA256 = "59a5ef05cbdf0584cbfd6cb6ca802c74ecf340fdeadc5a73eac24af622c22010"
VERIFIER_URL = "https://github.com/JetBrains/intellij-plugin-verifier/releases/download/1.410/verifier-cli-1.410-all.jar"


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def ide_home(cache, version):
    directory = cache / "ides" / version
    existing = list(directory.rglob("product-info.json")) if directory.exists() else []
    if existing:
        if len(existing) != 1:
            raise ValueError("Ambiguous IntelliJ SDK directory")
        return existing[0].parent
    with urllib.request.urlopen("https://data.services.jetbrains.com/products/releases?code=IIC&type=release", timeout=60) as response:
        releases = json.load(response)["IIC"]
    release = next(item for item in releases if item["version"] == version)
    download = release["downloads"]["linux"]
    archive = cache / "archives" / "ides" / f"idea-{version}-linux.tar.gz"
    archive.parent.mkdir(parents=True, exist_ok=True)
    if not archive.exists():
        print(f"Downloading official IDEA {version} SDK", flush=True)
        urllib.request.urlretrieve(download["link"], archive)
    with urllib.request.urlopen(download["checksumLink"], timeout=60) as response:
        expected = response.read(1024).decode("ascii").split()[0].lower()
    if digest(archive) != expected:
        raise ValueError("IntelliJ SDK checksum mismatch")
    directory.mkdir(parents=True, exist_ok=True)
    # Python's data filter rejects path traversal and unsafe link targets.
    with tarfile.open(archive) as bundle:
        bundle.extractall(directory, filter="data")
    existing = list(directory.rglob("product-info.json"))
    if len(existing) != 1:
        raise ValueError("Official SDK has no unique product-info.json")
    return existing[0].parent


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("archive", type=Path)
    parser.add_argument("--ide-version", choices=("2025.1", "2025.2", "2025.3"), default="2025.1")
    parser.add_argument("--ide-home", type=Path)
    parser.add_argument("--java-home", type=Path)
    parser.add_argument("--cache", type=Path)
    parser.add_argument("--reports", type=Path, default=Path("build/compatibility/verifier-ci"))
    args = parser.parse_args()
    if not args.archive.is_file():
        raise ValueError("Plugin archive does not exist")
    cache = args.cache or Path(os.environ.get("LATTICE_TEST_BINARIES", str(Path(__file__).resolve().parents[2] / "intellij-extension-test-binaries")))
    sdk = args.ide_home or ide_home(cache, args.ide_version)
    java_home = args.java_home or Path(os.environ.get("JAVA_HOME", str(cache / "ides/2025.1/jbr")))
    java = java_home / "bin" / ("java.exe" if os.name == "nt" else "java")
    verifier = cache / "tools" / "verifier" / "verifier-cli-1.410-all.jar"
    if not verifier.exists():
        verifier.parent.mkdir(parents=True, exist_ok=True)
        urllib.request.urlretrieve(VERIFIER_URL, verifier)
    if digest(verifier) != VERIFIER_SHA256:
        raise ValueError("Plugin Verifier checksum mismatch")
    work = args.reports.parent / "verifier-work" / uuid.uuid4().hex
    subprocess.run([str(java), "-Xmx2g", f"-Dplugin.verifier.home.dir={work.resolve()}",
                    "-jar", str(verifier), "check-plugin", str(args.archive.resolve()), str(sdk.resolve()),
                    "-runtime-dir", str(java_home.resolve()), "-verification-reports-dir", str(args.reports.resolve()), "-offline"], check=True)
    verdicts = list(args.reports.rglob("verification-verdict.txt"))
    if len(verdicts) != 1 or verdicts[0].read_text(encoding="utf-8").strip() != "Compatible":
        raise ValueError("Plugin verification did not produce a Compatible verdict")
    if list(args.reports.rglob("compatibility-problems.txt")):
        raise ValueError("Plugin Verifier reported compatibility problems")


if __name__ == "__main__":
    main()
