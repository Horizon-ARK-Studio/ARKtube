# ARKtube — Docs

**Branch:** `main`
**Scope:** the ARKtube application itself — the Neutralino shell around
`youtube.com/tv`, and how it's built and packaged for each platform.

This is not the `webtop` branch's docs. That branch is a separate, thin
session-integration layer (getting an already-installed ARKtube onto the
Ubuntu login screen) with its own `docs/README.md`. This index only covers
what `main` actually builds.

## Read this first

The repo root [`README.md`](../README.md) is the primary reference for this
branch. It covers the project's goal, what's built with, and how to build
each platform's package. Start there before these docs.

## What lives here

| Doc | Covers |
|---|---|
| [`PROBLEM-STATEMENT.md`](PROBLEM-STATEMENT.md) | The original design document: objective, scope, and what "installable desktop app that behaves like YouTube already looks" means in practice |
| [`SYSTEM-DESIGN-AGREEMENT.md`](SYSTEM-DESIGN-AGREEMENT.md) | The recurring architectural rule behind this project's worst bugs: who is allowed to own what, between this app's native layer and the WebView/Chromium runtime it sits on top of |
| [`BUGS-CAUGHT.md`](BUGS-CAUGHT.md) | AppImage crash and playback-quality fix record: the `resources.neu`/`.tmp` launch crash, degraded subtitle/video playback from missing GStreamer plugins, and the chrome-mode detached-process cleanup issues that follow from it |
| [`IMMERSIVE-MODE.md`](IMMERSIVE-MODE.md) | Implementation record for the Immersive Mode button: why F11 no longer has two competing handlers, the ownership split between the untrusted in-page script and the trusted packaging launcher, why real Chrome-side hardening can't apply mid-session, and what's still not done (Windows/macOS launcher parity) |

## Non-goals of this docs tree

* Documenting the `webtop` session/compositor layer — that's `webtop`'s own
  `docs/`, not this branch's concern.
* Duplicating platform-specific build instructions already covered in the
  root `README.md`'s "Development" section.
