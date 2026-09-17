#!/usr/bin/env python3
"""Regenerate patches/*.patch from the working tree of chromium/src.

    tools/export_patches.py

Every upstream file modified inside chromium/src becomes one patch named after
its path (chrome/browser/BUILD.gn -> patches/chrome_browser_BUILD.gn.patch).
Overlay files (symlinks into src/) are untracked upstream and are not part of
any patch. Stale patches for files that are no longer modified are deleted.

Workflow: edit the hook in chromium/src directly, build, then run this script
and commit patches/.
"""
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PATCHES = os.path.join(ROOT, "patches")
CHROMIUM = os.path.join(ROOT, "chromium", "src")


def git(*args):
    return subprocess.run(["git", *args], cwd=CHROMIUM, capture_output=True, text=True, check=True).stdout


def main():
    if not os.path.isdir(os.path.join(CHROMIUM, ".git")):
        sys.exit(f"no Chromium checkout at {CHROMIUM}")
    os.makedirs(PATCHES, exist_ok=True)
    modified = [l for l in git("diff", "--name-only").splitlines() if l.strip()]
    wanted = set()
    for path in modified:
        name = path.replace("/", "_") + ".patch"
        wanted.add(name)
        diff = git("diff", "--", path)
        with open(os.path.join(PATCHES, name), "w") as f:
            f.write(diff)
        print(f"patch: {name} ({diff.count(chr(10))} lines)")
    for stale in os.listdir(PATCHES):
        if stale.endswith(".patch") and stale not in wanted:
            os.remove(os.path.join(PATCHES, stale))
            print(f"patch: {stale} removed (file no longer modified)")
    print(f"{len(wanted)} patches")


if __name__ == "__main__":
    main()
