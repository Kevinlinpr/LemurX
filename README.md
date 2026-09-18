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

## The user always wins

Depth of customisation is only worth anything if it never costs the user
control of the browser. Three guarantees back that:

* **Stock when idle.** Every hook LemurX adds to Chromium (URL-loader
  throttles, navigation throttles, certificate override, custom schemes, back
  press, app menu, tab observers, renderer Lua) is gated on state that only a
  script can create. With no script running, each hook is an early `return`
  and the browser behaves like the official build it was compiled from.
* **A native master switch.** The three-dot menu always ends with **Lua
  scripts**: a plain Android dialog (no Lua involved, scripts cannot hide or
  intercept it) with an on/off switch for the runtime and a checkbox per
  script — the bundled tutorial, your local scripts, and `ugc/` scripts alike.
  Applying a change tears down everything scripts did — net rules, attached
  webviews, certificate whitelist, CDP sessions, schemes, timers, overlays,
  widgets, skin, per-tab UA/headers — stops the Lua engine
  (`LemurXEngine::Stop`), and recreates the activity so the shell is inflated
  from stock resources. Turning it back on starts a fresh `lua_State` and
  re-runs the scripts. The switch lives in its own preference file that no
  Lua API can reach.
* **Boot-loop protection.** If the process dies twice in a row within 20 s of
  starting scripts, the runtime is disabled automatically and the browser
  comes up stock with a notice; a script can never lock you out of the
  browser you need to fix it.

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
  chrome/lemurx/brand/                 Android branding: launcher icons, app_name, logo drawables
  chrome/lemurx/discover/              strings for the NTP Discover news stream
  chrome/app/theme/lemurx/             BRANDING + product logos (branding_path_component)
  components/resources/*/lemurx/       chrome://version logo
  components/vector_icons/lemurx/      product.icon (QR code centre, etc.)
  third_party/lua/                     Lua 5.4.7
patches/           unified diffs for the handful of upstream files we hook into
tools/apply.py     lays src/ + patches/ over chromium/src
tools/args.gn      default GN args (arm64, official, enable_extensions=false)
tools/brand/       master logo + gen_brand_assets.py (regenerates everything above)
```

Everything LemurX adds lives in `src/` and `patches/`; the Chromium tree is
pristine upstream at the pinned tag. There is no fork.

### Branding

The shipped app is branded LemurX end to end without editing any upstream
resource file:

- `branding_path_component = "lemurx"` (in `tools/args.gn`) points Chromium's
  own branding switch at `src/chrome/app/theme/lemurx/` (BRANDING → product
  name/company in version info, `product_logo_*.png`, svg) and
  `src/components/vector_icons/lemurx/product*.icon`.
- `//chrome/lemurx/brand:brand_resources` is an `android_resources` target with
  `resource_overlay = true`: aapt2 takes its launcher icons, `app_name` and every
  drawable that upstream draws the Chrome logo with (`chrome_logo_24dp`,
  `chrome_sync_logo`, `chromelogo16`, `chrome_logo_blue`, promo illustrations, …)
  instead of the upstream resources of the same name.
- `patches/tools_grit_grit_node_message.py.patch` rewrites "Chromium" /
  "Chrome" / "Google Chrome" to "LemurX" in every emitted UI string, in every
  language, at grit output time. Message ids are untouched so translations keep
  matching; ChromeOS, Chromebook, Chromecast, Chrome Web Store, Chrome
  Enterprise and lowercase `chrome://` URLs are left alone. The user agent is
  unaffected (it is not a grit string).
- Code identifiers (`org.chromium.*`, `chrome/` paths, `lemurx.chrome.*` Lua API)
  keep their names; they are not user-visible.

Regenerate all assets from the master logo with
`python3 tools/brand/gen_brand_assets.py` (needs Pillow), then `tools/apply.py`.

### Discover (new tab page news)

Chromium's Discover feed renders through the proprietary xsurface library,
which is only a stub in public builds, so upstream can never show anything but
"can't refresh". LemurX keeps the whole upstream surface — the "Discover"
header, its on/off switch, the `ARTICLES_LIST_VISIBLE` pref — and swaps only the
content stream: `FeedSurfaceCoordinator.createFeedStream()` returns
`LemurXDiscoverStream` (`src/chrome/android/java/.../lemurx/`) instead of
`FeedStream`.

- Data: the Lemur news service, `GET {base}/lemur/news/meta` (country / language
  / category targets) and `GET {base}/lemur/news/headlines?country&lang&category&page&pageSize`
  (50 per page, auto-loads the next page near the bottom).
- Look: the Lemur Discover card — title (16sp, max 3 lines) beside a 98×76dp
  12dp-rounded thumbnail, "source · time" in 10sp below, 16dp between cards.
  Views are plain Android widgets fed to Chromium's `FeedListContentManager`
  as `NativeViewContent`, so the NTP scroll, header and thumbnail capture all
  behave as upstream.
- Base URL: `--lemurx-discover-url=…` > `lemurx_settings` key
  `discover.base_url` > built-in default (currently the debug service
  `http://192.168.1.111:18888/`; the production host is `RELEASE_BASE_URL` in
  the same file). `discover.enabled=false` in `lemurx_settings` restores the
  upstream `FeedStream`.
- Independent of Lua: the stream does not go through the Lua runtime, so it is
  unaffected by the master switch; scripts that want to own the home page do so
  with `lemurx.skin` / `lemurx.ui` as before.

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
