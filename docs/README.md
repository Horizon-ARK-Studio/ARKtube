# ARKtube Webtop — Docs

**Branch:** `webtop`
**Scope:** session integration only — not the ARKtube application

This is not a copy of `main`'s `docs/`. This branch is not the application.
It's the thin session layer that gets an already-installed ARKtube onto the
Ubuntu login screen, so it can be entered directly instead of being launched
after landing on a normal desktop.

## Read this first

The repo root [`README.md`](../README.md) is the primary reference for this
branch. It lays out the goal (`Gear → ARKtube → native ARKtube session`),
what Webtop does and doesn't own, the session lifecycle, and window policy.
Start there before these docs.

## What lives here

| Directory | Contents |
|---|---|
| [`foundational/`](foundational/README.md) | Why Webtop exists as a separate session layer, not just a fullscreen window |
| [`bugs-caught/`](bugs-caught/README.md) | Session/kiosk-specific bugs found and fixed — currently empty |

## Stage docs

| Doc | Covers |
|---|---|
| [`STAGE-1-SELECTABLE-SESSION.md`](STAGE-1-SELECTABLE-SESSION.md) | Stage 1 implementation record: the exact `gnome-kiosk-script-session` package contents as verified from its `.deb`, the gear-menu naming gap that fix corrects, and the confirmed (not assumed) GNOME Kiosk 46 restart-loop risk carried into Stage 2 |
| [`STAGE-2-SESSION-LIFECYCLE.md`](STAGE-2-SESSION-LIFECYCLE.md) | Stage 2 implementation record: the restart-loop and logout-hang fixes, confirmed against the actual shipped systemd units and `.desktop` files and against upstream's own fixes for both in GNOME Kiosk 50; and why locking is a real, currently-unimplemented platform gap rather than an oversight |
| [`STAGE-3-INPUT-MAPPING.md`](STAGE-3-INPUT-MAPPING.md) | Stage 3 implementation record: the exact keys/buttons `main`'s `app-init.js` needs, confirmed against the actual default GSettings keybinding schemas and the `gnome-kiosk` binary to show none of them are grabbed by the compositor; and a correction to the root README's `--enable-vt-switch` line, which describes GNOME Kiosk 50 behavior that Noble's `46.0-1build2` doesn't have |
| [`STAGE-4-HARDENING.md`](STAGE-4-HARDENING.md) | Stage 4 implementation record: an explicit `KillMode=control-group` pin so cleanup of `main`'s detached chrome-mode process doesn't rest on an implicit systemd default; and why "developer tooling disabled" is currently blocked on `main` (chrome mode is the actively-shipped default, with no browser-hardening flags), not fixable from the session layer |
| [`STAGE-5-DOCUMENTATION-HANDOFF.md`](STAGE-5-DOCUMENTATION-HANDOFF.md) | Stage 5 implementation record: confirms every file `install-webtop-session.sh` deploys is committed (not just described), that `bugs-caught/` is still accurately empty rather than backfilled, and corrects two root-README lines that described GNOME Kiosk 50 behavior without noting this branch targets and reproduces it for Noble's older `46.0-1build2` |
| [`STAGE-6-OFFLINE-TOPBAR.md`](STAGE-6-OFFLINE-TOPBAR.md) | Stage 6 implementation record: a pywebview-based system topbar (clock, battery, volume, brightness, Wi-Fi, lock, logout, power) that works with no network connection, launched from `gnome-kiosk-script` so it rides Stage 4's existing `KillMode=control-group` cleanup instead of a second lifecycle |

## How this relates to `main`

`main` builds ARKtube itself — the Neutralino app that shows YouTube in a
persistent desktop window. This branch assumes that app is already built
and installed. It doesn't rebuild it, fork its code, or duplicate its docs.

Worth noting what `main` already gets you on its own: F11 inside ARKtube
already gets close to a media-center experience — fullscreen, the
`youtube.com/tv` leanback interface, controller/remote input mapped to the
same arrow-key navigation a keyboard uses. For someone who mostly wants
YouTube and uses it a lot, that's arguably already most of what "YouTube TV"
as a device gives you.

Webtop's job sits one level up the stack from that, and is narrower: get
from **Ubuntu's login screen** into **that already-fullscreen ARKtube**
directly — so ARKtube can be *chosen as a session*, the same way you'd pick
"Ubuntu on Wayland" from the gear icon, rather than something you open after
you're already on a desktop. It launches the installed app. It does not
reimplement it.

## Non-goals of this docs tree

* Documenting ARKtube's own UI, player, or navigation behavior — that's
  `main`'s job.
* Duplicating `main`'s `PROBLEM-STATEMENT.md` or `SYSTEM-DESIGN-AGREEMENT.md`
  — this branch has its own, narrower problem statement (see
  `foundational/`).
