# ARKtube — Docs

**Branch:** `main`
**Scope:** the ARKtube application itself — currently the native
GTK3 + WebKit2GTK Linux app in `arktube_linux/`, and how it's built and
packaged.

This is not the `webtop` branch's docs. That branch is a separate, thin
session-integration layer (getting an already-installed ARKtube onto the
Ubuntu login screen) with its own `docs/README.md`. This index only covers
what `main` actually builds.

Most of the docs below (`PROBLEM-STATEMENT.md`, `BUGS-CAUGHT.md`,
`IMMERSIVE-MODE.md`, `CURSOR-AUTO-HIDE.md`, `ARKtube-Assistant-Proposal.md`)
were written against an earlier Neutralino-based shell that has since been
removed from `main` and replaced by `arktube_linux/`. They're kept as
historical implementation/design records — each now says so at the top —
rather than being rewritten from scratch, since the reasoning and most of
the ported logic (idle cursor hide, gamepad remap, the GStreamer/hardware-
acceleration notes) still applies to the current native app.

## Read this first

The repo root [`README.md`](../README.md) and
[`arktube_linux/README.md`](../arktube_linux/README.md) are the primary,
up-to-date references for this branch. They cover the project's goal,
what it's built with, its current feature set, and how to build it.
Start there before these docs.

## What lives here

| Doc | Covers |
|---|---|
| [`PROBLEM-STATEMENT.md`](PROBLEM-STATEMENT.md) | The original design document: objective, scope, and what "installable desktop app that behaves like YouTube already looks" means in practice. Written against the earlier Neutralino shell; the underlying goal is unchanged in `arktube_linux/` |
| [`SYSTEM-DESIGN-AGREEMENT.md`](SYSTEM-DESIGN-AGREEMENT.md) | The recurring architectural rule behind this project's worst bugs: who is allowed to own what, between this app's native layer and the WebView/Chromium runtime it sits on top of. Still the operative rule for `arktube_linux/` |
| [`BUGS-CAUGHT.md`](BUGS-CAUGHT.md) | AppImage crash and playback-quality fix record from the old Neutralino shell: the `resources.neu`/`.tmp` launch crash, degraded subtitle/video playback from missing GStreamer plugins, and the chrome-mode detached-process cleanup issues that followed from it. The GStreamer/hardware-acceleration diagnosis still applies to `arktube_linux/`; the AppImage/Neutralino-specific fixes don't |
| [`IMMERSIVE-MODE.md`](IMMERSIVE-MODE.md) | Implementation record for the old shell's Immersive Mode button: why F11 didn't have two competing handlers, the ownership split between the untrusted in-page script and the trusted packaging launcher. Not yet ported to `arktube_linux/` — see that app's README "Not yet ported" list |
| [`CURSOR-AUTO-HIDE.md`](CURSOR-AUTO-HIDE.md) | Implementation record for idle cursor auto-hide, originally in the old shell's `app-init.js`; the same logic now lives in `arktube_linux/resources/js/user-script.js`. Covers why it lives here rather than in `webtop`, how it avoids reacting to gamepad-synthesized key events, and what it does and doesn't replace on the X11 session |

## Non-goals of this docs tree

* Documenting the `webtop` session/compositor layer — that's `webtop`'s own
  `docs/`, not this branch's concern.
* Duplicating build instructions already covered in the root `README.md`
  and `arktube_linux/README.md`.
