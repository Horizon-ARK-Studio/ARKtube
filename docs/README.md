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
