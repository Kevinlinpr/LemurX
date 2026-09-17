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


def overlay_dirs():
    """All directories under src/, shallowest first."""
    for base, dirs, _files in os.walk(OVERLAY):
        for d in dirs:
            full = os.path.join(base, d)
            yield full, os.path.relpath(full, OVERLAY)


def patch_files():
    return sorted(
        os.path.join(PATCHES, p) for p in os.listdir(PATCHES)
        if p.endswith(".patch")
    )


def _link_one(src, dst, copy):
    if copy:
        if os.path.isdir(src):
            shutil.copytree(src, dst)
        else:
            shutil.copy2(src, dst)
    else:
        os.symlink(src, dst)


def link(copy: bool):
    """Link the overlay into the checkout.

    Directories that do not exist upstream (chrome/lemurx/, chrome/browser/lemurx/,
    third_party/lua/, ...) are linked as a whole. Files that land inside an
    existing upstream directory (chrome/common/lemurx_web.mojom, ...) are linked
    one by one. Whole-directory links matter for android_assets: Chromium's zip
    helper preserves symlinks *as symlinks*, so a per-file link would end up in
    the APK as 70 bytes of link text instead of the Lua source.
    """
    unlink(quiet=True)  # drop stale links from an earlier layout
    n_dirs = n_files = 0

    def visit(rel):
        nonlocal n_dirs, n_files
        src = os.path.join(OVERLAY, rel)
        dst = os.path.join(CHROMIUM, rel)
        if os.path.isdir(src):
            if not os.path.lexists(dst):
                _link_one(src, dst, copy)
                n_dirs += 1
                return
            if os.path.islink(dst):
                sys.exit(f"unexpected link left behind: {rel}")
            if not os.path.isdir(dst):
                sys.exit(f"refusing to overwrite upstream file with a directory: {rel}")
            for child in sorted(os.listdir(src)):
                visit(os.path.join(rel, child))
            return
        if os.path.lexists(dst):
            if os.path.islink(dst) or copy:
                os.remove(dst)
            else:
                sys.exit(f"refusing to overwrite upstream file: {rel} (should be a patch, not an overlay file)")
        _link_one(src, dst, copy)
        n_files += 1

    for child in sorted(os.listdir(OVERLAY)):
        visit(child)
    verb = "copied" if copy else "linked"
    print(f"overlay: {n_dirs} directories + {n_files} files {verb}")


def unlink(quiet: bool = False):
    n = 0
    # whole-directory links first (shallowest first, so children are not visited)
    skipped = []
    for _src, rel in overlay_dirs():
        if any(rel.startswith(s + os.sep) for s in skipped):
            continue
        dst = os.path.join(CHROMIUM, rel)
        if os.path.islink(dst):
            os.remove(dst)
            skipped.append(rel)
            n += 1
    for _src, rel in overlay_files():
        if any(rel.startswith(s + os.sep) for s in skipped):
            continue
        dst = os.path.join(CHROMIUM, rel)
        if os.path.islink(dst):
            os.remove(dst)
            n += 1
        elif os.path.isfile(dst) and not quiet:
            # --copy layout: only remove if identical to the overlay copy
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
    if not quiet:
        print(f"overlay: {n} entries removed")


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
