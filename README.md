<p align="center">
  <img src="docs/images/logo.png" alt="Aero" width="120">
</p>

<h1 align="center">Aero</h1>

<p align="center">
  <strong>Personal Agent Browser.</strong><br>
  Official Chromium · Android · one tab, one agent · no extension system
</p>

<p align="center">
  <a href="https://kevinlinpr.github.io/LemurX/">Website</a>
  ·
  <a href="LICENSE">BSD-3-Clause</a>
  ·
  <a href="CONTRIBUTING.md">Contributing</a>
  ·
  <a href="SECURITY.md">Security</a>
</p>

Aero is an Android browser built on the official Chromium stable channel
whose primary surface is a conversation with a personal agent. Every tab is
bound to one agent thread: a new tab is a new conversation, and the pages the
agent opens, reads and operates are ordinary Chromium tabs on the same phone,
visible to the user at all times.

The agent itself (OpenMuse) runs on a server you control. Aero contributes
the two things a server cannot have: a real browser with the user's sessions,
and the user's eyes and hands on the result.

## What Aero adds to Chromium

* **Console shell** (`LemurXConsoleShell`, `lemurx/muse/*`) — native
  `chrome-native://lemurx/...` pages for chat, activity and personal data, a
  bottom bar floating over every page, and the rule that the new tab page *is*
  the agent conversation (`LemurXChromeHost.newTabUrlOverride`).
* **Agent device bridge** (`lemurx/agent/*`) — turns this install into the
  agent's browser worker. The phone long-polls `GET /lemurx/device/poll` on the
  OpenMuse server, executes browser-worker REST commands locally
  (`POST /sessions`, `navigate`, `read`, `elements`, `screenshot`, `input`, `close`) on
  real tabs, and replies with `POST /lemurx/device/reply`. The page is never
  injected into and no DevTools domain is attached: text and clickable
  elements come from a one-shot accessibility-tree snapshot
  (`lemurx_ax.cc` ↔ `LemurXAx` / `LemurXPage`), screenshots are a compositor
  copy of the web viewport, and every click / scroll / pinch is a real
  touchscreen `MotionEvent`. Native dialogs (`<select>`, date/time, JS
  `alert`/`confirm`/`prompt`, the long-press menu) are reported on `read`
  and answered through Chromium's own controllers (`LemurXDialogs`).
* **Google sign-in for the Aero account** (`LemurXGoogleSignIn`), first-run
  and startup-promo suppression, Aero branding, the Lemur Discover news
  stream and the favorites grid on the new tab page.

There is no scripting runtime and no WebExtensions API. Everything above is
plain Java/C++ on top of upstream Chromium, wired in through a handful of
small patches.

## The user always wins

* **Visible, interruptible.** Pages the agent opens are foreground tabs by
  default; a banner on the operated tab says so and has a **Stop** button that
  cuts the bridge for the rest of the session. While the bridge is connected
  an ongoing notification is shown.
* **Only the web.** The bridge navigates `http(s)` URLs only; `chrome://`,
  `chrome-native://`, `file://` and the console pages themselves are never
  operated. Page text is data: it is handed to the server as-is, never
  interpreted on the device.
* **No secrets in the tree.** The device token lives in `assets/lemurx/device.json`
  (empty in git, injected at packaging time) or in app preferences; it is never
  logged and never reaches a page.
* **Stock when idle.** Without a device token the bridge does nothing; without
  the console shell enabled the browser behaves like the official build it was
  compiled from.

## Layout

```
CHROMIUM_VERSION   pinned official Chromium release
chromium/          .gclient + fetch.sh; the checkout itself is not tracked
src/               overlay — files that do not exist upstream, mirrored at the same paths
  chrome/browser/ui/android/lemurx/    AX-tree snapshot + compositor screenshot (lemurx_ax)
  chrome/android/java/.../lemurx/      console shell, Chromium hooks, Discover stream
  chrome/android/java/.../lemurx/agent/  agent device bridge (LemurXDeviceBridge / LemurXAx / LemurXPage / LemurXDialogs / human input)
  chrome/android/java/.../lemurx/muse/   native console pages: chat, activity, data, settings, sign-in
  chrome/android/java/.../lemurx/favorites/  new tab page favorites grid
  chrome/lemurx/agent/device.json      bridge config asset (server, empty token)
  chrome/lemurx/console/ muse/ search/ resources of the console shell and native pages
  chrome/lemurx/brand/                 Android branding: launcher icons, app_name, logo drawables
  chrome/lemurx/discover/              strings for the NTP Discover news stream
  chrome/lemurx/ntp/                   NTP overlay: Aero wordmark, Lemur search box, favorites UI
  chrome/app/theme/lemurx/             BRANDING + product logos (branding_path_component)
  components/resources/*/lemurx/       chrome://version logo
  components/vector_icons/lemurx/      product.icon (QR code centre, etc.)
patches/           unified diffs for the handful of upstream files we hook into
tools/apply.py     lays src/ + patches/ over chromium/src
tools/args.gn      default GN args (arm64, official, enable_extensions=false)
tools/brand/       master logo + gen_brand_assets.py (regenerates everything above)
```

Everything Aero adds lives in `src/` and `patches/`; the Chromium tree is
pristine upstream at the pinned tag. There is no fork.

### Branding

The shipped app is branded Aero end to end without editing any upstream
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
  "Chrome" / "Google Chrome" to "Aero" in every emitted UI string, in every
  language, at grit output time. Message ids are untouched so translations keep
  matching; ChromeOS, Chromebook, Chromecast, Chrome Web Store, Chrome
  Enterprise and lowercase `chrome://` URLs are left alone. The user agent is
  unaffected (it is not a grit string).
- Code identifiers (`org.chromium.*`, `chrome/` paths) keep their names; they
  are not user-visible.

Regenerate all assets from the master logo with
`python3 tools/brand/gen_brand_assets.py` (needs Pillow), then `tools/apply.py`.

### Discover (new tab page news)

Chromium's Discover feed renders through the proprietary xsurface library,
which is only a stub in public builds, so upstream can never show anything but
"can't refresh". Aero keeps the whole upstream surface — the "Discover"
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
  `discover.base_url` > `https://api.lemurbrowser.com/`.
  `discover.enabled=false` in `lemurx_settings` restores the upstream
  `FeedStream`.
- Independent of the agent service: the stream works even when the OpenMuse
  server is unreachable.

### New tab page: logo, search box, favorites

The NTP keeps upstream's structure (`NewTabPageLayout`, `LogoCoordinator`,
`SearchBoxCoordinator`, the Discover header/feed below); only what is drawn in
each slot is Lemur's.

- Logo: `//chrome/lemurx/ntp:ntp_resources` (`resource_overlay = true`) replaces
  `ic_google_logo` with the Aero wordmark (`tools/brand/gen_ntp_logo.py`,
  tinted to the text colour, 42dp tall). `LogoMediator` /
  `NtpCustomizationUtils` are patched to always show the logo and never fetch a
  doodle, whatever the default search engine.
- Search box: same overlay swaps `home_surface_search_box_background` for
  Lemur's `Search_default` pill (48dp, 2dp outline in the text colour, no
  fill) and overrides the `fake_search_box_*` dimens / hint text appearance.
- Favorites: `NewTabPageCoordinator` mounts `LemurXFavoritesCoordinator`
  (`src/chrome/android/java/.../lemurx/favorites/`) where the Most Visited
  tiles used to be. It is Lemur's home-page bookmark grid: 5 per row; tap opens,
  tap a folder expands it in a panel; long-press enters edit mode (selection
  ring, delete badges, rename field) where dragging reorders, dropping one tile
  on another makes a folder and dragging past the folder panel un-nests; tapping
  empty space shows the "+" tile, which opens a bottom sheet listing bookmarks,
  history and a URL field.
  Storage is a small SQLite table (`lemurx_favorites.db`), seeded with the
  Bookmarks and History shortcuts. Favicons come from `LargeIconBridge` with a
  Lemur-style letter fallback.

### Agent device bridge

OpenMuse's `BrowserService` only speaks the browser-worker REST contract. The
phone is behind NAT and can only dial out, so the server keeps a small queue
(`apps/server/src/lemurx-bridge.ts`): worker calls are turned into commands,
the device fetches them with a 25 s long poll and posts the result back.

| Worker call | On the device |
|---|---|
| `POST /sessions {id,url}` / `…/navigate` | open or reuse the session's tab, load the URL (foreground by default) |
| `GET …/read` | accessibility-tree snapshot → `{url,title,text,truncated,elementCount,focus,pageScale,dialog?}` |
| `GET …/elements` | the same snapshot's interactive nodes, rectangles in the 1280×800 console canvas |
| `GET …/screenshot` | compositor copy of the web viewport, JPEG q70 (no agent overlay) |
| `POST …/input` | `click` / `text` accept a `target` (element id or visible text): the device re-snapshots, scrolls the node into view, pinches in if it is smaller than 18 dp, then taps the live rectangle. Raw `x,y` still works. `scroll` is a Bézier swipe (1/4 of the time it overshoots and corrects). `pinch {scale}` is a two-finger pinch. `text` is typed through the IME connection; `key` is a soft-keyboard `KeyEvent`. `type:"dialog"` answers the native dialog reported by `read`. |
| `POST …/close` | close the tab, mark the session closed |

Sessions survive restarts (`lemurx_agent` preferences); a tab the user closed
is reopened at its last URL. Config comes from app preferences, then
`assets/lemurx/device.json`, then `MuseApi.apiUrl()` for the server address.

Foreground only, user first. Every touch is drawn on screen
(`LemurXTouchOverlay`: aim ring before the finger lands, pressed dot, ripple on
lift, swipe trail) and the operated tab carries a banner with **Stop**. Typing
is visible too: the system IME is another process's window that nothing can
draw over, so while the agent types it is dismissed and `LemurXAgentKeyboard`
— an in-app soft keyboard (letters / symbols layers, Shift, a candidate chip
for CJK and anything else not on a key) — is shown instead, with the agent's
finger pressing each key and the text echoed in its strip; the characters
themselves still go through Chromium's InputConnection exactly as before. A
real finger on the screen (`ChromeTabbedActivity.dispatchTouchEvent`) cancels
the gesture in flight and answers `USER_ACTIVE` for the next 2.5 s. When the
window is not resumed the device answers `DEVICE_BACKGROUND`; the poll carries
`fg=0|1`, and the server (`lemurx-bridge.ts`, `LEMURX_FALLBACK_WORKER_URL`)
routes new sessions and navigations to the cloud worker until Aero is back on
screen. There is no DevTools session at all: the accessibility tree and the
compositor are the only readers, so the page cannot observe a debugger, a
`Runtime.enable`, or injected script. Inputs are paced (300–900 ms between
commands, a reading pause after `read` proportional to the text) so the
device, not the agent, looks like a person.

## Build

`chromium/fetch.sh` clones [depot_tools](https://commondatastorage.googleapis.com/chrome-infra-docs/flat/depot_tools/docs/html/depot_tools.html) into `depot_tools/` at the repo root if it is not already on `PATH` via `$DEPOT_TOOLS`.

```sh
./chromium/fetch.sh                      # shallow checkout of the pinned release (Android)
tools/apply.py                           # overlay + patches
cd chromium/src
gn gen out/lemurx --args="$(cat ../../tools/args.gn)"
autoninja -C out/lemurx chrome_public_apk
```

Default GN args are a **local** arm64 release with the royalty-free Chromium
codec set. The app is still branded Aero (`branding_path_component`).
`ffmpeg_branding` in Chromium names the *codec bundle*, not the product:

| GN | Meaning |
|---|---|
| `ffmpeg_branding = "Chromium"` (default) | VP8/VP9/AV1, Opus, Vorbis — fine to redistribute |
| `ffmpeg_branding = "Chrome"` + `proprietary_codecs = true` | also H.264/AAC — patent licenses required to ship an APK |
| `enable_widevine = true` | Google Widevine CDM — separate agreement |

To add proprietary codecs on a private distribution build (you are responsible
for licenses):

```sh
gn gen out/lemurx --args="$(cat ../../tools/args.gn) ffmpeg_branding=\"Chrome\" proprietary_codecs=true enable_widevine=true"
```

### Distributed build (optional REAPI)

Remote execution is off by default. If you run your own REAPI cluster
(Buildbarn, etc.):

1. Set `use_remoteexec = true` in GN args.
2. Uncomment `reapi_address`, `reapi_instance`, and `reapi_backend_config_path`
   in `chromium/.gclient`. The backend path must be **absolute** and should
   point at `tools/rbe/backend.star` in this repo.
3. `gclient runhooks` (or `configure_siso.py`) installs the backend config.

If the cluster is plaintext gRPC and does not implement
`google.longrunning.Operations`:

```sh
export RBE_service_no_security=true
autoninja -C out/lemurx -reapi_insecure -reapi_keep_exec_stream -remote_jobs 256 chrome_public_apk
```

## Upgrading Chromium

1. Change `CHROMIUM_VERSION` and the `@version` in `chromium/.gclient`.
2. `tools/apply.py --revert && ./chromium/fetch.sh && tools/apply.py`
3. Fix whatever in `patches/` no longer applies (they are deliberately tiny), rebuild.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Please read [SECURITY.md](SECURITY.md)
before reporting a vulnerability, and [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)
for community expectations.

## License

Everything in this repository is BSD-3-Clause (`LICENSE`). Third-party notices
(Chromium) are in `NOTICE`.

This overlay does not grant a license to Google Chrome trademarks, to
third-party extension names used as comparators in docs, or to patent rights
covering H.264/AAC if you enable `ffmpeg_branding = "Chrome"`.
