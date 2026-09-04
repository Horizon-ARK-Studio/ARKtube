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

## 10. Wayland: the hybrid embed's fallback never actually loaded YouTube

The later move to a *hybrid* window/chrome-embed model (Neutralino owns
the real top-level window; `packaging/linux/embed-chrome.sh` spawns a
real, separate Chrome process and X11-reparents it inside that window,
to get chrome mode's full `--user-agent` spoof back — see that script)
correctly detected that X11 reparenting is impossible on Wayland and
bailed out rather than erroring. But bailing out just meant exiting 0
and doing nothing — nothing then told Neutralino's own window, which was
still sitting on `resources/index.html`'s local "Starting ARKtube…"
backdrop page, to load YouTube any other way. On a Wayland session the
app would launch, look like it was starting, and never go any further:

```text
embed-chrome: Wayland session detected - arbitrary window reparenting isn't
permitted here. Falling back to plain window mode; ARKtube will keep
running with its own webview loading the local shell page only. Run
under Xwayland/X11 for the hybrid embed.
```

That log line was accurate about *why* — it just wasn't true that
"plain window mode" did anything useful once the embed was ruled out.

A separate, unrelated bug was hiding behind the same symptom on some
setups: `neutralino.config.json` had `documentRoot: "/resources/"` *and*
`url: "/resources/#embed-shell"` — Neutralino resolves `url` against
`documentRoot`, so this asked for `/resources/resources/index.html`,
which doesn't exist:

```text
ERROR NE_RS_UNBLDRE: Unable to load application resource file /resources/resources/index.html
```

Fixed by dropping the redundant `/resources/` prefix from `url` (now
`/#embed-shell`) — `documentRoot` already supplies it.

**The actual fix:** `AppRun` and the `.deb` launcher now run
`embed-chrome.sh`'s own Wayland/tool/Chrome checks themselves, *before*
launching Neutralino at all, and when the embed isn't going to be
possible they pass Neutralino a `--native-fallback` argument instead of
spawning `embed-chrome.sh`. `resources/js/app-init.js` reads that back
via `NL_ARGS` (available through `injectGlobals`) and, if it's set and
this webview isn't already on youtube.com, `location.replace()`s
straight to `https://www.youtube.com/tv#/` — the same direct-load
architecture §9 originally described, now used as a fallback rather
than the default. `modes.window.extendUserAgentWith` (restored in
`neutralino.config.json`, alongside the `url` fix above) is what that
fallback gets in place of Chrome's full `--user-agent` replacement — the
same partial-spoof trade-off §9 already covers, unverified against a
live YouTube response for the same reason given there.

One knock-on change: `xdotool`/`wmctrl`/`xbindkeys` were `Depends` on
the `.deb` package, which meant every install pulled in X11-only
tooling a Wayland-only desktop would never use. Now that a missing
embed falls back gracefully instead of breaking, they're `Recommends`
instead — present by default via normal dependency resolution, but not
a hard requirement to install or run the package.

**Files changed:** `neutralino.config.json` (`url`,
`extendUserAgentWith`), `packaging/linux/AppRun`,
`packaging/linux/build-deb.sh`, `packaging/linux/embed-chrome.sh`
(comments only — its own checks are now a safety net, not the primary
path), `resources/js/app-init.js`.

**Verification steps:**

```bash
# Wayland (or: unset/empty XDG_SESSION_TYPE, or rename xdotool/wmctrl/
# xbindkeys/chrome off PATH temporarily, to force the same fallback on X11)
echo $XDG_SESSION_TYPE   # should print "wayland"
./ARKtube-x86_64.AppImage
# Expected: stderr logs "hybrid Chrome embed unavailable... loading
# YouTube directly in ARKtube's own webview instead", and the window
# actually shows youtube.com/tv shortly after — not stuck on "Starting
# ARKtube..." indefinitely. F11/Escape/Home and the on-screen fullscreen/
# Immersive Mode buttons should still work, driven by app-init.js's
# existing fallback keydown handling.

# X11, with xdotool/wmctrl/xbindkeys/chrome all present
echo $XDG_SESSION_TYPE   # should print "x11"
./ARKtube-x86_64.AppImage
# Expected: unchanged from §9 - the hybrid embed still runs, Chrome gets
# reparented in, full Leanback UA spoof still applies.
```

Not verified end-to-end here — no display server in this environment,
same caveat every doc in this tree carries (see §9, `CURSOR-AUTO-HIDE.md`).
What's checked is the control flow: which branch each launcher takes
under which `XDG_SESSION_TYPE`/tool-availability combination, and that
`app-init.js`'s redirect condition only ever fires once per real
navigation (guarded the same way `__neutralinoAppInitialized` already
guards the rest of this file against YouTube's own scripts re-running it).

## 11. Wayland, take two: make the embed itself the default, not just a fallback

§10's fix made Wayland *work* — it just made it work by giving up on the
embed and using window mode's own webview instead, same as the pre-hybrid
architecture from §9. That's a real, permanent trade-off (partial UA
spoof only) for the one case that actually needs it: a Wayland compositor
with no Xwayland at all. But most Wayland compositors in practice (GNOME,
KDE, Sway and other wlroots compositors) run Xwayland alongside the
native protocol specifically so X11 apps keep working — and `xdotool`/
`wmctrl` speak X11, not the Wayland protocol, so they work against
Xwayland exactly the same way they work against a real X11 server. §10's
check (`XDG_SESSION_TYPE = wayland` ⇒ give up) never distinguished "no
Xwayland" from "Xwayland is right there" — it treated every Wayland
session as the rare case instead of the common one.

**What changes here:**

- The embed-possible check (`AppRun`, the `.deb` launcher,
  `embed-chrome.sh`'s own safety-net copy) now tests `DISPLAY` — reachable
  on a real X11 session *and* on Xwayland under Wayland — instead of
  `XDG_SESSION_TYPE`, followed by an actual `xdotool getdisplaygeometry`
  probe to confirm something is really listening on it. `--native-fallback`
  is now reserved for the genuine no-X11-at-all case.
- Chrome gets an explicit `--ozone-platform=x11` in `embed-chrome.sh`.
  Without it, current Chrome defaults to its own native-Wayland ozone
  backend under a Wayland session even with `DISPLAY` set and Xwayland
  reachable — which would hand back a window with no X11 ID at all for
  `xdotool` to find, silently breaking the reparent regardless of the
  `DISPLAY` check passing.
- Neutralino's own window has the same problem in reverse: GTK defaults
  to a native Wayland surface under a Wayland session too, which
  `embed-chrome.sh`'s `xdotool search --name "^ARKtube\$"` would never
  find. `AppRun`/the `.deb` launcher now launch it with `GDK_BACKEND=x11`
  whenever `EMBED_SUPPORTED=1`, forcing it onto the same Xwayland display
  Chrome is about to land on. Inert on a real X11 session, where that's
  already GDK's default backend.
- `xwayland` was added to the `.deb`'s `Recommends` alongside
  `xdotool`/`wmctrl`/`xbindkeys`, for the (increasingly rare, but real)
  minimal-install case where it isn't already pulled in by the desktop
  environment.

**What doesn't change:** actual Wayland-protocol reparenting is still
impossible, full stop — that's a deliberate Wayland design decision, not
a gap this works around. This isn't reparenting over Wayland; it's both
apps agreeing to speak X11 to the same Xwayland server instead, same as
how Firefox/Chrome themselves silently ran under Xwayland by default for
years before their own native-Wayland ozone/GTK backends matured. Runs
with genuinely no Xwayland present still take the §10 native-fallback
path, unchanged.

**Files changed:** `packaging/linux/embed-chrome.sh` (DISPLAY check +
connectivity probe, `--ozone-platform=x11`), `packaging/linux/AppRun`,
`packaging/linux/build-deb.sh` (same detection + `GDK_BACKEND=x11`,
`xwayland` added to `Recommends`).

**Verification steps:**

```bash
# GNOME/KDE Wayland session with Xwayland present (the common case)
echo $XDG_SESSION_TYPE   # "wayland"
echo $DISPLAY             # should be set (e.g. ":0" or ":1") - Xwayland's rootless display
./ARKtube-x86_64.AppImage
# Expected: NO "hybrid Chrome embed unavailable" line. embed-chrome.sh's
# own log lines (waiting for window, launching Chrome, reparenting)
# should appear exactly as they do on a real X11 session, and the app
# should look identical - full Leanback UA, F11/Escape/Home grabbed
# globally, no visible difference from §9's original X11 behavior.

# Wayland with no Xwayland (minimal Sway install, `xwayland` package
# removed/disabled) - the one case that still needs §10's fallback
sudo apt remove xwayland   # or the equivalent for your compositor
echo $DISPLAY              # empty
./ARKtube-x86_64.AppImage
# Expected: "hybrid Chrome embed unavailable (no X11 display/Xwayland...)"
# and YouTube loads directly in ARKtube's own (native Wayland) webview,
# same as §10.
```

Not verified end-to-end here, for the same reason as everywhere else in
this file — no display server in this environment. What's checked is the
control flow and that `DISPLAY`/`xdotool getdisplaygeometry` is a
correct, standard way to detect "an X11 display (real or Xwayland) is
reachable" independent of `XDG_SESSION_TYPE`.

## 12. Snap-confined Chrome/Chromium can't write the profile dir §11 hands it, and fails as a GPU crash instead of a permission error

§11 made the hybrid embed work under Wayland-with-Xwayland, which covers
the overwhelming majority of real desktops. On one of those — a stock
Ubuntu install, Xwayland present, `/usr/bin/chromium` on PATH — a user
still got a hard crash on every launch:

```text
embed-chrome: found Chrome window 6291466, reparenting into 12582913
embed-chrome: xbindkeys grabbing F11/Escape/Home globally (pid 191285)
...
[191274:191797:...:ERROR:content/browser/gpu/gpu_process_host.cc:1029] GPU process launch failed: error_code=1002
(repeats ~6x)
[191274:191274:...:FATAL:content/browser/gpu/gpu_data_manager_impl_private.cc:417] GPU process isn't usable. Goodbye.
embed-chrome: Neutralino window or Chrome process gone; exiting.
```

The reparenting itself worked (§11 is fine) — Chrome's *own* GPU process
died immediately after, repeatedly, until Chromium gave up and exited,
which took the whole embed down with it (`embed-chrome.sh`'s geometry-follow
loop exits as soon as the Chrome PID it's watching disappears).

**Root cause:** on Ubuntu (and most other distros now), `chromium` /
`chromium-browser` isn't a regular binary — `/usr/bin/chromium` is a
thin wrapper around a strictly-confined *snap*. A confined snap's only
route into `$HOME` at all is the `home` interface, which is auto-connected
but — by explicit, documented design, not a bug — refuses read/write
access to **any path with a dot-prefixed component**: `~/.cache/...`,
`~/.config/...`, and `~/.local/...` are all off-limits, snap or no snap
argument on the command line notwithstanding. `CHROME_PROFILE_DIR` in
`AppRun`/the `.deb` launcher is `${ARKTUBE_DATA_DIR}/.tmp/chromedata`,
i.e. `~/.local/share/ARKtube/.tmp/chromedata` — squarely inside `~/.local`.

Handed a `--user-data-dir` it's confined away from, Chromium doesn't fail
with a clean "permission denied" - it silently can't create the profile,
the GPU shader/disk cache, `Local State`, etc, and the GPU process (which
needs to open files under that directory as part of its own init) crashes
on launch instead. Nothing here is Wayland- or Xwayland-specific — the
same crash happens on a real X11 session too, with the same snap-confined
binary and the same dot-prefixed profile path; it just so happened the
report that caught this was on a Wayland/Xwayland machine, right after §11
shipped, which made it look at first like a new Wayland-specific failure
mode. It reads like a GPU/driver bug (the actual log output never mentions
snap, confinement, or the profile directory at all) but isn't one — the
exact same browser, run standalone outside ARKtube with its own default
profile location, plays video with GPU acceleration on the same machine
without issue.

**The fix:** `AppRun` and the `.deb` launcher's embedded script now resolve
the chosen Chrome/Chromium candidate's real path (`command -v` +
`readlink -f`) right after picking it, and check whether it lives under
`/snap/`. If it does, `CHROME_PROFILE_DIR`/`CHROME_LOCK_PATTERN` are
redirected to `~/snap/<snap-name>/common/arktube-chromedata` —
`SNAP_USER_COMMON`, the one location every version of that snap is always
allowed to read and write, persisted across snap revisions, and created
automatically by snapd the first time the snap itself runs (the launcher
also `mkdir -p`s it defensively, in case the snap has never been launched
standalone). `reap_stale_chrome` is re-run immediately after the redirect,
since its first, proactive call — before the redirect logic runs — still
checked the old, pre-redirect path.

Non-snap Chrome/Chromium (a `.deb`-installed `google-chrome-stable`, or a
distro that ships an unconfined `chromium` package) is unaffected —
`CHROME_REAL_BIN` won't resolve under `/snap/` and `CHROME_PROFILE_DIR`
is left exactly as §9–§11 already had it.

**Files changed:** `packaging/linux/AppRun`, `packaging/linux/build-deb.sh`
(same redirect logic in both, since the `.deb`'s launcher is a heredoc
copy of AppRun's, not a shared script).

**Verification steps:**

```bash
# Snap Chrome/Chromium (the common Ubuntu case) - the actual bug
which chromium              # -> /usr/bin/chromium
readlink -f "$(which chromium)"   # -> /snap/chromium/<rev>/usr/lib/.../chrome
./ARKtube-x86_64.AppImage
# Expected: stderr logs "chromium resolves to a snap (...) - using
# ~/snap/chromium/common/arktube-chromedata as its profile dir instead
# of .../.local/share/ARKtube/.tmp/chromedata". No GPU process
# crash-loop, no FATAL, YouTube loads and plays with the embed intact.

# Non-snap Chrome/Chromium (.deb-installed google-chrome-stable, or an
# unconfined chromium package on a non-Ubuntu distro) - unaffected path
readlink -f "$(which google-chrome-stable)"   # -> somewhere under /opt or /usr, not /snap
./ARKtube-x86_64.AppImage
# Expected: no snap-redirect log line at all; CHROME_PROFILE_DIR stays
# ~/.local/share/ARKtube/.tmp/chromedata exactly as before, unchanged
# from §9-§11 behavior.
```

Not verified end-to-end here, for the same reason as everywhere else in
this file — no display server, and no snap tooling, in this environment.
What's checked is the control flow: that the `/snap/` path-prefix test
correctly distinguishes a snap-confined binary from a regular one, and
that the redirected `SNAP_USER_COMMON` path matches what snapd itself
grants a confined snap unconditional access to (per the `home` interface's
documented dot-path exclusion, which is what actually blocks the original
path — independently confirmed against multiple `chromium-browser`
snap-confinement reports, not specific to ARKtube).

## 13. Neutralino's own `extendUserAgentWith` value was itself invalid, silently

Separately from §12's crash, the same log carried one more line worth
fixing even though it never brought anything down:

```text
** (ARKtube:191252): CRITICAL **: 00:45:06.644: void webkit_settings_set_user_agent(WebKitSettings *, const char *): assertion 'WebCore::isValidUserAgentHeaderValue(userAgentString)' failed
```

This fires on Neutralino's *own* WebKitGTK window (the small local
backdrop page in hybrid mode; the actual, direct-load webview under the
§10 native-fallback path) — nothing to do with the embedded Chrome
process from §12, and not what caused that crash. But it means the
`modes.window.extendUserAgentWith` value in `neutralino.config.json`
(the README's fallback (1), and the only thing that fires at all under
native-fallback with no Chrome/embed available) was never actually being
applied — WebKitGTK's own validator (added upstream specifically to stop
apps setting header values HTTP forbids — see webkit.org/b/201077)
rejects it outright and the call is a no-op.

**Root cause:** WebKit validates a user-agent value against RFC 7231's
`product-list` grammar: `product *( RWS ( product / comment ) )`, where a
`comment` is text in parentheses and everything *outside* parentheses has
to parse as a bare `token["/"version]`. The configured value was:

```text
 Mozilla/5.0 (PS4; Leanback Shell) Cobalt/26.lts.0-qa; compatible;
```

`Cobalt/26.lts.0-qa` parses fine as a product, but the `; compatible;`
tacked on after it is bare text with a semicolon, outside any
parentheses — not a valid product and not a valid comment, so the whole
string is rejected by `isValidUserAgentHeaderValue()` before WebKitGTK
ever touches it. (The leading space is fine — this fragment is meant to
be appended after WebKitGTK's own default UA, per the README's own
worked example, so it's acting as the `RWS` separator between the last
product of the default UA and the first product of this extension.)

**The fix:** wrap the trailing `compatible` marker in parentheses, same
as the existing `(PS4; Leanback Shell)` comment, instead of leaving it as
bare unparenthesized text:

```text
 Mozilla/5.0 (PS4; Leanback Shell) Cobalt/26.lts.0-qa (compatible)
```

This keeps every token the previous value was trying to convey (device
family, shell, Cobalt version, a "compatible" marker) while actually
parsing as valid `product *( RWS ( product / comment ) )`, so
`extendUserAgentWith` does what the README already claims it does.

**Files changed:** `neutralino.config.json`
(`modes.window.extendUserAgentWith` only — the `chrome` mode's
`args`/`--user-agent` and `embed-chrome.sh`'s own `CHROME_UA` are set
directly on a real Chrome-family process via a command-line flag, which
Chromium does not run through WebKit's HTTP-header-value validator, so
neither was actually broken; left as-is to keep this change minimal).

**Verification steps:**

```bash
./ARKtube-x86_64.AppImage
# Expected: no "assertion 'WebCore::isValidUserAgentHeaderValue(...)'
# failed" CRITICAL in stderr. With no embed available (§10's
# native-fallback path), check the UA ARKtube's own webview is actually
# sending: open the inspector (enableInspector is already true) and
# evaluate `navigator.userAgent` - it should now end in
# "... Cobalt/26.lts.0-qa (compatible)", confirming the extension made
# it through where it silently didn't before.
```

Not verified end-to-end here, for the same reason as everywhere else in
this file — no display server in this environment. What's checked is
that the corrected string parses under RFC 7231's `product-list` grammar
(the same grammar WebKit's `isValidUserAgentHeaderValue()` implements),
by hand-tracing the same token/comment rules the WebKit changeset that
introduced this validator documents.

## 14. Same GPU-process crash as §12, different (more common) cause: AppArmor userns restriction, not snap - hits plain .deb Chrome too

§12 fixed the crash for the specific case where the Chrome/Chromium
binary on PATH is a snap. A follow-up report on a machine with **no
snap involved at all** - `chromium` there is a normal `.deb`-installed
binary, not `/snap/bin/chromium` - hit the exact same symptom:

```text
embed-chrome: found Chrome window ..., reparenting into ...
[...:ERROR:content/browser/gpu/gpu_process_host.cc:...] GPU process launch failed: error_code=1002
(repeats)
[...:FATAL:content/browser/gpu/gpu_data_manager_impl_private.cc:...] GPU process isn't usable. Goodbye.
```

§12's `/snap/*` path check correctly does nothing here (the resolved
binary path never matches it), which is right - it isn't a snap
confinement problem this time - but that also means it does nothing
*for* this user, and the crash remains.

**Root cause:** `error_code=1002` on Linux almost always means the same
thing regardless of who packaged the browser: a utility process -
including the GPU process - failed to set up its own sandbox at launch.
Since Ubuntu 23.10 (default from 24.04 LTS onward),
`kernel.apparmor_restrict_unprivileged_userns=1` blocks the *unprivileged
user namespace* sandbox Chromium uses by default on modern kernels,
**unless** the exact binary being launched has a matching AppArmor
profile explicitly granting it `userns` - see
`chromium.googlesource.com/.../apparmor-userns-restrictions.md`, which
Chromium's own zygote-launch failure message links to directly. Recent
official Google Chrome `.deb` builds ship such a profile in their
postinst; a plain distro `chromium`/`chromium-browser` package,
especially on an older release stream, older Chrome build, or PPA,
frequently does not - and unlike §12, there's no path-prefix trick to
detect this in advance, since it depends on packaging details a resolved
binary path alone doesn't reveal. (§12 and this section are two
different browsers hitting the same visible symptom for two unrelated
reasons - worth keeping both fixes, since either can be the one that
actually applies on a given machine.)

**The fix:** `embed-chrome.sh`'s `CHROME_ARGS` now always includes
`--disable-gpu-sandbox` - the specific, narrow mitigation Chromium's own
engineers recommend first when triaging this exact `error_code=1002` /
"GPU process isn't usable" failure (see the upstream issue thread linked
in the verification steps below): it drops sandboxing for the GPU
process only, not every process (`--no-sandbox` does that, and stays
deliberately unused here). The GPU process doesn't execute untrusted
page script - that's the renderer process's job, and its own,
separate sandbox is untouched by this flag - so this is a materially
smaller concession than blanket `--no-sandbox`, while still fixing the
crash whether the underlying cause turns out to be the AppArmor
restriction above, a snap edge case §12's redirect didn't happen to
catch, or some other sandbox-setup failure with the same symptom.

**Files changed:** `packaging/linux/embed-chrome.sh` (`CHROME_ARGS` only).

**Verification steps:**

```bash
# Check whether this is the AppArmor-userns case specifically (informational only)
sysctl kernel.apparmor_restrict_unprivileged_userns
# 1 on Ubuntu 23.10+/24.04+ by default = the restriction described above is active

./ARKtube-x86_64.AppImage
# Expected: no "GPU process launch failed: error_code=1002" loop, no
# "GPU process isn't usable. Goodbye." FATAL, and the embedded Chrome
# window survives instead of the whole embed exiting seconds after
# reparenting.
```

Not verified end-to-end here, for the same reason as everywhere else in
this file — no display server in this environment. What's checked is
that `--disable-gpu-sandbox` is a real, documented Chromium flag (not a
guess) and that it's what Chromium's own team suggests as the first
diagnostic/mitigation step for this specific error code on
`issues.chromium.org`, independent of what's packaging the browser.
