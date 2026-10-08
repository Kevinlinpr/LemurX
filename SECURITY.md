# Security policy

## This is a powerful browser

LemurX embeds Lua in the browser process and in every renderer. Scripts you
install **on the device** (`init.lua`, local scripts, official scripts) run
with host privileges: they can see navigations, rewrite requests, hold
main-world DOM handles, drive DevTools, and draw native UI.

Treat a LemurX script like a native browser module, not like a Chrome
extension.

## Trust domains

| Location | Trust | Expectation |
|---|---|---|
| Bundled official scripts, `init.lua` | full | reviewed in this repo |
| User local scripts | full | the user chose to run them |
| `files/lua/ugc/` | reduced | own `lua_State`, smaller stdlib, no privileged APIs |

The UGC boundary is enforced at runtime per `lua_State`. If you find a way
for UGC Lua to call a privileged `lemurx.*` API, or to escape into the
browser-process state of a full-trust script, that is a vulnerability.

## Always-on user controls (not optional)

These must keep working even when scripts misbehave:

- Native **Lua scripts** activity and master switch (scripts must not be able
  to hide or intercept it).
- Boot-loop guard: two crashes within 20 s of starting scripts disables the
  runtime.
- `lemurx.system.*` AccessibilityService stays off until the user enables it
  in Android Settings, and must stop answering when the master switch is off.

Please do not send patches that weaken these.

## Reporting a vulnerability

**Do not file a public GitHub issue** for a vulnerability.

Email **kevinlinpr@gmail.com** with:

- LemurX commit (or Chromium pin from `CHROMIUM_VERSION`)
- What a script, page, or attacker can do that they should not
- A minimal Lua snippet or page if you have one (no full exploit chain
  required)

You should get an acknowledgement within a few days. Please give us time to
ship a fix before publishing details.

We are especially interested in:

- UGC / untrusted Lua reaching privileged APIs
- Scripts surviving or bypassing the master switch
- Renderer Lua reading another site's data without an explicit user script
- The accessibility service working while Lua is disabled, or enabling
  itself
- Remote code execution from a web page with no user-installed script
