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
  The standard module set (`window`, `webview`, `modes`, `binds`, `lousy.*`,
  `follow`, `adblock`, `formfiller`, `session`, the `luakit://` chrome pages, …)
  is re-implemented clean-room, so an `rc.lua` written for luakit runs on a phone.
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
  chrome/lemurx/luakit/                luakit-compatible runtime: kernel/, lib/, lousy/, config/
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

### Distributed build (self-hosted REAPI)

`tools/args.gn` enables `use_remoteexec` / `use_siso`; `chromium/.gclient`
points Siso at the in-house Buildbarn cluster (`reapi_address`,
`reapi_backend_config_path = tools/rbe/backend.star`). `gclient runhooks`
(or `configure_siso.py` directly) installs the backend config. The cluster is
plaintext gRPC and does not implement `google.longrunning.Operations`, so run:

```sh
export RBE_service_no_security=true
autoninja -C out/lemurx -reapi_insecure -reapi_keep_exec_stream -remote_jobs 256 chrome_public_apk
```

Without a reachable cluster, `autoninja --offline` builds locally.

## Upgrading Chromium

1. Change `CHROMIUM_VERSION` and the `@version` in `chromium/.gclient`.
2. `tools/apply.py --revert && ./chromium/fetch.sh && tools/apply.py`
3. Fix whatever in `patches/` no longer applies (they are deliberately tiny), rebuild.

## License

Everything in this repository is BSD-3-Clause (`LICENSE`), including the
luakit-compatible Lua runtime, which is an independent re-implementation of
luakit's public API and contains no luakit code. Third-party notices (Chromium,
Lua, markdown.lua) are in `NOTICE`.
