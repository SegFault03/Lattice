#!/usr/bin/env python3
"""Retrieve the pinned upstream corresponding source distributed with releases."""
import argparse
import hashlib
from pathlib import Path
import urllib.request
from common import ROOT, cache_directory
from dependencies import Dependencies, add_dependency_options

NAME = "mysql-connector-j-26.7.0-source.tar.gz"
URL = "https://codeload.github.com/mysql/mysql-connector-j/tar.gz/refs/tags/26.7.0"
SHA256 = "8c739fa84f8031bc14b3e6f5e2de27da2b8f6ec24480075e7393490758e5d1e5"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=ROOT / "build/release")
    parser.add_argument("--cache", type=Path)
    parser.add_argument("--github-output", type=Path, help="Append the prepared filename to this Actions output file")
    parser.add_argument("--source-archive", type=Path, help="Existing checksum-pinned Connector/J 26.7.0 source archive")
    add_dependency_options(parser)
    args = parser.parse_args()
    with Dependencies(args) as deps:
        prepare(args, deps)


def prepare(args, deps):
    cache_root = args.cache if args.cache and not args.cleanup else deps.workspace() / "sources"
    cached_source = args.cache / NAME if args.cache and (args.cache / NAME).is_file() else None
    source = args.source_archive or cached_source or deps.existing("sources/" + NAME) or cache_root / NAME
    if args.source_archive and not source.is_file():
        raise ValueError("--source-archive does not exist")
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
    if args.github_output:
        with args.github_output.open("a", encoding="utf-8") as output:
            output.write(f"filename={NAME}\n")
    print(f"Prepared checksum-verified {NAME}")


if __name__ == "__main__":
    main()
