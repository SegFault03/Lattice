#!/usr/bin/env python3
"""Prepare versioned release notes locally; never contacts or publishes to GitHub."""
import argparse
import html
import os
from pathlib import Path
import re
import subprocess

VERSION = re.compile(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\Z")


def validate_version(value):
    if not VERSION.fullmatch(value):
        raise ValueError("Release version must have the form 1.2.3")
    return value


def patch_notes(changelog, version):
    sections = {}
    current = None
    for line in changelog.splitlines():
        if line.startswith("## "):
            match = re.match(r"## \[([^]]+)\]", line)
            current = match.group(1) if match else None
            if current is not None:
                sections[current] = []
        elif current is not None:
            sections[current].append(line)
    for heading in (version, "Unreleased"):
        content = "\n".join(sections.get(heading, [])).strip()
        if content and any(line.lstrip().startswith("- ") for line in content.splitlines()):
            return content
    raise ValueError(f"Add patch notes for [{version}] or [Unreleased] in CHANGELOG.md")


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args], text=True, encoding="utf-8").strip()


def previous_tag(root, version):
    target = tuple(map(int, version.split(".")))
    candidates = []
    for tag in git(root, "tag", "--merged", "HEAD").splitlines():
        if tag.startswith("v") and VERSION.fullmatch(tag[1:]):
            number = tuple(map(int, tag[1:].split(".")))
            if number < target:
                candidates.append((number, tag))
    return max(candidates)[1] if candidates else None


def prepare(root, version, tag=None, repository=None, server="https://github.com"):
    validate_version(version)
    if tag:
        if tag != "v" + version:
            raise ValueError("Release tag must be v followed by the release version")
        if git(root, "rev-parse", f"{tag}^{{commit}}") != git(root, "rev-parse", "HEAD"):
            raise ValueError("The release tag must point to the commit being built")
    notes = patch_notes((root / "CHANGELOG.md").read_text(encoding="utf-8"), version)
    previous = previous_tag(root, version)
    commit_range = f"{previous}..HEAD" if previous else "HEAD"
    commits = git(root, "log", "--reverse", "--format=%H%x09%s", commit_range)
    history = []
    for commit in commits.splitlines():
        sha, subject = commit.split("\t", 1)
        # Commit subjects are displayed as text, never interpreted as shell commands.
        subject = html.escape(subject).replace("[", "\\[").replace("]", "\\]")
        link = f"[{sha[:7]}]({server}/{repository}/commit/{sha})" if repository else f"`{sha[:7]}`"
        history.append(f"- {subject} ({link})")
    body = f"# Lattice {version}\n\n## Patch notes\n\n{notes}\n\n## Commit history\n\n"
    body += "\n".join(history) if history else "No new commits since the previous release."
    if previous and repository:
        body += f"\n\n[Full comparison]({server}/{repository}/compare/{previous}...{tag or 'HEAD'})"
    return body + "\n", "<pre>" + html.escape(notes) + "</pre>\n"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--tag", default=os.environ.get("RELEASE_TAG") or None)
    parser.add_argument("--version", default=os.environ.get("RELEASE_INPUT_VERSION") or None)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.tag:
        if not args.tag.startswith("v"):
            raise ValueError("Release tag must have the form v1.2.3")
        version = validate_version(args.tag[1:])
        if args.version and args.version != version:
            raise ValueError("Tag and requested version disagree")
    elif args.version:
        version = validate_version(args.version)
    else:
        properties = (args.root / "gradle.properties").read_text(encoding="utf-8")
        version = validate_version(re.search(r"^pluginVersion=(.+)$", properties, re.M).group(1).strip())
    body, plugin_notes = prepare(args.root, version, args.tag, os.environ.get("GITHUB_REPOSITORY"), os.environ.get("GITHUB_SERVER_URL", "https://github.com"))
    output = args.output or args.root / "build/release"
    output.mkdir(parents=True, exist_ok=True)
    (output / "release-notes.md").write_text(body, encoding="utf-8")
    (output / "patch-notes.html").write_text(plugin_notes, encoding="utf-8")
    (output / "version.txt").write_text(version + "\n", encoding="utf-8")
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as stream:
            stream.write(f"version={version}\n")
    print(f"Prepared Lattice {version} release notes in {output}")


if __name__ == "__main__":
    main()
