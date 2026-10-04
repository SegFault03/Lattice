#!/usr/bin/env python3
"""Retrieve the pinned upstream corresponding source distributed with releases."""
import argparse
import hashlib
from pathlib import Path
import urllib.request
from common import ROOT, cache_directory

NAME = "mysql-connector-j-9.0.0-source.tar.gz"
URL = "https://codeload.github.com/mysql/mysql-connector-j/tar.gz/refs/tags/9.0.0"
SHA256 = "f7b980c67063200f20a8611d57f33b51623b99ad0b852d110c7280c3a4c7b955"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=ROOT / "build/release")
    parser.add_argument("--cache", type=Path)
    args = parser.parse_args()
    cache_root = args.cache or cache_directory() / "sources"
    source = cache_root / NAME
    if source and source.is_file():
        content = source.read_bytes()
    else:
        with urllib.request.urlopen(URL, timeout=60) as response:
            content = response.read(32 * 1024 * 1024 + 1)
    if len(content) > 32 * 1024 * 1024 or hashlib.sha256(content).hexdigest() != SHA256:
        raise ValueError("Upstream source archive failed checksum/size validation")
    if not source.exists():
        cache_root.mkdir(parents=True, exist_ok=True)
        source.write_bytes(content)
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / NAME).write_bytes(content)
    print(f"Prepared checksum-verified {NAME}")


if __name__ == "__main__":
    main()
