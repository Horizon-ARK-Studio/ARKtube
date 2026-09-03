# ARKtube AppImage crash & playback-quality fix proposal

**Status:** proposed, patches included in this branch
**Scope:** Linux AppImage distribution only (source `neu run` / `neu build`
development flow was already fine)

## 1. Summary

Running the previously-published `ARKtube-x86_64.AppImage` produced two
failures back to back, ending in a hard crash:

```text
2026-08-31 19:53:08,639 ERROR [default] NE_RS_TREEGER: Resource file tree generation error. ./resources.neu is missing.
...
terminate called after throwing an instance of 'std::filesystem::__cxx11::filesystem_error'
  what():  filesystem error: cannot create directories: Read-only file system [./.tmp]
Aborted (core dumped)
```

Plus a set of GStreamer/WebKit console warnings indicating software-only
video playback and broken subtitles:

```text
WebKit wasn't able to find a WebVTT encoder. Subtitles handling will be degraded unless gst-plugins-bad is installed.
GStreamer element fakevideosink not found. Please install it
```

Neither of these is a bug in ARKtube's own JS/config — they're
consequences of how the AppImage was assembled and of what's on the
target system. Both are fixed here without touching YouTube's frontend
at all, consistent with the project's own design principle in
`docs/PROBLEM-STATEMENT.md` ("borrow behavior and appearance before
rebuilding anything").

## 2. Root cause: `./resources.neu is missing`

Neutralino loads app resources in one of three modes
(`--res-mode=embedded|bundle|directory`). In `bundle` mode it looks for
`resources.neu` **next to the binary**. The previous AppImage apparently
shipped the raw `neu build` output (binary + separate `resources.neu`)
without guaranteeing both land in the same place inside the AppImage
mount, so the binary couldn't find its resource file at startup.

Neutralino silently falls back to `directory` mode when this happens
(hence the app still opened and rendered YouTube), but it's a fragile,
avoidable failure mode for a distributed AppImage.

**Fix:** build with `neu build --embed-resources`
(`packaging/linux/build-appimage.sh`). This produces a single-file
binary with resources baked in via Node's `postject`, so there is no
external `resources.neu` to lose track of, ever.

## 3. Root cause: `Read-only file system [./.tmp]` → abort

This is the actual crash, and it's a known interaction between
Neutralino and read-only AppImage squashfs mounts:

- Neutralino keeps working storage (extension IPC sockets, internal
  state) under a `.tmp` directory relative to `NL_PATH`.
- `NL_PATH` defaults to the resources path, which in turn defaults to
  the directory the binary was launched from.
- Inside a mounted AppImage, that directory *is* the read-only squashfs
  image (`/tmp/.mount_XXXXXXX/...`), so `mkdir ./.tmp` throws
  `EROFS`, and — on the framework version previously pinned in this
  repo (`nightly`, before this branch) — that exception surfaces as an
  uncaught `std::filesystem::filesystem_error` and the process aborts.
  Neutralino 6.0.0's release notes list *"Fix framework crashing when
  creating the `.tmp` directory under restricted file manipulation
  permissions"*, but that fix targets restricted-permission scenarios,
  not a fully read-only mount — which is exactly what an AppImage is.

**Fix, two parts:**

1. `neutralino.config.json` now pins `cli.binaryVersion` /
   `cli.clientVersion` to `6.8.0` instead of `nightly`, for a
   reproducible, known-good build (`nightly` can silently change under
   the project's feet between builds).
2. `packaging/linux/AppRun` — the new AppImage entrypoint — launches the
   binary with `--path=<writable directory>`
   (`$XDG_DATA_HOME/ARKtube`, created on first run), so `NL_PATH` and
   therefore `./.tmp` never resolve inside the read-only mount in the
   first place. This is a one-line, documented Neutralino CLI flag
   (`--path=<path>` — "Overrides the resources path... changes the
   `NL_PATH` global variable"), not a workaround-of-a-workaround.

## 4. Playback quality: matching Firefox's hardware-accelerated path

ARKtube's webview is WebKitGTK on Linux — the same engine behind GNOME
Web, and architecturally close to what Firefox's GTK build ultimately
delegates to for VA-API video decode. The console log shows it running
in a degraded mode:

```text
WebKit wasn't able to find a WebVTT encoder. Subtitles handling will be degraded unless gst-plugins-bad is installed.
GStreamer element fakevideosink not found. Please install it
```

`fakevideosink` is a core GStreamer element — its absence means the
system's GStreamer install is missing base plugins entirely, not just
the "bad" set. This isn't something ARKtube's config can fix by itself;
it's a runtime dependency the AppImage doesn't (and shouldn't) bundle,
since bundling a full GStreamer + VA-API stack into every AppImage is
exactly the "bundle an entire browser runtime" outcome this project
deliberately avoids (see the root README: *"Neutralinojs is used as the
native application layer rather than bundling a complete browser
runtime"*).

**Fix, two parts:**

1. **Documented system dependency** (README, "Linux:
   hardware-accelerated playback"): install
   `gstreamer1.0-plugins-{base,good,bad,ugly}`, `gstreamer1.0-libav`,
   `gstreamer1.0-vaapi`, and `gstreamer1.0-gl`. This is the same plugin
   set a distro's Firefox package pulls in as recommended/dependency
   packages, which is why Firefox "just works" on the same machine
   where the previous AppImage didn't.
2. **Environment variables set by `AppRun` before every launch**, mirroring
   what a properly configured browser sets:
   - Explicitly unsets any inherited `WEBKIT_DISABLE_COMPOSITING_MODE`
     / `LIBGL_ALWAYS_SOFTWARE` so GPU compositing isn't silently forced
     off.
   - `WEBKIT_DISABLE_DMABUF_RENDERER=0` — keeps WebKit's accelerated
     DMA-BUF video frame path enabled instead of the slower Cairo
     fallback.
   - `GST_VAAPI_ALL_DRIVERS=1` — GStreamer's VA-API plugin only trusts a
     short hardcoded driver allow-list by default; this is the single
     most common reason "VA-API is installed but not used" on anything
     outside a handful of Intel chips.

   `LIBVA_DRIVER_NAME` is deliberately **not** hardcoded, since forcing
   the wrong value (e.g. `iHD` on an AMD/NVIDIA machine) breaks VA-API
   outright instead of just leaving it unaccelerated. If hardware decode
   still isn't kicking in after installing the packages above, check
   what your system already uses for Firefox/mpv:

   ```bash
   vainfo                       # lists the active VA-API driver + supported codecs
   ```

   and export the matching value before launching, e.g. `iHD` (modern
   Intel), `i965` (older Intel), `radeonsi` (AMD), or `nvidia`
   (proprietary NVIDIA driver with `nvidia-vaapi-driver`).

## 5. Chrome mode: "Opening in existing browser session." on every relaunch

Repeated launches of the AppImage (`defaultMode: "chrome"`, per
`neutralino.config.json`) print `Opening in existing browser session.` on
the second and later runs instead of opening a fresh app window, and the
process list accumulates extra PIDs across runs that never seem to exit.

It's tempting to read this as a missing/misconfigured `--user-data-dir`
(some AI-generated advice circulating for this exact symptom says
that), but that's not what's happening here, and adding one to
`modes.chrome.args` would just create a *second*, differently-located
profile alongside the one Neutralino already manages — not fix
anything. Checked directly against Neutralino's own source
(`chrome.cpp`):

```cpp
chromeCmd += " --user-data-dir=\"" + settings::joinAppDataPath("/.tmp/chromedata") + "\"";
```

This is unconditional — chrome mode always sets `--user-data-dir`, with
no config path that omits it. `joinAppDataPath` resolves against
`appDataPath`, which defaults to `appPath` (`settings.cpp`), which is
exactly the directory job 3's `--path="${ARKTUBE_DATA_DIR}"` (§3 above)
already points at `$XDG_DATA_HOME/ARKtube`. So the profile directory —
`$XDG_DATA_HOME/ARKtube/.tmp/chromedata` — is already persistent purely
as a side effect of the fix in §3; cookies and login state already
survive between clean launches on their own.

**Actual root cause:** process lifecycle, not path configuration.
`chrome.cpp` spawns the browser via `os::execCommand(..., {background:
true})`, which on Linux (`lib/tinyprocess/process_unix.cpp`) calls
`setpgid(0, 0)` on the child right after `fork()` — putting Chrome in
its own process group, detached from the Neutralino server. Separately,
`app::exit()` and `app.killProcess()` (`api/app/app.cpp`) only ever
signal `getpid()`, i.e. the Neutralino server's own PID — never the
Chrome child. So:

- A terminal `Ctrl-C` only reaches processes in the terminal's
  foreground process group, and Chrome isn't in it.
- `Neutralino.app.exit()`, called from `app-init.js`'s `windowClose`
  handler, only stops the Neutralino server.

Either way, Chrome is orphaned and keeps running, still holding
`.tmp/chromedata/SingletonLock`. The next launch hits Chrome's own
singleton-instance check against that lock and hands off to the
orphan instead of starting fresh — hence the message — and that
orphaned window was never wired to *this* run's `app-init.js`, so its
close handling, tray, etc. are all dead. Repeat a few times and you get
exactly what the process list shows: one live app window backed by
whichever orphan happened to win the handoff, plus a pile of unreachable
zombies underneath it.

**Fix:** `packaging/linux/AppRun` now reaps stale Chrome instances tied
to *this app's own* profile directory (matched by the literal
`--user-data-dir=.../chromedata` value in their command line, so it can
never touch the user's actual browser profile) both before launch — in
case the previous run left one behind — and via an `EXIT`/`INT`/`TERM`
trap, so this run cleans up after itself too, however it ends. This
required dropping the `exec` used to launch the Neutralino binary (a
trap can't run after `exec` replaces the shell process), replaced with a
plain foreground invocation — signal delivery to the Neutralino binary
itself is unaffected, since it's still the script's only foreground
child.

## 6. Files changed

| File | Change |
|---|---|
| `ARKtube/neutralino.config.json` | Pin `binaryVersion`/`clientVersion` to `6.8.0` (was `nightly`) |
| `ARKtube/packaging/linux/AppRun` | Writable `--path`, hardware-accel env vars, **and now**: reap stale/orphaned Chrome-mode processes before launch and on exit (§5) |
| `ARKtube/packaging/linux/ARKtube.desktop` | **New.** Desktop entry for AppImage integration |
| `ARKtube/packaging/linux/build-appimage.sh` | **New.** Reproducible build script (`--embed-resources` + AppDir assembly) |
| `README.md` | New "Linux: building the AppImage" and "Linux: hardware-accelerated playback" sections |

## 7. Verification steps

```bash
cd ARKtube/
neu update                          # pull the pinned 6.8.0 binaries
sudo apt install gstreamer1.0-plugins-base gstreamer1.0-plugins-good \
                  gstreamer1.0-plugins-bad gstreamer1.0-plugins-ugly \
                  gstreamer1.0-libav gstreamer1.0-vaapi gstreamer1.0-gl
./packaging/linux/build-appimage.sh
./ARKtube-x86_64.AppImage
```

Expected: no `resources.neu is missing` line, no `.tmp` filesystem
error, no `fakevideosink not found` warning, and `vainfo` output showing
an active decode profile while a video is playing (check with
`intel_gpu_top` / `radeontop` / `nvidia-smi` for actual GPU decode
utilization, since VA-API can be "available" without every video
actually using it depending on codec).

For §5, specifically:

```bash
./ARKtube-x86_64.AppImage &   # launch, sign into YouTube, then Ctrl-C the terminal
pgrep -fa chromedata          # should show nothing once the trap has run
./ARKtube-x86_64.AppImage     # should NOT print "Opening in existing browser session."
                               # and should still be signed in
```

## 8. Non-goals

This proposal does not touch:
- the navigation/state-persistence architecture described in
  `docs/PROBLEM-STATEMENT.md` sections 7–9 (unimplemented, out of scope
  here),
- Windows/macOS packaging (unaffected — the read-only-mount problem is
  AppImage-specific),
- YouTube's own frontend or player.

## 9. Moving off chrome mode: a detached browser process isn't "an app"

Sections 5–7 (and the equivalent fixes later added to the `.deb`, `.dmg`,
and Windows launchers) all worked around the same underlying fact:
`defaultMode: "chrome"` makes Neutralino spawn Chrome/Chromium/Edge as a
**separate, fully-detached process** and load YouTube inside *that*,
with the Neutralino server itself just relaying window-lifecycle events
to it. Every fix up to this point made that arrangement more reliable
(reaping orphans, persisting the profile), but it never stopped being
two processes wearing one app's clothing — closable independently,
killable independently, and only reunited by a `--user-data-dir` string
match. That's a workaround, not the intended architecture.

`defaultMode` is now `"window"`. In window mode, YouTube loads inside
Neutralino's **own** embedded webview — WebKitGTK on Linux, WebView2 on
Windows, WKWebView on macOS — running in the same process as the rest of
the app, the same way any other native desktop application works.
There is no second process to detach, orphan, or reap in the first
place, so the entire class of bugs in §5–§7 doesn't apply to it. Native
window behavior (title bar, taskbar/dock entry, real minimize/
maximize/fullscreen via `Neutralino.window.*`, the system tray) now also
works correctly, rather than silently no-opping the way it did under
chrome mode (see the updated comment in `resources/js/app-init.js`).

**The trade-off, stated plainly:** chrome mode's `args` could pass a
full `--user-agent="Mozilla/5.0 (PS4; Leanback Shell) Cobalt/26.lts.0-qa;
compatible;"` override, which is how this app got youtube.com/tv to
serve its full 10-foot "Leanback" TV interface instead of the regular
desktop site. Window mode has no equivalent full-replacement option —
Neutralino's webview only supports `extendUserAgentWith`, which
*appends* a suffix to the platform's real WebKit/WebView2/WKWebView user
agent string, not a full spoof (this is a Neutralino/webview-library
limitation, not something this project's config can work around).
`modes.window.extendUserAgentWith` is set to the same Cobalt/PS4
fragment as a best-effort attempt, but because it now trails a real
desktop browser signature instead of replacing it outright, YouTube's
own server-side device detection may or may not still serve the
Leanback UI for it — this hasn't been verified against a live YouTube
response, since that detection logic is on Google's side and not
something reproducible in this repo.

If you find the app no longer renders the TV/Leanback interface after
this change: `modes.chrome` is left fully intact in
`neutralino.config.json` for exactly this reason. Set `"defaultMode"`
back to `"chrome"` to restore the old spoofed-UA behavior — the
`AppRun` / `build-deb.sh` / `build-dmg.sh` / `Launch-ARKtube.ps1`
cleanup logic from §5–§7 was deliberately left in place (it's an
inert no-op under window mode, since it only ever matches processes
against this app's own chrome-profile directory) so that switching back
doesn't reintroduce the orphaned-process bug either.
