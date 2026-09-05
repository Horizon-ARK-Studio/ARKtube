# ARKtube

## YouTube, as a native desktop app.

A lightweight, single-process Linux desktop client for `youtube.com/tv`,
built with **GTK3 + WebKit2GTK** (`arktube_linux/`).

It looks like YouTube's TV/Leanback interface.

It behaves like an app.

No redesign.
No replacement frontend.
Just YouTube, in a proper desktop window.

---

## The idea

YouTube is already a great product.

The desktop website just doesn't always feel like a desktop application.

This project keeps the familiar YouTube TV experience while giving it a
persistent, native desktop shell.

```text
YouTube (TV/Leanback UI)
        +
GTK3 + WebKit2GTK
        =
YouTube, installed.
```

The goal is simple:

**Open it. Watch something. Keep browsing.**

---

## Why?

A desktop application should feel persistent.

Navigation shouldn't feel like throwing the entire application away and
rebuilding it from scratch.

This project explores a different model:

```text
┌─────────────────────────────────────────┐
│                                         │
│         YouTube (TV/Leanback)           │
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

* **GTK3** — native window, event handling, and drawing
* **WebKit2GTK** (4.1) — the same rendering engine GNOME Web / Epiphany
  uses, running in the same process as the rest of the app
* **YouTube's TV/Leanback interface** (`youtube.com/tv`) — the actual
  interface and content experience
* **C** — the native glue (`arktube_linux/src/main.c`)
* **A small injected page script** — `arktube_linux/resources/js/user-script.js`

This is a single native process: one window, one WebView, no second
browser process to spawn or keep in sync, and no Node.js/npm build
tooling required to build or run it.

---

## What this is

**A native desktop shell for YouTube's TV interface.**

It requests and renders `youtube.com/tv` — YouTube's TV/Leanback
interface, designed for D-pad/remote-style navigation — inside a native
window, and adds the desktop/TV-box behavior that interface doesn't get
for free in a plain browser tab:

* a full user-agent replacement (HTTP header *and* the JS-visible
  `navigator`/`screen` identity) so YouTube serves the TV interface
  reliably
* fullscreen (F11) and Escape-to-unfullscreen, with the fullscreen
  state remembered across restarts
* Home-key in-page navigation back to the TV interface's root, without
  a full page reload
* gamepad/remote input remapped to the same keyboard events the page
  already understands
* cursor auto-hide after 10 seconds of idle mouse activity
* a connectivity check and a "no internet" screen shown in place of an
  empty or endlessly-loading browser when the machine is offline
* a branded boot splash shown while the page loads, dismissed the
  moment it actually finishes rather than after a fixed delay

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

> **What if YouTube's TV interface behaved like the native desktop
> application it already looks like?**

---

## Status

🚧 **Early native port, in progress.**

An earlier version of this project (removed from `main`; see the git
history if you're curious) wrapped YouTube in
[Neutralinojs](https://neutralino.js.org) instead, and on Linux spawned
a separate, reparented Chrome process to get a spoofable user agent.
`arktube_linux/` replaces that entirely with one native GTK3 +
WebKit2GTK process — see `arktube_linux/README.md`'s "Why" section for
the full reasoning, and `docs/bugs-caught/` for the class of bugs the
old approach caused.

Currently working (Linux only):

* [x] Launches `youtube.com/tv` in a native GTK window
* [x] TV/Leanback interface via a full user-agent + `navigator`/`screen`
      identity replacement
* [x] F11 fullscreen toggle, Escape to exit fullscreen, remembered
      across restarts
* [x] Home key navigates back to `/tv`'s root without a full reload
* [x] Gamepad/remote input, remapped to keyboard events
* [x] Cursor auto-hide after 10s idle
* [x] Connectivity check with a "no internet" screen
* [x] Boot splash, dismissed when the page actually finishes loading
* [x] `.deb` package built and uploaded automatically by
      `.github/workflows/arktube-linux.yml`

Not yet ported:

* [ ] Tray icon / tray menu
* [ ] Immersive Mode (fullscreen lockdown + devtools guard)
* [ ] Windows / macOS builds
* [ ] AppImage packaging

The project will stay deliberately small until the underlying approach
is proven on Linux, before any other platform is considered.

---

## Design

The architecture is the smallest layer that gets a spoofable user agent
and a single, native, cross-compositor (X11 + Wayland) window:

```text
┌─────────────────────────────┐
│      GTK3 process           │
│                             │
│   ┌─────────────────────┐   │
│   │   WebKit2GTK WebView │   │
│   │                     │   │
│   │  youtube.com/tv     │   │
│   │                     │   │
│   └─────────────────────┘   │
│                             │
│   window chrome, keyboard,  │
│   splash/no-internet UI     │
└─────────────────────────────┘
```

Over time, desktop-specific functionality can be introduced without
replacing the YouTube experience.

---

## Development

See `arktube_linux/README.md` for full build instructions. In short:

### Requirements

* CMake ≥ 3.16, a C11 compiler
* GTK3 development headers (`libgtk-3-dev` on Debian/Ubuntu)
* WebKit2GTK development headers (`libwebkit2gtk-4.1-dev` on
  Debian/Ubuntu)

### Build and run

```bash
cd arktube_linux
cmake -B build -S .
cmake --build build
./build/arktube_linux
```

`cmake --build` copies `resources/` next to the built binary
automatically, so the build directory is runnable as-is.

### Install

```bash
cmake --install build --prefix /usr/local
```

Installs the binary, `resources/` (as
`share/arktube_linux/resources/`), the `.desktop` entry, and the app
icon, matching a normal Linux `/usr` layout.

### CI

`.github/workflows/arktube-linux.yml` builds the binary, installs it
into a staging prefix, and packages that prefix into a `.deb` on every
push to `main`, uploading all three as workflow artifacts. It does not
currently build an AppImage, a Windows build, or a macOS build.

### Controller / remote support

`resources/js/user-script.js` polls the standard Gamepad API and
re-dispatches D-pad, stick, and face-button input as the same
`ArrowUp/Down/Left/Right`, `Enter`, `Escape`, and `Home` key events the
keyboard handler already understands — so any game controller, or any
remote that a platform exposes to the browser as a HID gamepad, drives
the same `youtube.com/tv` navigation a keyboard does, with no separate
input path to maintain. Connect/disconnect events are logged to the
console for troubleshooting.

### Single-process app

`arktube_linux` loads `youtube.com/tv` inside its own WebKit2GTK
WebView, in the *same process* as the rest of the app, like any normal
native desktop application. Closing the window quits the whole thing;
there's no second window or process left running afterward.

### Linux: hardware-accelerated playback

`arktube_linux`'s webview is WebKitGTK, the same engine GNOME Web uses,
and the same playback stack Firefox's GTK build ultimately hands off to
for VA-API decode. `webkit_settings_set_hardware_acceleration_policy()`
is set explicitly to `ALWAYS` in `src/main.c`, but smooth,
hardware-accelerated playback still depends on the system having the
full GStreamer plugin set installed:

```bash
# Debian/Ubuntu
sudo apt install gstreamer1.0-plugins-base gstreamer1.0-plugins-good \
                  gstreamer1.0-plugins-bad gstreamer1.0-plugins-ugly \
                  gstreamer1.0-libav gstreamer1.0-vaapi gstreamer1.0-gl
```

Without `gstreamer1.0-plugins-bad`, subtitles fall back to a degraded
path ("WebKit wasn't able to find a WebVTT encoder"). Without
`gstreamer1.0-vaapi` and `gstreamer1.0-gl`, video decode and compositing
run entirely on the CPU. See `docs/bugs-caught/BUGS-CAUGHT.md` for the
background this was originally diagnosed against (the earlier
Neutralino-based build), including how to pick the right
`LIBVA_DRIVER_NAME` for your GPU — the same guidance still applies here.

---

## Roadmap

### 01 — It opens

YouTube's TV interface launches inside a native desktop window. ✅

### 02 — It works

Playback, search, authentication, navigation and sessions work normally. ✅

### 03 — It feels native

Window state (fullscreen persistence), input remapping and desktop
integration (`.desktop` entry, icon) are in place. Tray icon and
Immersive Mode lockdown are still open.

### 04 — It stays alive

Navigation is already persistent within the single WebView; further
reducing unnecessary reloads remains an open area.

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
                 │ GTK3+WebKit2GTK│
                 └──────┬───────┘
                        │
                  desktop app
```

The project should add as little as possible.

If YouTube already solves a problem, let YouTube solve it.

If the desktop needs something YouTube doesn't provide, add the
smallest layer necessary.

---

## Architecture: how the app initializes

### Key files

* **`arktube_linux/src/main.c`** — window/webview creation, user-agent
  and hardware-acceleration setup, fullscreen handling, the
  connectivity check, and the boot-splash / no-internet overlays
* **`arktube_linux/resources/js/user-script.js`** — injected at
  document-start into every frame of `youtube.com/tv`: the
  `navigator`/`screen` identity spoof, Home-key SPA navigation,
  gamepad/remote-to-keyboard remapping, and cursor auto-hide
* **`arktube_linux/packaging/arktube-linux.desktop`** — the installed
  `.desktop` launcher entry
* **`arktube_linux/CMakeLists.txt`** — build and install rules

### Initialization flow

1. `main()` creates the GTK window (maximized by default, or
   fullscreen if that was the state when the app last quit) and shows
   it immediately.
2. A background thread checks internet connectivity by attempting a
   raw TCP connection to `8.8.8.8:53`, independently of and before any
   WebView load.
3. While offline, a full-bleed "no internet" screen is shown in place
   of the browser; the check keeps retrying every few seconds.
4. Once connectivity is confirmed, the WebView is pointed at
   `https://www.youtube.com/tv#/` and a branded boot splash (logo +
   spinner) is shown on top of it.
5. `user-script.js` is injected at document-start, ahead of any of the
   page's own scripts, so the user-agent and `navigator`/`screen` spoof
   is in place before the page's bootstrap JS ever reads it.
6. The splash is dismissed the moment the WebView reports
   `WEBKIT_LOAD_FINISHED` (with a 20-second defensive ceiling in case
   that event never fires).

### Why the TV/Leanback interface

`youtube.com/tv` is designed for D-pad/remote navigation rather than a
mouse-and-keyboard desktop browser, which is what makes it a good fit
for a persistent, always-on-top-feeling desktop shell. Getting YouTube
to reliably serve that interface requires more than a User-Agent HTTP
header: `youtube.com/tv`'s own bootstrap JS also reads
`navigator.userAgent`, `navigator.platform`, `navigator.maxTouchPoints`
and `screen.*` directly, so `user-script.js` patches those on the
relevant prototypes before the page's own scripts run — see the
comments in that file for why each property is patched the way it is.

---

This project contains original code written for the desktop shell and
application layer.

YouTube is a trademark of Google LLC.

This project is independent and is not affiliated with or endorsed by
Google or YouTube.

See the repository license for the licensing terms of this project.
