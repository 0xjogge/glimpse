#!/usr/bin/env python3
"""Validate Frem-style <feature>.<patch>-release tags and increasing version codes."""

import argparse
import json
import re
import subprocess

TAG_PATTERN = re.compile(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)-release")


def parse_version(tag):
    match = TAG_PATTERN.fullmatch(tag)
    if not match:
        raise ValueError("Expected <feature>.<patch>-release, for example 0.1-release")
    feature, patch = map(int, match.groups())
    if feature > 210_000 or patch >= 10_000 or not 1 <= feature * 10_000 + patch <= 2_100_000_000:
        raise ValueError("Version is outside Android's range")
    return feature, patch


def previous_release(tag, releases):
    current = parse_version(tag)
    stable = []
    for release in releases:
        if release["isDraft"] or release["isPrerelease"]:
            continue
        try:
            stable.append((parse_version(release["tagName"]), release["tagName"]))
        except ValueError:
            continue
    if not stable:
        return ""
    version, previous = max(stable)
    if current <= version:
        raise ValueError(f"{tag} must be newer than published release {previous}")
    return previous


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tag")
    parser.add_argument("--published", action="store_true")
    args = parser.parse_args()
    try:
        feature, patch = parse_version(args.tag)
        previous = ""
        if args.published:
            releases = json.loads(subprocess.check_output(["gh", "release", "list", "--limit", "1000", "--json", "tagName,isDraft,isPrerelease"], text=True))
            previous = previous_release(args.tag, releases)
            if previous:
                subprocess.run(["git", "merge-base", "--is-ancestor", previous, args.tag], check=True)
    except ValueError as error:
        parser.error(str(error))
    print(f"tag={args.tag}\nversion={feature}.{patch}\nversion_code={feature * 10_000 + patch}\nprevious_tag={previous}")


if __name__ == "__main__":
    main()
