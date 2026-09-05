# Immersive Mode

> **Historical note:** this describes the old Neutralino-based shell,
> which has since been removed from `main` and replaced by the native
> `arktube_linux/` app. Immersive Mode has not been ported to
> `arktube_linux/` yet — see that app's `README.md` "Not yet ported"
> list. Kept here as the design/implementation record for when it is.

**Status:** implemented in the old shell (Linux: `.deb` and AppImage
packaging).
**Scope:** `ARKtube/resources/js/app-init.js`, `ARKtube/neutralino.config.json`,
`ARKtube/packaging/linux/build-deb.sh`, `ARKtube/packaging/linux/AppRun`.
**Not yet done:** `ARKtube/packaging/windows/Launch-ARKtube.ps1` and
`ARKtube/packaging/macos/build-dmg.sh` need the equivalent read-the-flag-and-pick-
args logic added — see "What's not done yet" below. Flagging this rather
than quietly limiting scope, same reasoning `docs/STAGE-4-HARDENING.md`
(webtop branch) uses for its own open items.

## What problem this solves

Two things existed before this change, both with legitimate claims to
`F11`:

1. Chrome mode (`defaultMode: "chrome"` in `ARKtube/neutralino.config.json`) spawns
   a real, separate Chrome/Chromium process (`chrome.cpp`), which already
   has its own native, built-in `F11` fullscreen toggle.
2. `app-init.js`'s own `onKeyDown` *also* called `toggleFullScreen()` on
   `F11`.

Two independent handlers racing to answer the same physical key is a bug
waiting to surface (double-toggles, inconsistent state depending on which
handler's async work lands first), not a feature. On top of that,
`docs/STAGE-4-HARDENING.md` (webtop branch) already found — by reading
`chrome.cpp` and the actual shipped config, not assumptions — that chrome
mode ships with **no hardening flags at all**: a full Chrome window with
working `F12`/right-click Inspect devtools and a real GTK file picker is
what's on screen today. That doc explicitly scoped fixing it as a
`main`-side decision, not something the session/compositor layer below it
could reach.

## The fix: two owners, two things they can each actually enforce

**`F11` now belongs to Chrome alone.** `app-init.js`'s `onKeyDown` no
longer intercepts it. Chrome's own native handler is untouched, so `F11`
keeps working exactly as any Chrome window's `F11` always has — this is
what "remember F11 is a user-defined [Chrome] behavior" means in practice:
stop fighting Chrome for it.

**A new, separate concept — "Immersive Mode" — owns the actual
lockdown**, via its own dedicated on-screen button (top-left corner,
deliberately placed apart from the existing fullscreen button so the two
are never confused for one control) instead of being bound to any key
Chrome already owns. Clicking it:

1. Flips an in-memory on/off flag and updates the button's icon
   (🔓 off / 🔒 on).
2. Persists that flag via `Neutralino.storage.setData("immersiveMode", ...)`
   — Neutralino's own small sandboxed key/value store (a plain file at
   `<appData>/.storage/immersiveMode.neustorage`, per
   `api/storage/storage.cpp` upstream), **not** `youtube.com`'s own
   `localStorage`, which this app doesn't own and which YouTube's own
   settings UI could clear independently of anything ARKtube does.
3. If turning on: enters fullscreen immediately, using the same call the
   fullscreen button already makes.
4. Shows a message box explaining that devtools/kiosk hardening itself
   takes effect on the **next** launch, not this instant — see "Why this
   can't apply instantly" below.

## Why this can't apply instantly, and why that's the honest answer

Chrome mode's browser process is spawned once, by `chrome.cpp`, with
`args` baked into the command line at that moment
(`chromeCmd += " " + input["args"].get<string>();`). Nothing server-side
re-reads config or re-spawns Chrome mid-session. The only way to change
Chrome's own flags is a fresh process launch.

The obvious-looking shortcut — have `app-init.js` call
`Neutralino.app.restartProcess({args: "--chrome-args=..."})` itself — was
deliberately **not** taken. Client-side, `restartProcess()` is implemented
as `os.execCommand(...)` followed by `app.exit()`. `os.execCommand` is a
general-purpose "run this shell command" native call. `app-init.js` runs
inside the Chrome child, on `youtube.com/tv`'s own origin — a page this
project doesn't control and YouTube's own script shares. Allowlisting
`os.execCommand` for that page, just so this one button could relaunch
itself, would mean any script running in that origin gets arbitrary local
command execution. That is a categorically bigger hole than the narrow,
specific-methods-only `nativeAllowList` this project already maintains
(`app.exit`, `app.killProcess`, `debug.log`, `os.setTray`,
`os.showMessageBox`, `events.broadcast`, and now `storage.getData` /
`storage.setData` — each one a single fixed action, not an execution
primitive) is worth trading away for a convenience toggle. So it wasn't.

Instead, the split is:

* **`app-init.js` (untrusted origin) can:** flip its own button's state,
  persist a plain preference value via the sandboxed `storage` API, ask
  for fullscreen right now, and run a same-session, JS-level guard against
  the obvious keyboard/mouse paths into devtools (`F12`,
  `Ctrl/Cmd+Shift+I/J/C`, `Ctrl/Cmd+U`, and the right-click context menu —
  see `isDevToolsShortcut()` and the `contextmenu` listener). **This guard
  is explicitly a best-effort, same-session stand-in, not real security.**
  A page script can never truly disable a browser's own devtools; a
  keyboard shortcut this script doesn't happen to intercept, or DevTools
  opened some other way, still works. It exists only to avoid leaving an
  obviously unguarded shortcut sitting there for the gap between "button
  clicked" and "next relaunch" — nothing more is claimed for it.
* **The packaging launcher (trusted, local, not youtube.com's script)
  can:** read that same persisted file directly off disk — a launcher
  script reading a file it already has filesystem access to needs no new
  IPC surface at all — and decide, on ARKtube's behalf, which real
  `--chrome-args` to hand Neutralino for *this* launch, via the
  `--chrome-args=...` CLI override Neutralino's own `settings.cpp`
  already implements (`applyConfigOverride`, `"Priority: mode -> root ->
  null"` — a CLI override always wins over the embedded config value).
  This is the layer that can actually enforce something: when the
  persisted flag is `"1"`, both `ARKtube/packaging/linux/build-deb.sh`'s installed
  launcher and `ARKtube/packaging/linux/AppRun` now pass
  `--chrome-args="<user-agent> --kiosk --disable-dev-tools --disable-pinch --overscroll-history-navigation=0"`
  instead of the baseline args — `--kiosk` removes whatever chrome UI
  `--app=` mode still leaves reachable and forces fullscreen at the
  browser level itself, and `--disable-dev-tools` is the actual flag that
  closes off `F12` / Inspect / `chrome://inspect`.

Nothing here required loosening `nativeAllowList` beyond the two new
`storage.*` methods, and both of those are narrow, single-purpose,
sandboxed reads/writes of one small local file — not execution
primitives.

## Files changed

* `ARKtube/resources/js/app-init.js` — F11 no longer intercepted; new Immersive
  Mode button, persistence, and same-session guards.
* `ARKtube/neutralino.config.json` — added `storage.getData` / `storage.setData`
  to both the root `nativeAllowList` and `modes.chrome.nativeAllowList`
  (chrome mode's own list fully replaces the root list per
  `settings.cpp`'s `getOptionForCurrentMode` — "mode -> root -> null" —
  so it needed adding in both places, not just one).
* `ARKtube/packaging/linux/build-deb.sh` — the installed `/usr/bin/arktube`
  launcher now reads the persisted preference and picks `--chrome-args`
  accordingly.
* `ARKtube/packaging/linux/AppRun` — same logic, for the AppImage entry point.

## What's not done yet

* `ARKtube/packaging/windows/Launch-ARKtube.ps1` and `ARKtube/packaging/macos/build-dmg.sh`
  don't yet read the persisted flag or pass an equivalent
  `--chrome-args` override. The button and persistence already work
  identically on those platforms (`Neutralino.storage` isn't
  Linux-specific) — only the trusted-launcher half of the split is
  missing there. Windows' `--chrome-args` value would need `--kiosk
  --disable-dev-tools` translated to whatever the local Chrome/Edge
  binary accepts (same flags, in practice — both are Chromium), and the
  storage file path would be
  `%LOCALAPPDATA%\ARKtube\.storage\immersiveMode.neustorage` instead of
  the XDG path this doc assumes.
* No on-hardware click-through yet (same caveat every doc in this
  project already carries for anything touching a real display).
