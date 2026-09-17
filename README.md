# LemurX

**Official Chromium + embedded Lua. No extension system.**

LemurX is an Android browser built directly on the official Chromium stable
channel. It removes the WebExtensions API entirely and replaces it with a single,
much deeper extension surface: **Lua 5.4 embedded in the browser process, in
every renderer process, and in the native Android shell.**

The bet is simple. Browser extensions are a sandboxed guest; they see what the
host chooses to show them. A script in LemurX *is* the host. It can veto a
navigation before it commits, rewrite every subresource request synchronously
inside the renderer, hold real main-world DOM handles, register its own URL
schemes, drive the full DevTools Protocol without a cable, and replace the
native shell with a widget tree it draws itself.

## What a script can do

The full reference lives in `src/chrome/lemurx/lua/docs/LUA_GUIDE.md`.

* **`lemurx.*`** — tabs, network rules, HTTP, files, storage, shell, declarative
  native UI, native view surgery, input, cookies, history, prefs, the whole
  Chrome DevTools Protocol.
* **luakit compatibility layer** — the complete C-level Lua API of
  [luakit](https://luakit.github.io/) reimplemented on Chromium: `widget{}` trees
  mapped to Android views, `webview` bound to a Tab, `luakit.register_scheme`,
  `sqlite3`, `regex`, `soup`, `stylesheet`, `timer`, `download`, `ipc_channel`.
  luakit's own `lib/`, `lousy/` and `rc.lua` run unmodified on a phone.
* **Lua in the renderer** — one `lua_State` per renderer process with `page`,
  `dom_document`, `dom_element` on real V8 handles, a synchronous `send-request`
  hook on every subresource, `luakit.register_function` to expose Lua to page JS.

Two trust domains: scripts you put on your own device run with full power;
scripts you install from others (`files/lua/ugc/`) run in their own `lua_State`
with a reduced standard library and no privileged APIs. The boundary is enforced
at runtime, per state, not by source inspection.

## Layout

```
CHROMIUM_VERSION   pinned official Chromium release
chromium/          .gclient + fetch.sh; the checkout itself is not tracked
src/               overlay — files that do not exist upstream, mirrored at the same paths
  chrome/browser/lemurx/               browser-process Lua: net rules, throttle, cookies
  chrome/browser/ui/android/lemurx/    Lua engine, lemurx.* API, CDP, luakit natives
  chrome/renderer/lemurx/              renderer-embedded Lua (luakit web extension model)
  chrome/common/lemurx_web.mojom       browser <-> renderer Lua IPC
  chrome/android/java/.../lemurx/      Java hosts (shell, UI, widgets, moat)
  chrome/lemurx/lua/                   init.lua, tutorial, examples, docs
  chrome/lemurx/luakit/                luakit kernel (BSD) + luakit lib/ (GPLv3, verbatim)
  third_party/lua/                     Lua 5.4.7
patches/           unified diffs for the handful of upstream files we hook into
tools/apply.py     lays src/ + patches/ over chromium/src
tools/args.gn      default GN args (arm64, official, enable_extensions=false)
```

Everything LemurX adds lives in `src/` and `patches/`; the Chromium tree is
pristine upstream at the pinned tag. There is no fork.

## Build

```sh
./chromium/fetch.sh                      # shallow checkout of the pinned release (Android)
tools/apply.py                           # overlay + patches
cd chromium/src
gn gen out/lemurx --args="$(cat ../../tools/args.gn)"
autoninja -C out/lemurx chrome_public_apk
```

## Upgrading Chromium

1. Change `CHROMIUM_VERSION` and the `@version` in `chromium/.gclient`.
2. `tools/apply.py --revert && ./chromium/fetch.sh && tools/apply.py`
3. Fix whatever in `patches/` no longer applies (they are deliberately tiny), rebuild.

## License

LemurX code is BSD-3-Clause (`LICENSE`). Third-party notices in `NOTICE`; the luakit
Lua libraries are GPLv3 and shipped as separate source files.
