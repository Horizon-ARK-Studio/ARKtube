# Stage 3 — Input mapping

**Status:** Implemented. No new session configuration was required — this
stage's work is verification, plus one correction to a stale assumption
about GNOME Kiosk's default hardening. Where Stage 1 and Stage 2 each
shipped a fix, Stage 3's finding is that nothing needs to be shipped: the
keys ARKtube needs already reach it, unmodified, on the exact package
versions Stage 1/2 already established as the real target.
**Stage definition:** `docs/foundational/STAGED-IMPLEMENTATION.md`, Stage 3.
**Built on:** `docs/STAGE-1-SELECTABLE-SESSION.md` and
`docs/STAGE-2-SESSION-LIFECYCLE.md` — same Ubuntu 24.04 Noble base those
verified against, confirmed again here via `/etc/os-release`.

## What ARKtube actually needs (enumerated, not guessed)

Rather than assume which keys matter, `main`'s
`resources/js/app-init.js` was read directly. It handles input in two
places:

1. A native `keydown` listener that acts on three keys: `F11` (toggle
   fullscreen), `Escape` (exit fullscreen only — never quits the app), and
   `Home` (navigate the `youtube.com/tv` SPA back to `#/`).
2. A Gamepad-API poller that re-dispatches D-pad/stick/face-button input as
   synthetic `keydown` events using the *same* key values: `ArrowUp`,
   `ArrowDown`, `ArrowLeft`, `ArrowRight`, `Enter`, `Escape`, and `Home` —
   so any controller or HID-reporting remote drives the same
   `youtube.com/tv` navigation a keyboard does.

Combined, the full set this stage needs to confirm reaches ARKtube
unmolested is: **arrow keys, Enter, Escape, Home, and F11** — all as bare
key presses, no modifier held.

## What was verified, and how

Same approach as Stage 1 and Stage 2: rather than reason from
documentation describing GNOME Kiosk in general, the exact packages this
deployment target actually installs were pulled with `apt-get download`
(`gnome-kiosk` and `gnome-kiosk-script-session`, both `46.0-1build2`,
matching what Stage 1/2 already confirmed Noble ships) and inspected
directly, then `gnome-kiosk` was actually installed so `--help-all` and
the live GSettings schema defaults could be checked rather than assumed
from changelog prose.

### The keybinding surface that could compete for these keys

Three layers were checked, all from the actual installed schemas/binary,
not from documentation:

| Layer | Checked | Result |
|---|---|---|
| `org.gnome.desktop.wm.keybindings` (`gsettings-desktop-schemas` `46.1-0ubuntu1`) | Every default binding value | `toggle-fullscreen` has **no default binding at all** (`@as []`); `panel-run-dialog` is `<Alt>F2`; every workspace-switch binding requires `<Control><Alt>` or `<Super>`. None is a bare arrow key, `Enter`, `Escape`, `Home`, or `F11`. |
| `org.gnome.mutter` / `org.gnome.mutter.keybindings` | Every default binding value | `cancel-input-capture` is `<Super><Shift>Escape` (not bare `Escape`); `toggle-tiled-left/right` are `<Super>Left/Right` (not bare arrows). Nothing binds a bare key ARKtube uses. |
| `org.gnome.settings-daemon.plugins.media-keys` | Every default binding value | No entry uses any of `F11`, `Home`, `Escape`, `Return`/`KP_Enter`, or the arrow keys. |

Separately, the `gnome-kiosk` binary itself (`46.0-1build2`, read via
`strings`, not source) contains the log line
`KioskCompositor: Neutering builtin keybindings` — confirming GNOME
Kiosk additionally disables a small, specific set of builtin bindings at
the compositor level, independent of the GSettings schemas above. That
code path traces to an old upstream fix (GNOME Kiosk merge request
`!7`, "compositor: Ignore some of the builtin keybindings") that stops a
Wayland crash tied to the run-dialog binding (`<Alt>F2`) and turns off a
few other bindings "that aren't so useful to GNOME Kiosk." None of that
overlaps ARKtube's key set either — it's the same `<Alt>F2`-class binding
already absent from ARKtube's needs, not the arrow/Enter/Escape/Home/F11
set.

The `gnome-kiosk` package's own dconf overlay
(`/usr/share/dconf/profile/gnomekiosk` →
`gnomekiosk.dconf.compiled`, applied automatically — the binary has
`DCONF_PROFILE`/`gnomekiosk` compiled in, no external env var needed) was
also read directly rather than assumed empty: it contains exactly two
keys, both cosmetic (`primary-color`, `picture-options` for the
background). No keybinding overrides ship in it.

### Correction — the root README's VT-switch line is describing a later GNOME Kiosk

The root `README.md`'s "Session model" section states that current GNOME
Kiosk releases disable native compositor keybindings by default and that
VT switching needs `--enable-vt-switch` to get back. Checking this
against the actual installed `46.0-1build2` binary
(`gnome-kiosk --help-all`) shows **no such flag exists in this version at
all** — its full option list has nothing named anything like
`vt-switch`. Searching upstream confirms why: GNOME's own "What is new in
GNOME Kiosk 50" post describes exactly this behavior — native keybindings
including VT switching disabled by default, `--enable-vt-switch` to
restore it — as new *in GNOME Kiosk 50*. Noble's `46.0-1build2` predates
that by several releases, the same kind of version gap Stage 1 found for
the gear-menu naming and Stage 2 found for the restart-loop and
`X-GDM-SessionRegisters` behavior.

Practically, this doesn't change what Stage 3 needs to do: the specific
neutering `46.0-1build2` *does* ship (confirmed above, from the binary
directly) doesn't touch any key ARKtube needs, and there's no
`--enable-vt-switch`-equivalent toggle in this version to leave alone or
turn on. There is nothing to configure either way. The root README's
framing is accurate for GNOME Kiosk in general going forward; it's just
not the mechanism doing the work on the version this branch actually
deploys against today.

## Exit condition assessment

Stage 3's exit condition has two halves:

* **"No unrelated GNOME Shell shortcuts leak through"** — confirmed
  directly from the schemas and binary above. Nothing GNOME Kiosk or its
  dependencies bind by default collides with ARKtube's key set.
* **"ARKtube's own arrow-key/D-pad/face-button navigation works inside
  the session"** — this half needs a real compositor and a focused
  ARKtube window to actually click/press through, which this container
  can't do (same limitation Stage 1 and Stage 2 already flagged: no
  display manager here). What's confirmed is the negative case (nothing
  competes for or grabs these keys); the positive case (a press actually
  lands in the WebKitGTK webview and youtube.com/tv reacts) is still an
  on-hardware check.

## What's genuinely unverified

* On-hardware click-through — same reason and same open item as Stage 1
  and Stage 2.
* Whether WebKitGTK's own webview has any built-in handling of `F11`
  that could act before `app-init.js`'s listener sees it. That would be
  inside the native window Neutralino/WebKitGTK owns, not the compositor
  — per the root README's ARKtube/Webtop boundary, that's `main`'s
  concern to verify, not Webtop's.
* Whether a given physical remote actually enumerates to the browser as
  a HID gamepad at all is hardware- and platform-dependent, and sits
  below the compositor layer this stage covers.

## Files

* `docs/STAGE-3-INPUT-MAPPING.md` — this file.
* `docs/README.md` — added a row for this stage.
* No `session/` files changed. See "Correction" above for why: there is
  no flag to set and nothing to override on the version this branch
  targets.
