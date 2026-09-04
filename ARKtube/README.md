# ARKtube (Stage 0)

Neutralinojs project for the ARKtube MVP. This is **Stage 0 / Phase 0** from
[`docs/PROBLEM-STATEMENT.md`](../docs/PROBLEM-STATEMENT.md): no custom UI, no
navigation controller yet — the only job right now is proving that
`https://www.youtube.com` loads and behaves inside Neutralino's native WebView.

## What's here

`neutralino.config.json` is set to load YouTube directly (no local `index.html`
involved):

```json
"url": "https://www.youtube.com/tv"
```

This targets YouTube's TV-optimized ("10-foot") interface instead of the
regular desktop site. It's the same interface Google's own Cobalt engine
renders on certified smart TVs — Cobalt is essentially "a shell + youtube.com/tv,"
which is the same pairing this project is going for, just built on Neutralino's
native webview instead of a bundled Chromium fork. It's a natural fit for a
persistent, full-screen, keyboard/remote-driven desktop app, and it sidesteps
some of the desktop site's heavier single-page-app navigation chrome that
Stage 0 was already flagging as a risk (see §22/§27 in the design doc).

Two shell-side changes went with it:
- Default window size is now 1280x720 (16:9) instead of 1280x800, matching the
  TV UI's expected aspect ratio.
- `F11` toggles fullscreen and `Escape` exits fullscreen (without quitting the
  app), since the TV interface is meant to be driven full-screen.

Everything else about Stage 0 — no bridge exposed, `enableNativeAPI: false`
posture toward the page — is unchanged.

- `enableNativeAPI: false` and a minimal `nativeAllowList` — YouTube is treated
  as untrusted content (see design doc §23 Security). No bridge is exposed yet.
- Window mode targeted, per project decision to go native-webview-first rather
  than spawning Chrome in app mode.
- `bin/` ships prebuilt Neutralino binaries for Linux (x64/arm64/armhf), macOS
  (x64/arm64/universal), and Windows (x64) — Linux is the current test target.

## Prerequisites (Linux)

Neutralino's Linux binary needs **WebKitGTK** installed on the system — it is
**not** bundled and not always present by default:

```bash
# Debian/Ubuntu
sudo apt-get update
sudo apt-get install -y libwebkit2gtk-4.1-0
# older distros may need libwebkit2gtk-4.0-37 instead
```

Also install the Neutralino CLI if you don't have it:

```bash
npm install -g @neutralinojs/neu
```

## Run it

```bash
cd ARKtube
neu run
```

This launches the prebuilt binary and opens a native window pointed at
youtube.com.

## What to check (Stage 0 checklist, from the design doc §7 & §27)

- [ ] Does the homepage load and render correctly?
- [ ] Does Google login work, or does it get blocked
      (`Error 403: disallowed_useragent` is the classic WebView rejection)?
- [ ] Does video playback work (codecs / DRM via WebKitGTK on your distro)?
- [ ] Do cookies/session persist across app restarts?
- [ ] Does search work?
- [ ] Does internal navigation (home → search → watch → channel) work at all,
      even as full page reloads?
- [ ] Does fullscreen work?

Report back what breaks — per the design doc's failure-mode section (§22),
each failure has a defined fallback (e.g. if Google blocks the WebView user
agent, the next experiment is `window.extendUserAgentWith` in the config
before reaching for `browser`/Chrome mode).

**Specific risk with `/tv`:** unlike the desktop site, `youtube.com/tv` is
normally only served to recognized TV/set-top-box user agents — a plain
WebKitGTK UA may get redirected back to `youtube.com` or shown an
unsupported-device page.

Two fallbacks are already wired for this, in the order to try them:

1. **`modes.window.extendUserAgentWith`** (set in `neutralino.config.json`)
   appends a Cobalt/PS4 device-token suffix to WebKitGTK's *default* UA
   string. This is a config-only, no-rebuild change, but be clear about
   what it actually produces: Neutralino's `extendUserAgentWith` **appends**
   to the platform UA, it does not replace it. The resulting string is a
   genuine desktop-Linux WebKitGTK UA with TV tokens bolted onto the end
   (`Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/605.1.15 ... Safari/605.1.15
   (PS4; Leanback Shell) Cobalt/26.lts.0-qa (compatible)`), not a clean
   Cobalt UA. Whether that's enough depends entirely on whether YouTube's
   TV-client check does a loose substring match for `Cobalt/` /
   `PlayStation` anywhere in the string, or a stricter check that's
   thrown off by the leading `X11; Linux` / `AppleWebKit` desktop tokens
   still being present. Try it first because it's free; don't assume it
   works without checking DevTools/network output.

2. **`chrome` mode**, if (1) doesn't get past detection. Its `args` field
   passes straight through to the underlying Chrome-family process's
   `--user-agent=` flag, which *fully replaces* the UA rather than
   appending to it — mechanically the same thing a bare
   `chromium --user-agent="..." --app="https://www.youtube.com/tv#/"`
   invocation does. This is the config's pre-wired fallback and is already
   set to the same Cobalt/PS4 string.

   A true full-replace *inside* native `window` mode (equivalent to
   calling WebKitGTK's `webkit_settings_set_user_agent()` before the
   webview is created) is **not** exposed through `neutralino.config.json`
   or app-side JS — Neutralino's binary is precompiled, and getting that
   call made would mean patching Neutralino's own C++ source and building
   a custom binary. That's a real option if both fallbacks above fail, but
   it's a materially bigger undertaking than a config change, and per the
   project's own failure-mode policy (`docs/PROBLEM-STATEMENT.md` §22:
   "fail gracefully instead of trying to impersonate a browser
   indefinitely"), the right next step at that point is falling back to
   the desktop `url`, not building and maintaining a forked Neutralino
   binary just to keep `/tv` working.

## Known constraints found while scaffolding this

- WebKitGTK is a runtime dependency that must be installed separately on
  Linux; it's not something `neu run` fetches for you.
- This was scaffolded and smoke-tested in a sandboxed container with no route
  to the public internet, so the WebKit window itself was confirmed to launch
  and navigate, but real-world YouTube compatibility (login, playback, DRM)
  has **not** been verified yet — that's your job for this Stage 0 run.
