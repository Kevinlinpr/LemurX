#!/usr/bin/env python3
"""Lay LemurX over a pristine Chromium checkout.

    tools/apply.py            # link src/** into chromium/src, apply patches/*.patch
    tools/apply.py --copy     # copy instead of symlink (for CI / archives)
    tools/apply.py --revert   # remove links and reverse the patches

Layout:
    src/         files that do not exist upstream (new dirs, third_party/lua, ...)
                 -> symlinked into chromium/src at the same relative path
    patches/     unified diffs against upstream files that already exist
                 (chrome_content_browser_client.cc, BUILD.gn hooks, ...)
                 -> applied with `git apply` inside chromium/src

Chromium version is pinned in CHROMIUM_VERSION and chromium/.gclient. Upgrading
LemurX to a new Chromium = bump the pin, re-fetch, re-run this script, fix
whatever patch no longer applies. That is the whole upgrade procedure.
"""
import argparse
import os
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OVERLAY = os.path.join(ROOT, "src")
PATCHES = os.path.join(ROOT, "patches")
CHROMIUM = os.path.join(ROOT, "chromium", "src")


def overlay_files():
    for base, _dirs, files in os.walk(OVERLAY):
        for f in files:
            full = os.path.join(base, f)
            yield full, os.path.relpath(full, OVERLAY)


def patch_files():
    return sorted(
        os.path.join(PATCHES, p) for p in os.listdir(PATCHES)
        if p.endswith(".patch")
    )


def link(copy: bool):
    n = 0
    for src, rel in overlay_files():
        dst = os.path.join(CHROMIUM, rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        if os.path.lexists(dst):
            if os.path.islink(dst) or copy:
                os.remove(dst)
            else:
                sys.exit(f"refusing to overwrite upstream file: {rel} (should be a patch, not an overlay file)")
        if copy:
            shutil.copy2(src, dst)
        else:
            os.symlink(src, dst)
        n += 1
    print(f"overlay: {n} files {'copied' if copy else 'linked'}")


def unlink():
    n = 0
    for _src, rel in overlay_files():
        dst = os.path.join(CHROMIUM, rel)
        if os.path.lexists(dst):
            os.remove(dst)
            n += 1
    # prune empty dirs we created
    for _src, rel in overlay_files():
        d = os.path.dirname(os.path.join(CHROMIUM, rel))
        while d.startswith(CHROMIUM) and d != CHROMIUM:
            try:
                os.rmdir(d)
            except OSError:
                break
            d = os.path.dirname(d)
    print(f"overlay: {n} files removed")


def git_apply(args):
    return subprocess.run(["git", "apply", *args], cwd=CHROMIUM, capture_output=True, text=True)


def apply_patches(reverse: bool):
    ok = True
    for p in (reversed(patch_files()) if reverse else patch_files()):
        name = os.path.basename(p)
        flags = ["--reverse"] if reverse else []
        # already applied?
        if not reverse and git_apply(["--check", "--reverse", p]).returncode == 0:
            print(f"patch: {name} (already applied)")
            continue
        r = git_apply(["--check", *flags, p])
        if r.returncode != 0:
            ok = False
            print(f"patch: {name} DOES NOT APPLY\n{r.stderr.strip()}")
            continue
        git_apply([*flags, p])
        print(f"patch: {name} {'reverted' if reverse else 'applied'}")
    return ok


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--copy", action="store_true")
    ap.add_argument("--revert", action="store_true")
    a = ap.parse_args()
    if not os.path.isdir(os.path.join(CHROMIUM, ".git")):
        sys.exit(f"no Chromium checkout at {CHROMIUM}; run chromium/fetch.sh first")
    if a.revert:
        apply_patches(reverse=True)
        unlink()
        return
    link(a.copy)
    if not apply_patches(reverse=False):
        sys.exit(1)


if __name__ == "__main__":
    main()
