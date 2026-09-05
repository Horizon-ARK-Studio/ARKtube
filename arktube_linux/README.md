# arktube_linux

A native Linux port of ARKtube: **one GTK3 + WebKit2GTK process, no
Neutralino, no embedded/reparented Chrome.**

This is now the only application code on the `main` branch. An earlier
revision of this project wrapped YouTube in Neutralinojs instead, with
a Linux build that spawned and reparented a separate Chrome process
(the shell this replaced); that Neutralino app lived in an `ARKtube/`
directory that has since been removed from `main` (see the git history
if you want the old code). `docs/bugs-caught/` still documents the bug
history from that earlier approach, since the same GStreamer/hardware-
acceleration notes still apply here.

## Why

The old Neutralino-based Linux build worked by spawning a real,
separate Chrome process and reparenting it as an X11 child of
Neutralino's own window, so that YouTube (rendered by that Chrome
process) visually filled a window that Neutralino actually owned and
controlled. That bought a fully-spoofable user agent and a real Chrome
engine, at a real cost documented in `../docs/bugs-caught`:

* it needs `xdotool`/`wmctrl`/`xbindkeys` and an X11 session --
  reparenting one client's window into another's simply isn't possible
  under Wayland, so that path falls back to rendering YouTube in
  Neutralino's own (more limited) webview instead;
* two processes have to be kept in lockstep (resizing, closing,
  crash/orphan cleanup) instead of one;
* the whole thing needs Node.js, npm, and the `neu` CLI just to build,
  plus a prebuilt Neutralino server binary checked into the repo.

WebKitGTK -- the same engine GNOME Web / Epiphany uses, and the engine
Neutralino's own Linux webview already wraps -- draws directly into its
own GTK-owned window on **both** X11 and Wayland, with no reparenting
trick required, and its user agent setting
(`webkit_settings_set_user_agent()`) is a full replacement rather than
Neutralino window-mode's append-only `extendUserAgentWith`. That
removes the reparenting problem, the two-process problem, and the
user-agent limitation all at once, in exchange for being Linux-only --
which is exactly what this directory is for.

## Status

🚧 **Early port, in progress.** This started from the smallest possible
slice and has grown from there, carrying functionality over from the
old Neutralino-based shell piece by piece.

Currently working:

* [x] Launches youtube.com/tv in a native GTK window
* [x] TV/Leanback interface: full user-agent replacement (`src/main.c`)
      *and* a matching `navigator.userAgent`/`navigator.platform`/
      `navigator.maxTouchPoints`/`screen.*` spoof (`resources/js/user-script.js`)
      -- the UA header alone isn't sufficient; youtube.com/tv's bootstrap
      JS reads the JS-visible identity directly too.
* [x] F11 fullscreen toggle, Escape to exit fullscreen
* [x] Fullscreen state persisted across restarts, in a GKeyFile under
      `$XDG_CONFIG_HOME/arktube_linux/config.ini` (see
      `arktube_load_fullscreen_pref()`/`arktube_save_fullscreen_pref()`
      in `src/main.c`)
* [x] Home key navigates back to `/tv`'s root without a full reload
* [x] Gamepad/remote input, remapped to the same keyboard events
      youtube.com/tv already understands
* [x] Cursor auto-hide after 10s idle
* [x] Background connectivity check (raw TCP connect to `8.8.8.8:53`,
      retried on a timer) that gates the WebView's first load, showing
      a full-bleed "no internet" screen (`splash screen/no_internet.png`)
      instead of an empty or perpetually-loading browser while offline
* [x] Branded boot splash (`splash screen/boot-logo.png` + an animated
      spinner), shown while the page loads and dismissed the moment
      WebKit reports the load finished (with a 20s defensive fallback)
* [x] Debian package built and uploaded automatically by
      `.github/workflows/arktube-linux.yml` (see "Building" below)

Not yet ported:

* [ ] Tray icon / tray menu
* [ ] Immersive Mode (fullscreen lockdown + devtools guard)
* [ ] AppImage packaging (only the `.deb` is currently produced by CI)
* [ ] Windows / macOS builds

## Building

**CI does the actual compiling and packaging**
(`.github/workflows/arktube-linux.yml`, at the repo root) -- it builds
the binary, installs it into a staging prefix, and packages that
prefix into a `.deb`, uploading all three as workflow artifacts on
every push to `main`. It does not currently build an AppImage
equivalent. Everything below is for a local dev build.

### Requirements

* CMake >= 3.16, a C11 compiler
* GTK3 development headers (`libgtk-3-dev` on Debian/Ubuntu)
* WebKit2GTK development headers (`libwebkit2gtk-4.1-dev` on
  Debian/Ubuntu)

### Configure and build

```bash
cd arktube_linux
cmake -B build -S .
cmake --build build
```

`cmake --build` copies `resources/` next to the built binary
automatically, so it's runnable straight from the build directory:

```bash
./build/arktube_linux
```

### Install

```bash
cmake --install build --prefix /usr/local
```

Installs the binary, `resources/` (as
`share/arktube_linux/resources/`), the `.desktop` entry, and the app
icon, matching a normal Linux `/usr` layout.

## Layout

```
arktube_linux/
├── CMakeLists.txt
├── src/
│   └── main.c                    # GTK3 + WebKit2GTK window/webview,
│                                  # F11/Escape, connectivity check,
│                                  # boot splash + no-internet overlays
├── resources/
│   ├── js/
│   │   └── user-script.js        # injected page script -- ported subset
│   │                              # of the old Neutralino app-init.js
│   ├── icons/
│   │   ├── appIcon.png           # window/.desktop icon
│   │   └── arktube-spinner.svg   # boot-splash loading spinner
│   └── splash screen/
│       ├── boot-logo.png         # boot splash backdrop
│       └── no_internet.png       # shown while offline
└── packaging/
    └── arktube-linux.desktop
```
