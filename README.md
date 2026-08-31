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
`docs/FIX-PROPOSAL.md` for why that reliably crashes on launch, and what
the wrapper does about it.

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
run entirely on the CPU. See `docs/FIX-PROPOSAL.md` for the full
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

## License

This project contains original code written for the desktop shell and application layer.

YouTube is a trademark of Google LLC.

This project is independent and is not affiliated with or endorsed by Google or YouTube.

See the repository license for the licensing terms of this project.
