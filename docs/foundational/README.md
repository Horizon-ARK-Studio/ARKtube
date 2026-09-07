# Foundational docs

Why this branch exists, and what it's actually built on now.

`arktube-layer-shell` is the compositor-layer implementation of the
migration `webtop`'s `docs/foundational/CAGE-MIGRATION.md` proposed:
get ARKtube and its TV-style system overlay running under a minimal
Wayland compositor instead of GNOME Kiosk. That migration was staged
toward Cage. This branch tried Cage, proved the approach, and then
moved on to Sway for reasons of its own — see below.

| Doc | Covers |
|---|---|
| [`PROBLEM_STATEMENT.md`](PROBLEM_STATEMENT.md) | The problem this branch solves — hosting ARKtube fullscreen with a working `wlr-layer-shell-v1` surface for the overlay — and the history of how it was solved: a hand-written layer-shell implementation on top of a Cage fork first, then a full switch to Sway once that fork's maintenance cost outgrew its benefit. |
| [`SYSTEM_DESIGN.md`](SYSTEM_DESIGN.md) | The current design: what Sway is configured to do, why `wlr-layer-shell-v1` is the right protocol for the overlay, how idle-inhibit and session-readiness signaling work, and what still counts as this layer's responsibility vs. `webtop`'s or `main`'s. |

## Reading order

Start with `PROBLEM_STATEMENT.md` for the *why* — including the parts
that turned out to be wrong (GNOME Kiosk, then a hand-maintained Cage
fork) before arriving at Sway. Then read `SYSTEM_DESIGN.md` for the
*what* — the design as it stands today, without re-deriving the
history each time.

## How this relates to other branches

```text
main     — the ARKtube application itself
webtop   — Ubuntu session integration (GDM, gear menu, lock/logout)
layer-shell (this branch) — the compositor underneath both of the above
```

`webtop`'s own `docs/foundational/` documents *why* a dedicated
session exists at all, and *why* it should run on a minimal compositor
rather than full GNOME Shell. This branch doesn't re-argue that; it
picks up exactly where `CAGE-MIGRATION.md` leaves off and documents
what actually got built.

## Non-goals of this docs tree

* Re-documenting `webtop`'s session-lifecycle or input-mapping work —
  that's `webtop`'s `docs/STAGE-*` series, rebuilt for Sway where
  needed, not duplicated here.
* Re-documenting ARKtube's own application behavior — that's `main`'s
  `docs/foundational/`.
* Advocating for a compositor choice beyond the one already made. If a
  future need reopens that question, it gets a new doc, not edits to
  the history in `PROBLEM_STATEMENT.md`.
