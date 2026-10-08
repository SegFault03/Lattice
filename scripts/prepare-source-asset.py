#!/usr/bin/env python3
"""Retrieve the pinned upstream corresponding source distributed with releases."""
import argparse
import hashlib
from pathlib import Path
import urllib.request
from common import ROOT, cache_directory

NAME = "mysql-connector-j-26.7.0-source.tar.gz"
URL = "https://codeload.github.com/mysql/mysql-connector-j/tar.gz/refs/tags/26.7.0"
SHA256 = "8c739fa84f8031bc14b3e6f5e2de27da2b8f6ec24480075e7393490758e5d1e5"


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
