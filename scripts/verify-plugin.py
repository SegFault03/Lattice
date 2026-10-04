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
from common import ROOT, cache_directory, java_command

VERIFIER_SHA256 = "59a5ef05cbdf0584cbfd6cb6ca802c74ecf340fdeadc5a73eac24af622c22010"
VERIFIER_URL = "https://github.com/JetBrains/intellij-plugin-verifier/releases/download/1.410/verifier-cli-1.410-all.jar"
SDK_LAYOUT = "Checksum-verified SDK extraction, layout 2\n"


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def ide_home(cache, version):
    directory = cache / "ides" / version
    for candidate in [directory] + list((cache / "ides").glob(version + "-*")):
        if (candidate / ".complete").exists() and (candidate / ".complete").read_text(encoding="utf-8") == SDK_LAYOUT:
            existing = list(candidate.rglob("product-info.json"))
            if len(existing) != 1:
                raise ValueError("Ambiguous IntelliJ SDK directory")
            return existing[0].parent
    with urllib.request.urlopen("https://data.services.jetbrains.com/products/releases?code=IIC&type=release", timeout=60) as response:
        releases = json.load(response)["IIC"]
    release = next(item for item in releases if item["version"] == version)
    # SDK bytecode verification works on every host using the official Linux
    # archive; no IDE executable from that archive is launched here.
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
    # A partial extraction must never be reused as a complete SDK.
    if directory.exists():
        directory = directory.with_name(version + "-" + uuid.uuid4().hex)
    directory.mkdir(parents=True)
    # Python's data filter rejects path traversal and unsafe link targets.
    with tarfile.open(archive) as bundle:
        def safe_sdk_member(member, destination):
            # Keep regular runtime files: product-info references them when the
            # verifier validates the IDE layout. Skip links for Windows portability.
            if member.issym() or member.islnk():
                return None
            # Preserve data_filter's None directory mode; assigning 0644 here
            # removes directory search permission on Linux/macOS.
            return tarfile.data_filter(member, destination)
        bundle.extractall(directory, filter=safe_sdk_member)
    existing = list(directory.rglob("product-info.json"))
    if len(existing) != 1:
        raise ValueError("Official SDK has no unique product-info.json")
    (directory / ".complete").write_text(SDK_LAYOUT, encoding="utf-8")
    return existing[0].parent


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("archive", type=Path)
    parser.add_argument("--ide-version", choices=("2025.1", "2025.2", "2025.3"), default="2025.1")
    parser.add_argument("--ide-home", type=Path)
    parser.add_argument("--java-home", type=Path)
    parser.add_argument("--cache", type=Path)
    parser.add_argument("--reports", type=Path, default=ROOT / "build/compatibility/verifier-ci")
    args = parser.parse_args()
    if not args.archive.is_file():
        raise ValueError("Plugin archive does not exist")
    cache = args.cache or cache_directory()
    sdk = args.ide_home or ide_home(cache, args.ide_version)
    java = java_command(args.java_home)
    java_home = args.java_home or (Path(os.environ["JAVA_HOME"]) if os.environ.get("JAVA_HOME") else None)
    verifier = cache / "tools" / "verifier" / "verifier-cli-1.410-all.jar"
    if not verifier.exists():
        verifier.parent.mkdir(parents=True, exist_ok=True)
        urllib.request.urlretrieve(VERIFIER_URL, verifier)
    if digest(verifier) != VERIFIER_SHA256:
        raise ValueError("Plugin Verifier checksum mismatch")
    reports = args.reports / uuid.uuid4().hex
    # The verifier recreates its report directory during startup. Keep scratch
    # outside it so initialization cannot delete the live plugin repository.
    work = args.reports.parent / "verifier-work" / reports.name
    command = [java, "-Xmx2g", f"-Dplugin.verifier.home.dir={work.resolve()}",
                    "-jar", str(verifier), "check-plugin", str(args.archive.resolve()), str(sdk.resolve()),
                    "-verification-reports-dir", str(reports.resolve()), "-offline"]
    if java_home:
        command += ["-runtime-dir", str(java_home.resolve())]
    subprocess.run(command, check=True)
    verdicts = list(reports.rglob("verification-verdict.txt"))
    if len(verdicts) != 1 or verdicts[0].read_text(encoding="utf-8").strip() != "Compatible":
        raise ValueError("Plugin verification did not produce a Compatible verdict")
    if list(reports.rglob("compatibility-problems.txt")):
        raise ValueError("Plugin Verifier reported compatibility problems")


if __name__ == "__main__":
    main()
