# Stage 4 — Hardening

**Status:** Partially implemented. One concrete fix shipped within
Webtop's own remit (an explicit `KillMode=control-group` pin on the
systemd drop-in this branch already owns). The rest of the "safe to
leave logged into unattended" question is blocked on `main`, not on
anything the session layer can fix — see "What's blocking, and why it's
out of Webtop's reach" below.
**Stage definition:** `docs/foundational/STAGED-IMPLEMENTATION.md`, Stage 4.
**Built on:** `docs/STAGE-1-SELECTABLE-SESSION.md` through
`docs/STAGE-3-INPUT-MAPPING.md` — same Noble base, same "read the actual
shipped thing" method.
**Correction to a prior report:** an earlier pass through this stage
assumed `main`'s `docs/BUGS-CAUGHT.md` §9 — which says `defaultMode` is
`"window"` now, with chrome mode kept only as a fallback — was still
current. It isn't. `main`'s actual, currently-checked-in
`neutralino.config.json` was read directly (fresh clone, `main`
`077176b`) rather than trusted from that doc's prose, and it has
`"defaultMode": "chrome"`. That doc is stale; the code is what's real.

## What ARKtube is actually running today (read from `main`, not assumed)

`main`'s `neutralino.config.json` (`077176b`) configures **all four**
Neutralino modes (`window`, `browser`, `cloud`, `chrome`), but only one is
active: `defaultMode: "chrome"`. In this mode, Neutralino's own embedded
webview (the one `enableInspector`, `borderless`, etc. under
`modes.window` describe) is never shown. Instead Neutralino spawns a
real, separate Chrome/Chromium/Edge process, points it at
`youtube.com/tv`, and injects `app-init.js` into that page — the native
Neutralino process becomes a host controlling a detached browser
frontend over its own IPC, not the thing rendering the UI itself. That
`modes.window.enableInspector: true` setting many first-pass audits
(including an earlier version of this one) would flag as "devtools left
on" is actually inert right now — it governs a webview that isn't in
use. That's not the same as devtools being off; see below.

This is deliberate and actively maintained, not a leftover: `packaging/linux/build-deb.sh`'s
`/usr/bin/arktube` launcher (the exact binary `session/gnome-kiosk-script`
execs) carries real, non-trivial logic specifically for chrome mode's
detached-process shape — it reaps any stale Chrome process matching this
app's `--user-data-dir` on every launch, and traps `EXIT INT TERM` to do
the same cleanup on the way out. Recent `main` commits
(`"ARKtube window mode had issues. fixed it"`,
`"Bug fixes. ARKtube and chrome child were decoupled. fixed it"`) are
active work on exactly this mode. Calling this a "hybrid" is fair: it's
a native host process plus a semi-independent browser frontend, joined
by a narrow, explicit IPC allowlist (`nativeAllowList` in
`neutralino.config.json` — just `app.exit`, `app.killProcess`,
`debug.log`, `os.setTray`, `os.showMessageBox`, `events.broadcast`), not
a "pure app window" and not "just a browser" either.

## What was verified, and how

### Stray processes across cycles (Stage 4's 3rd checklist item) — already reasonably covered

Chrome mode's detachment (`setpgid(0, 0)`, per `build-deb.sh`'s own
comments) takes the child out of this service's *process group*, but
process groups and cgroups are different things: a forked child stays in
its parent's cgroup unless something explicitly moves it, which nothing
here does. `org.gnome.Kiosk.Script.service` (read directly from the
extracted `.deb` in Stage 1/2) sets no `KillMode`, so it runs on
systemd's own default — confirmed from `systemd.kill(5)`: **`control-group`**,
meaning *"all remaining processes in the control group of this unit will
be killed on unit stop."* That should already catch a detached-but-not-
decgrouped Chrome child when the service stops, independent of whether
`app-init.js`'s own cleanup runs. Combined with the launcher's own
reap-on-start and reap-on-`EXIT`/`INT`/`TERM` logic, and Stage 2's
`Restart=no` fix (which stops the service — and thus triggers this kill —
instead of looping), this checklist item looks solid.

That said, it was resting entirely on an *implicit* systemd default doing
a job this session's hardening now explicitly depends on. This stage
pins it: `session/systemd/org.gnome.Kiosk.Script.service.d/override.conf`
now also sets `KillMode=control-group` explicitly, with a comment
explaining why, so a future systemd default change (or someone reading
the drop-in without also knowing the man page default) can't silently
reopen this.

### No path to a general desktop, terminal, or arbitrary launcher (2nd checklist item) — the compositor layer holds, the content layer doesn't

At the compositor/session layer, this was already substantially covered
by Stage 1–3's findings, restated here rather than re-derived: GNOME
Kiosk ships no panel, dock, or file manager by design; `<Alt>F2`
(run-dialog) is neutered at the compositor level (Stage 3); the
`46.0-1build2` build Noble ships has no `--enable-vt-switch`-equivalent
flag to accidentally leave on (Stage 3). None of that has changed.

What's different from a prior pass at this stage is recognizing that
**the escape surface here isn't only compositor-level.** A full,
un-flagged Chrome/Chromium window *is* the running application under
`defaultMode: "chrome"`, and `modes.chrome.args` in the checked-in config
is just `--user-agent=... --start-maximized` — no `--kiosk`,
`--disable-dev-tools`, or anything else that would close off that
browser's own UI. That means the browser's own address bar, tab strip,
menu, F12/right-click-Inspect devtools, and a "Save As"/"Open File"
picker (a real GTK file chooser, not sandboxed) are all reachable
through the fullscreen window itself, entirely inside the Chrome
process — no compositor keybinding grab or session policy at this
layer can close that off, because none of those interactions go through
the compositor's keybinding path at all; they're handled inside Chrome.

### Developer tooling disabled (1st checklist item) — not currently true, for the mode actually shipping

Given the above, `modes.window.enableInspector: true` being present is
mostly beside the point: the mode it configures isn't the one rendering
the UI. What actually determines whether devtools are reachable right
now is whether Chrome itself was launched with hardening flags, and it
wasn't. Devtools are on, by omission, in the actively-shipped
configuration.

## What's blocking, and why it's out of Webtop's reach

Neutralino embeds its config into the built binary
(`neu build --embed-resources`, per `build-deb.sh`'s own comment). The
mode ARKtube runs in, and whatever flags get passed to the Chrome
process it launches, are baked in at that build step — nothing in this
session/compositor layer can intercept or override them at launch time.
Per the root README's own "Responsibilities" section, "developer tooling
disabled" is explicitly called out as an ARKtube build concern that
Webtop should *verify*, not own. Verifying it is what this stage did;
fixing it means one of two things happening in `main`, not here:

* add real kiosk-hardening flags to `modes.chrome.args` (at minimum
  something that removes browser chrome and blocks devtools) if chrome
  mode is being kept deliberately for its user-agent spoofing, or
* finish the move to `modes.window` (`defaultMode: "window"`,
  `enableInspector: false`) that `docs/BUGS-CAUGHT.md` §9 describes,
  now that the "window mode had issues" commits suggest whatever broke
  it before is being worked through anyway.

Either is a `main`-side decision with a real trade-off attached (the
Cobalt/PS4 user-agent spoof that gets ARKtube the TV/Leanback UI only
fully works in chrome mode, per §9) — not something to silently pick on
Webtop's behalf.

## Exit condition assessment

Stage 4's exit condition is the root README's "Production expectations"
checklist. Of the items that check specifically: "Developer tooling is
unavailable in production" and "Unnecessary compositor shortcuts are not
exposed" — the latter holds, confirmed directly; the former does not, for
the reasons above. "The session survives normal login/logout cycles
cleanly" is now backed by an explicit setting rather than an implicit
default, but still not clicked through on real hardware — same caveat as
every prior stage.

## What's genuinely unverified

* On-hardware click-through — same reason and same open item as every
  prior stage: no display manager in this container.
* Whether `KillMode=control-group` actually reaps a live Chrome process
  in practice (this reasons correctly from `systemd.kill(5)`'s
  documented behavior and the cgroup-inheritance-on-fork model, but
  hasn't been watched happen against a real running session).
* Whether YouTube's server-side device detection still serves the
  TV/Leanback UI if `main` moves to window mode's `extendUserAgentWith`
  instead of chrome mode's full user-agent override — `docs/BUGS-CAUGHT.md`
  §9 already flags this as unverified on `main`'s side; repeated here
  only because it's the real reason chrome mode might be staying, not
  because Webtop can resolve it.

## Files

* `session/systemd/org.gnome.Kiosk.Script.service.d/override.conf` —
  added `KillMode=control-group`, pinned explicitly rather than left to
  the systemd default.
* `docs/STAGE-4-HARDENING.md` — this file.
* `docs/README.md` — added a row for this stage.
