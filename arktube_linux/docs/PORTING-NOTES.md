# Porting notes: `../ARKtube` (Neutralino) → `arktube_linux` (native GTK)

Tracks where each piece of the old shell's logic ended up, so nothing
gets silently dropped as the port continues.

## Ported

| Old (`../ARKtube`) | New (`arktube_linux`) | Notes |
| --- | --- | --- |
| `neutralino.config.json` `url` → `youtube.com/tv` | `src/main.c` `ARKTUBE_URL` | Loaded directly; no local shell page to bounce off of first. |
| `neutralino.config.json` `chrome.args` UA string | `src/main.c` `ARKTUBE_USER_AGENT` via `webkit_settings_set_user_agent()` | Full replacement natively; the old window-mode path could only *extend* its UA (`extendUserAgentWith`). |
| `app-init.js` `onKeyDown()` F11 branch + `toggleFullScreen()` | `src/main.c` `on_window_key_press()` | Native `gtk_window_fullscreen()`/`unfullscreen()`; no `Neutralino.window.*` round-trip needed. |
| `app-init.js` `onKeyDown()` Escape branch + `exitFullScreenIfActive()` | `src/main.c` `on_window_key_press()` | Same as F11: still only exits fullscreen, never quits. |
| `app-init.js` `goHome()` + Home-key handling | `resources/js/user-script.js` `goHome()`/`onKeyDown()` | Unchanged logic; still just a hash-route change. |
| `app-init.js` gamepad/remote → keyboard remap (`GAMEPAD_BUTTON_TO_KEY`, stick fallback, repeat timing, poll loop) | `resources/js/user-script.js` | Copied essentially verbatim -- it was already pure page-side JS with no Neutralino dependency. |
| `app-init.js` cursor auto-hide | `resources/js/user-script.js` `initCursorAutoHide()` | Same 10s timeout. The old comment about Wayland needing this done *inside* the webview (rather than a session-level daemon like `unclutter`) applies even more directly here, since this **is** the webview's own process. |
| `onWindowClose()` / `safeExit()` | `src/main.c` `on_window_delete()` | Trivial now: one process, so "close the window" and "quit the app" are the same GTK `gtk_main_quit()` call. No `exitProcessOnClose` flag, no separate cleanup-then-exit sequencing needed. |
| `resources/icons/appIcon.png` | `resources/icons/appIcon.png` | Copied as-is. |
| `packaging/linux/ARKtube.desktop` | `packaging/arktube-linux.desktop` | Re-pointed at the new binary name; otherwise the same shape. |

## New in the port (had no equivalent in `../ARKtube`)

| What | Where | Why it's needed |
| --- | --- | --- |
| `navigator.userAgent`/`appVersion`/`platform`/`vendor`/`maxTouchPoints` and `screen.width`/`height`/`availWidth`/`availHeight`/`devicePixelRatio` spoof | `resources/js/user-script.js`, `spoofProperty()` | The old shell relied on the UA **header** alone (`extendUserAgentWith` in window mode, or a full `--user-agent` flag in chrome mode). That's necessary but not sufficient: youtube.com/tv's own bootstrap JS also reads these `navigator.*`/`screen.*` values directly, and a plain WebKitGTK view reports ordinary desktop values there (mouse-capable, arbitrary window size) no matter what the UA header claims. Every TV-mode wrapper that reliably gets the Leanback UI (VacuumTube, youtube-tv-ua-spoof, the multitheftauto/mtasa-blue `"; SMART-TV; Tizen 4.0"` workaround) pairs a UA string with exactly this kind of JS-level patch. Defined on `Navigator.prototype`/`Screen.prototype`/`Window.prototype`, not the instances, so a same-origin integrity check via `Object.getOwnPropertyDescriptor` still finds a prototype getter rather than a suspicious own-property shadow -- see the comment above `spoofProperty()`. |

Containerizing the process (Docker or otherwise) was considered and
explicitly rejected as a mechanism for this: a container changes what
the *process* can reach on the host (filesystem, network, other
processes), not what the *page's JavaScript* can observe about its own
runtime. `navigator.userAgent`, `screen.width`, `maxTouchPoints`, and
every other signal above come from WebKitGTK's own JS engine and DOM
implementation, which behaves identically whether or not the process
sits inside a namespace/cgroup sandbox. If UA-header-only spoofing
weren't enough running natively, it wouldn't become enough running
under Docker either -- the fix has to happen at the same layer the
detection happens at (page JS), which is what `user-script.js` now
does.

## Deliberately not ported (yet)

These depended on a Neutralino API (`Neutralino.os.setTray`,
`Neutralino.storage.*`) that has no 1:1 native equivalent -- porting
them means designing their native replacement, not copying code, so
they're left out of this first pass rather than stubbed out badly:

* **Tray icon / tray menu** (`setTray()`, `onTrayMenuItemClicked()`).
  Native options are AppIndicator/`libayatana-appindicator` (Wayland-
  friendly, but an extra dependency) or a plain `GtkStatusIcon`
  (X11-only, and deprecated upstream). Needs a decision before it's
  worth writing.
* **Immersive Mode** (`insertImmersiveButton()`,
  `toggleImmersiveMode()`, the devtools-shortcut guard). The old
  version's *real* enforcement was Chrome launch flags applied by
  `AppRun`/the `.deb` launcher on the *next* start, driven by a
  preference read off disk -- the in-session part was always
  best-effort. Porting this well means picking where the preference
  lives (a `GKeyFile` under `g_get_user_config_dir()` is the obvious
  native fit) and deciding what "locked down" even means for a webview
  that's already just one native window, not a separate detached
  browser process to harden.
* **Persisted settings generally** (`Neutralino.storage.getData` /
  `setData`). Same `GKeyFile` note as above -- there's currently
  nothing in `arktube_linux` that needs to remember anything across
  runs yet, so this is tracked here rather than added speculatively.
* **On-screen fullscreen button** (`insertFullscreenButton()`). It
  existed for "anyone without a keyboard/remote handy" on a webview
  where the *host app* couldn't otherwise show any of its own chrome
  over YouTube. A native GTK window still has real window-manager
  decorations (or a `libhandy`/header-bar mode if this ever goes
  client-side-decorations) to fall back on, so this needs a fresh
  decision, not a straight copy.
* **`docs/bugs-caught` X11-reparenting-specific fixes**
  (`embed-chrome.sh`, the `--native-fallback` argument, the
  `CHROME_LOCK_PATTERN` stale-singleton-lock cleanup in `AppRun`).
  These don't apply here at all -- there is no second Chrome process
  to spawn, reparent, or clean up after in this architecture, so
  they're not "not yet ported", they're simply obsolete for this
  directory.

## Explicitly out of scope for this directory

* Windows and macOS. `../ARKtube` (Neutralino) remains the
  cross-platform shell; `arktube_linux` is Linux-only by design (see
  `README.md`'s "Why" section) and isn't meant to grow other-OS
  support.
