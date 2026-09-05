# Foundational docs

Design intent behind the `webtop` branch — why this needs to be a separate
session at all, rather than just fullscreening ARKtube on a normal desktop.

| Doc | Covers |
|---|---|
| [`PROBLEM-STATEMENT.md`](PROBLEM-STATEMENT.md) | The problem this branch solves: entering a native ARKtube session directly from Ubuntu's login screen (gear icon → ARKtube), without booting the full GNOME Shell desktop first. Covers the GDM / GNOME Kiosk / Webtop / Neutralino / ARKtube responsibility boundary, session lifecycle (lock, unlock, logout), why the gear icon is the right entry point, and non-goals. |
| [`CAGE-MIGRATION.md`](CAGE-MIGRATION.md) | Why this branch is moving off GNOME Kiosk to Cage, a minimal single-application Wayland compositor — the same "reached for the GNOME default instead of the right shape" mistake that also produced Stage 6/7's GNOME-styled topbar (see `docs/STAGE-8-TV-STYLE-OVERLAY.md`), applied here to the compositor layer instead of the UI layer. Documentation and staged plan only — no code has switched compositors yet. |

This plays the same role as `main`'s `docs/PROBLEM-STATEMENT.md` in the
sense that both are "why does this exist" documents written before the
implementation — but the content doesn't overlap. `main`'s problem
statement is about making YouTube behave like an installed application.
This one is about making an already-installed application selectable as an
Ubuntu session. Different layer, different problem.
