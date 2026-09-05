# arktube_linux

A native Linux port of ARKtube: **one GTK3 + WebKit2GTK process, no
Neutralino, no embedded/reparented Chrome.**

The `../ARKtube` directory (Neutralino + hybrid embedded-Chrome shell)
is unaffected by this and keeps building the same way it always has,
via `.github/workflows/stage0.yml`. This directory is a from-scratch,
Linux-only rewrite living alongside it, not a modification of it.

## Why

`../ARKtube`'s Linux build works by spawning a real, separate Chrome
process and reparenting it as an X11 child of Neutralino's own window
(`packaging/linux/embed-chrome.sh`), so that YouTube (rendered by that
Chrome process) visually fills a window that Neutralino actually owns
and controls. That buys a fully-spoofable user agent and a real Chrome
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

🚧 **Early port, in progress.** This starts from the smallest possible
slice and grows from there -- see `docs/PORTING-NOTES.md` for exactly
what has and hasn't been carried over yet from `../ARKtube`.

Currently working:

* [x] Launches youtube.com/tv in a native GTK window
* [x] TV/Leanback interface: full user-agent replacement (`src/main.c`)
      *and* a matching `navigator.userAgent`/`navigator.platform`/
      `navigator.maxTouchPoints`/`screen.*` spoof (`resources/js/user-script.js`)
      -- the UA header alone isn't sufficient; youtube.com/tv's bootstrap
      JS reads the JS-visible identity directly too. See
      `docs/PORTING-NOTES.md` for why both layers are needed.
* [x] F11 fullscreen toggle, Escape to exit fullscreen
* [x] Home key navigates back to `/tv`'s root without a full reload
* [x] Gamepad/remote input, remapped to the same keyboard events
      youtube.com/tv already understands
* [x] Cursor auto-hide after 10s idle

Not yet ported (see `docs/PORTING-NOTES.md` for why each needs a
native replacement rather than a direct copy):

* [ ] Tray icon / tray menu
* [ ] Immersive Mode (fullscreen lockdown + devtools guard)
* [ ] Persisted settings
* [ ] Packaging (AppImage/.deb equivalents, an install target exists
      in `CMakeLists.txt` but isn't wired into a CI artifact yet)

## Hardware acceleration & stutter

WebKitGTK is asked to keep GPU compositing on unconditionally
(`WEBKIT_HARDWARE_ACCELERATION_POLICY_ALWAYS`, set in `src/main.c`),
but smooth playback also depends on the system's GStreamer/VA-API
stack for video decode. If playback is stuttery, see
[`docs/HARDWARE-ACCELERATION.md`](docs/HARDWARE-ACCELERATION.md) for
the GStreamer VA-API packages to install, how to pick the right driver
for your GPU, and the `WEBKIT_DISABLE_DMABUF_RENDERER` troubleshooting
step for the rarer case where accelerated compositing itself -- not
decode -- is the cause.

## Building

**CI does the actual compiling** (see `.github/workflows` at the repo
root, once the workflow for this directory lands) -- everything below
is for a local dev build.

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
│   └── main.c              # GTK3 + WebKit2GTK window/webview, F11/Escape
├── resources/
│   ├── js/
│   │   └── user-script.js  # injected page script -- ported subset of
│   │                       # ../ARKtube/resources/js/app-init.js
│   └── icons/
│       └── appIcon.png
├── packaging/
│   └── arktube-linux.desktop
└── docs/
    ├── PORTING-NOTES.md    # file-by-file mapping from ../ARKtube
    └── HARDWARE-ACCELERATION.md  # VA-API setup and stutter troubleshooting
```
