# ARKtube

## YouTube, as a desktop app.

A lightweight YouTube desktop client built with [Neutralinojs](https://neutralino.js.org).

It looks like YouTube.

It behaves like an app.

No redesign.
No replacement frontend.
Just YouTube, in a proper desktop window.

---

## The idea

YouTube is already a great product.

The desktop website just doesn't always feel like a desktop application.

This project keeps the familiar YouTube experience while giving it a persistent desktop shell.

```text
YouTube
   +
Neutralino
   =
YouTube, installed.
```

The goal is simple:

**Open it. Watch something. Keep browsing.**

---

## Why?

A desktop application should feel persistent.

Navigation shouldn't feel like throwing the entire application away and rebuilding it from scratch.

This project explores a different model:

```text
┌─────────────────────────────────────────┐
│                                         │
│              YouTube                    │
│                                         │
│   Home → Search → Video → Channel       │
│                                         │
│   persistent application context        │
│                                         │
└─────────────────────────────────────────┘
```

The YouTube experience stays familiar.

The application stays alive.

---

## Built with

* **Neutralinojs** — lightweight desktop shell
* **YouTube** — the interface and content experience
* **JavaScript** — the glue
* **Vue 3** — optional for future custom UI
* **Svelte** — possible future migration

Neutralinojs is used as the native application layer rather than bundling a complete browser runtime.

---

## What this is

**A desktop shell for YouTube.**

The first version intentionally does very little.

It should let YouTube remain YouTube.

That means keeping:

* the familiar interface
* the existing player
* search
* channels
* playlists
* subscriptions
* recommendations
* account functionality
* existing navigation patterns

The application layer focuses on desktop behavior.

---

## What this is not

This isn't:

* a YouTube redesign
* a Piped frontend
* a NewPipe client
* a custom recommendation engine
* an Electron clone
* a new video platform

There are already enough of those.

This project is interested in something narrower:

> **What if the YouTube website behaved like the desktop application it already looks like?**

---

## Status

🚧 **Experimental**

The first milestone is proving that YouTube can run reliably inside a Neutralino application while preserving the functionality users expect from the website.

Current priorities:

* [ ] Launch YouTube inside Neutralino
* [ ] Verify playback
* [ ] Verify authentication/session persistence
* [ ] Verify navigation
* [ ] Investigate navigation interception
* [ ] Preserve application state
* [ ] Preserve scroll state where possible
* [ ] Improve desktop window behavior
* [ ] Add native desktop integration
* [ ] Add application-level settings

The project will stay deliberately small until the underlying approach is proven.

---

## Design

The architecture starts with the smallest possible layer:

```text
┌─────────────────────────────┐
│        Neutralino           │
│                             │
│   ┌─────────────────────┐   │
│   │      WebView         │   │
│   │                     │   │
│   │       YouTube       │   │
│   │                     │   │
│   └─────────────────────┘   │
│                             │
│      Desktop integration    │
└─────────────────────────────┘
```

Over time, desktop-specific functionality can be introduced without replacing the YouTube experience.

The long-term architecture is intended to remain framework-independent.

---

## Development

### Requirements

* Node.js
* npm
* Neutralinojs CLI

Install the CLI:

```bash
npm install -g @neutralinojs/neu
```

Neutralino provides an official `neu` CLI for creating, running, and building applications.

### Run

```bash
neu run
```

### Build

```bash
neu build
```

Neutralino's build process is intentionally lightweight and does not require bundling an entire Chromium runtime into the application.

### Linux: building the AppImage

```bash
cd ARKtube/
neu update                       # fetch the pinned 6.8.0 binaries
./packaging/linux/build-appimage.sh
```

This produces a self-contained `ARKtube-x86_64.AppImage` that embeds its
resources and ships an `AppRun` wrapper. Don't hand-wrap the raw
`neutralino-linux_x64` binary in an AppImage yourself — see
`docs/BUGS-CAUGHT.md` for why that reliably crashes on launch, and what
the wrapper does about it.

### Linux: building the .deb

```bash
cd ARKtube/
neu update                       # fetch the pinned 6.8.0 binaries
./packaging/linux/build-deb.sh
```

Produces `ARKtube-<version>-amd64.deb`, installable with
`sudo apt install ./ARKtube-<version>-amd64.deb`. It installs a launcher
at `/usr/bin/arktube`, a `.desktop` entry, and an icon, and points
Neutralino's writable `.tmp` directory at `~/.local/share/ARKtube` the
same way the AppImage's `AppRun` does.

### Windows: building the .exe

```powershell
cd ARKtube/
neu update                       # fetch the pinned 6.8.0 binaries
./packaging/windows/build-exe.ps1
```

Produces `ARKtube-<version>-windows-x64.zip` containing `ARKtube.exe`
(resources are embedded into the binary at build time, so there's
nothing else needed to run it) plus `ARKtube.bat` / `Launch-ARKtube.ps1`.
**Run `ARKtube.bat`, not `ARKtube.exe` directly** - the wrapper points
Neutralino at a writable per-user data dir and cleans up the detached
chrome-mode child process on exit (see
`packaging/windows/Launch-ARKtube.ps1` for why that matters).

### macOS: building the .dmg

```bash
cd ARKtube/
neu update                       # fetch the pinned 6.8.0 binaries
./packaging/macos/build-dmg.sh
```

Produces `ARKtube-<version>-macos.dmg` containing `ARKtube.app` (with an
`Applications` symlink for drag-to-install). The app launches through a
small wrapper that points Neutralino's writable `.tmp` directory at
`~/Library/Application Support/ARKtube`, since `/Applications` itself
isn't writable by a normal user.

Every platform artifact above is also built automatically by
`.github/workflows/stage0.yml` on every push to `main` and is
downloadable from that workflow run's Artifacts section.

### Controller / remote support

`resources/js/app-init.js` polls the standard Gamepad API and re-dispatches
D-pad, stick, and face-button input as the same `ArrowUp/Down/Left/Right`,
`Enter`, `Escape`, and `Home` key events the keyboard handler already
understands — so any game controller, or any remote that a platform
exposes to the browser as a HID gamepad, drives the same youtube.com/tv
navigation a keyboard does, with no separate input path to maintain.
Connect/disconnect events are logged via `debug.log` for troubleshooting.

### Chrome-mode process lifecycle

ARKtube's `defaultMode` is `chrome` (see `neutralino.config.json`), which
runs the actual YouTube page in a separate Chrome/Chromium/Edge child
process rather than Neutralino's own webview. That child process is
launched fully detached from the Neutralino server process, so
`Neutralino.app.exit()` (what `app-init.js` calls on window close) only
ever terminates the Neutralino server - never the browser it spawned.
Left alone, that leaks a browser process (and its profile lock) on any
exit that isn't a clean quit through the app UI, and the next launch
silently reattaches to the orphan instead of starting fresh. Every
launcher this project ships - `packaging/linux/AppRun` (AppImage),
`packaging/linux/build-deb.sh`'s `/usr/bin/arktube`,
`packaging/macos/build-dmg.sh`'s `ARKtube.app` wrapper, and
`packaging/windows/Launch-ARKtube.ps1` - reaps that orphaned process and
its stale lock files both before and after each run. If you add another
distribution channel, carry the same cleanup over; running the raw
Neutralino binary directly (bypassing all of the above) will hit this.

### Linux: hardware-accelerated playback

ARKtube's webview is WebKitGTK, the same engine GNOME Web uses, and the
same playback stack Firefox's GTK build ultimately hands off to for
VA-API decode. For smooth, hardware-accelerated YouTube playback
(comparable to Firefox), install the full GStreamer plugin set:

```bash
# Debian/Ubuntu
sudo apt install gstreamer1.0-plugins-base gstreamer1.0-plugins-good \
                  gstreamer1.0-plugins-bad gstreamer1.0-plugins-ugly \
                  gstreamer1.0-libav gstreamer1.0-vaapi gstreamer1.0-gl
```

Without `gstreamer1.0-plugins-bad`, subtitles fall back to a degraded
path ("WebKit wasn't able to find a WebVTT encoder"). Without
`gstreamer1.0-vaapi` and `gstreamer1.0-gl`, video decode and compositing
run entirely on the CPU. See `docs/BUGS-CAUGHT.md` for the full
explanation, including how to pick the right `LIBVA_DRIVER_NAME` for
your GPU.

---

## Roadmap

### 01 — It opens

YouTube launches inside a native desktop window.

### 02 — It works

Playback, search, authentication, navigation and sessions work normally.

### 03 — It feels native

Window state, keyboard shortcuts, menus and desktop integration are added.

### 04 — It stays alive

Navigation becomes persistent and unnecessary document-level reloads are reduced.

### 05 — It disappears

The desktop shell becomes invisible.

All you notice is YouTube.

That's the point.

---

## Philosophy

Keep the UI.

Keep the player.

Keep the experience.

Change the shell.

```text
                 ┌──────────────┐
                 │    YouTube   │
                 └──────┬───────┘
                        │
                  existing UX
                        │
                 ┌──────▼───────┐
                 │  Neutralino  │
                 └──────┬───────┘
                        │
                  desktop app
```

The project should add as little as possible.

If YouTube already solves a problem, let YouTube solve it.

If the desktop needs something YouTube doesn't provide, add the smallest layer necessary.

---

## Architecture: How the App Initializes

### Key Files

* **`neutralino.config.json`** — Application configuration (window mode vs. chrome mode)
* **`resources/js/app-init.js`** — Injected into YouTube's page, handles window control and tray menu
* **`resources/index.html`** — Template-only, not served in production (documentRoot is null)
* **`resources/js/main.js`** — Dev-only, for testing with `resources/index.html`

### Initialization Flow

1. Neutralino starts with `url: "https://www.youtube.com/tv#/"` (external YouTube, not local resources)
2. Neutralino injects `resources/js/app-init.js` into the YouTube page
3. `app-init.js` initializes Neutralino API with error handling
4. Event listeners are registered for window close and tray menu clicks
5. Keyboard shortcuts (F11 for fullscreen, Escape for exit) are wired up

### Why `documentRoot` is Null

The app loads YouTube directly as an external URL. The `documentRoot: "/resources/"` would only be used if `url` pointed to a local page (like `/resources/#index`). Since we're using an external URL, we set `documentRoot` to `null` to avoid confusion.

### Production vs. Development

* **Production**: Uses YouTube TV interface with `app-init.js` injected for native controls
* **Development**: Can temporarily change `url` to `"/resources/#index"` to test with local `index.html` and `main.js`

### Chrome Mode vs. Window Mode

Both modes now include `app-init.js` injection and support for:
* Tray menu (VERSION, QUIT)
* Window close handling
* Fullscreen toggle (F11)

Chrome mode additionally:
* Launches with `--start-fullscreen` and PS4 user-agent
* Blocks filesystem access for security
---

This project contains original code written for the desktop shell and application layer.

YouTube is a trademark of Google LLC.

This project is independent and is not affiliated with or endorsed by Google or YouTube.

See the repository license for the licensing terms of this project.
