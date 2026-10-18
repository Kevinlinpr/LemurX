# Contributing to LemurX

Thanks for helping. This repository is an **overlay** on a pinned official
Chromium tag, not a Chromium fork. Please keep it that way.

## Ground rules

- Everything LemurX adds lives in `src/` (new files) or `patches/` (tiny
  unified diffs against files that already exist upstream).
- Do not copy Chromium sources into this repo. Do not vendor a checkout.
- Do not commit secrets, LAN addresses, absolute machine paths, keystores,
  APKs, or spreadsheets. See `.gitignore`.
- Match the surrounding comment language (many LemurX sources are commented
  in Chinese). Do not delete existing comments.
- Agent-facing code goes under `src/chrome/android/java/.../lemurx/agent/`;
  keep the browser-worker REST semantics in sync with OpenMuse's
  `lemurx-bridge.ts`.

## Build from a clone

See the **Build** section in [README.md](README.md). In short:

```sh
./chromium/fetch.sh
tools/apply.py
cd chromium/src
gn gen out/lemurx --args="$(cat ../../tools/args.gn)"
autoninja -C out/lemurx chrome_public_apk
```

`fetch.sh` will clone `depot_tools` into `./depot_tools` if needed. Override
with `DEPOT_TOOLS=/path/to/depot_tools`.

## Where to change what

| Kind of change | Put it in |
|---|---|
| New C++/Java/resources that Chromium does not have | `src/` at the same path they should appear under `chromium/src` |
| A hook into an existing Chromium file | a new or updated file in `patches/` |
| Default GN flags | `tools/args.gn` (keep remote exec and proprietary codecs **off**) |
| Branding assets | `tools/brand/`, then `python3 tools/brand/gen_brand_assets.py` |

After editing overlay files, re-run `tools/apply.py` on an already-fetched
tree (it replaces links). After editing patches, `--revert` then apply again,
or `git apply` inside `chromium/src`.

## Patches

Patches must be as small as possible: one hook, not a rewrite of the upstream
file. Name them after the upstream path, with `/` replaced by `_`, for
example `chrome_browser_chrome_content_browser_client.cc.patch`.

When Chromium is upgraded (`CHROMIUM_VERSION` + `chromium/.gclient`):

1. `tools/apply.py --revert`
2. `./chromium/fetch.sh`
3. `tools/apply.py`
4. Fix any patch that no longer applies, rebuild.

## Pull requests

1. One concern per PR when you can.
2. Describe *why*, not only what changed.
3. If you change what the agent can do on the device, say how the user stays
   in control (visible, interruptible, http(s) only).
4. Do not enable `use_remoteexec`, `ffmpeg_branding = "Chrome"`, or
   `proprietary_codecs` in the default `tools/args.gn`.

## Code of conduct

Participation is covered by [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).
