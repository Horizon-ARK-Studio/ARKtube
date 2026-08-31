# youtube-desktop (Stage 0)

Neutralinojs project for the YT-Desktop MVP. This is **Stage 0 / Phase 0** from
[`docs/PROBLEM-STATEMENT.md`](../docs/PROBLEM-STATEMENT.md): no custom UI, no
navigation controller yet — the only job right now is proving that
`https://www.youtube.com` loads and behaves inside Neutralino's native WebView.

## What's here

`neutralino.config.json` is set to load YouTube directly (no local `index.html`
involved):

```json
"url": "https://www.youtube.com"
```

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
cd youtube-desktop
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

## Known constraints found while scaffolding this

- WebKitGTK is a runtime dependency that must be installed separately on
  Linux; it's not something `neu run` fetches for you.
- This was scaffolded and smoke-tested in a sandboxed container with no route
  to the public internet, so the WebKit window itself was confirmed to launch
  and navigate, but real-world YouTube compatibility (login, playback, DRM)
  has **not** been verified yet — that's your job for this Stage 0 run.
