# ARKtube Layer-Shell — Docs

**Branch:** `arktube-layer-shell`
**Scope:** the Wayland compositor layer only — not the ARKtube
application, and not Ubuntu session integration.

This branch sits between `webtop` and the display. `webtop` gets
ARKtube selectable as a session from Ubuntu's login screen and owns
lock/logout/lifecycle. `main` builds ARKtube itself. This branch
provides what runs underneath both: the compositor that shows ARKtube
fullscreen and gives the TV-style system overlay a real surface to
draw itself onto.

## Read this first

The repo root [`README.md`](../README.md) is the primary, up-to-date
reference for this branch — it's written for Sway as the shipped
compositor, and covers configuration, session integration, and
requirements in full. Start there before these docs.

## What lives here

| Directory | Contents |
|---|---|
| [`foundational/`](foundational/README.md) | Why this branch exists, and its history — a hand-maintained Cage fork with custom layer-shell support, replaced by Sway once that maintenance cost outgrew its benefit |
| [`bugs_caught/`](bugs_caught/README.md) | Compositor-layer bugs found and fixed — currently empty; also serves as the template for logging one |

## How this relates to `main` and `webtop`

```text
main     — the ARKtube application (GTK3 + WebKit2GTK, on Linux)
webtop   — Ubuntu session integration: GDM, the gear menu, lock/logout
layer-shell (this branch) — the compositor: Sway, layer-shell, idle-inhibit
```

Each branch has its own `docs/`, scoped to its own layer. This tree
doesn't duplicate `main`'s application docs or `webtop`'s session-
lifecycle docs (`STAGE-1` through `STAGE-8`) — it only covers what
changes when the compositor underneath both of them changes.

Where the two intersect: `webtop`'s Stage 8 overlay
(`docs/STAGE-8-TV-STYLE-OVERLAY.md` on that branch) is what actually
uses the `wlr-layer-shell-v1` support this branch provides. This
branch documents the protocol layer; `webtop` documents what's drawn
on top of it.

## Non-goals of this docs tree

* Documenting ARKtube's own UI, player, or navigation behavior —
  that's `main`'s job.
* Documenting session selection, lock, logout, or GDM integration —
  that's `webtop`'s job.
* Carrying forward GNOME Kiosk or Cage as live options — both were
  tried and superseded; see `foundational/PROBLEM_STATEMENT.md` for
  why, kept as history rather than something this tree treats as
  current.
