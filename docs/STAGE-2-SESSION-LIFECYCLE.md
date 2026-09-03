# Stage 2 — Session lifecycle

**Status:** Partially implemented. The two confirmed regressions in Noble's
`gnome-kiosk` 46 that would otherwise break "ARKtube exits → back to GDM"
and "logout → back to GDM" are fixed and verified against the actual
shipped packages. Locking is **not** implemented — see "What's still open"
below; it's a real platform gap, not an oversight.
**Stage definition:** `docs/foundational/STAGED-IMPLEMENTATION.md`, Stage 2.
**Built on:** `docs/STAGE-1-SELECTABLE-SESSION.md`, which this stage
carries one landmine forward from directly.

## What was verified, and how

Same approach as Stage 1: rather than reason from the package's
description, the actual packages were downloaded in this container (same
Ubuntu 24.04 Noble base Stage 1 used — confirmed via `/etc/os-release`)
with `apt-get download` and read directly.

| Package | Version | Why it was pulled |
|---|---|---|
| `gnome-kiosk` | `46.0-1build2` | The compositor; same version Stage 1 already checked |
| `gnome-kiosk-script-session` | `46.0-1build2` | The systemd units and session file — this stage's main subject |
| `gnome-session-common` | `46.0-1ubuntu4` | The generic session-management systemd units this package's units plug into |
| `gnome-session-bin` | `46.0-1ubuntu4` | Confirmed it ships `gnome-session-quit`, and confirmed its dependency list does **not** pull in `gnome-shell` or a full desktop |

Every systemd unit `gnome-kiosk-script-session` and `gnome-session-common`
install was extracted and read in full, not just the one Stage 1 already
quoted.

## Fix 1 — the relaunch loop (confirmed, fixed)

Stage 1 flagged this as a risk inferred from changelog text. Reading the
actual `.deb` confirms it exactly:

```ini
# usr/lib/systemd/user/org.gnome.Kiosk.Script.service, as shipped
[Unit]
Description=Kiosk script
BindsTo=gnome-session.target
After=gnome-session.target

[Service]
ExecStart=/usr/bin/gnome-kiosk-script
Restart=always
```

`Restart=always`, no `RestartSec=` override. The session itself is driven
by `gnome-session-manager@.service`, which runs
`/usr/libexec/gnome-session-binary --systemd-service --session=%i` — this
is the process that actually watches the session's required components
(`RequiredComponents=org.gnome.Kiosk;org.gnome.Kiosk.Script;`, from
`gnome-kiosk-script.session`) and decides when the whole session should
end. Empirically — this is the part that can't be fully derived from unit
files alone, since `gnome-session-binary` isn't shell-scriptable — it does
not treat "the required unit keeps getting relaunched" as "the required
unit is gone." Under `Restart=always` that terminal state never arrives,
so `gnome-kiosk-script` exiting (ARKtube crashing, or being closed on
purpose) never reaches `gnome-session-binary` as "component gone." It just
gets relaunched, over and over, with `gnome-kiosk-script` re-execing
`arktube` each time. Not a dead session. Not a drop to a usable desktop
either. Just a loop with no way back to GDM short of killing the
compositor process by hand.

This is also, independently, exactly the fix GNOME Kiosk shipped upstream
in version 50 — its own `NEWS` file states, for the `50.rc` cycle:

> Script session: systemd is no longer instructed to restart the session
> when the script exits, so that users can logout of the script session
> when the script terminates.

Noble ships 46, which predates that fix by several releases. Rather than
wait for Ubuntu to backport it, it's reproduced here as a systemd user
drop-in — not an edit to the package's own unit file, for the same
conffile reason Stage 1 avoided editing the package's `.desktop` files:

```ini
# session/systemd/org.gnome.Kiosk.Script.service.d/override.conf
[Service]
Restart=no
```

Deployed to `~/.config/systemd/user/org.gnome.Kiosk.Script.service.d/override.conf`.

## Fix 2 — the logout hang (confirmed, fixed)

This one wasn't in Stage 1's doc at all — it only surfaced from reading
`gnome-kiosk-script-session`'s stock `.desktop` files directly:

```ini
# usr/share/wayland-sessions/gnome-kiosk-script-wayland.desktop, as shipped
X-GDM-SessionRegisters=true
X-GDM-CanRunHeadless=true
```

`X-GDM-SessionRegisters=true` tells GDM the session's compositor will
register itself as the session leader over D-Bus, the way GNOME Shell
does. GNOME Kiosk doesn't do that. GNOME upstream confirmed this as a real
bug (not specific to this branch) in
[gnome-kiosk#49](https://gitlab.gnome.org/GNOME/gnome-kiosk/-/issues/49):
with the key wrongly set to `true`, "GDM will not terminate/restart the
greeter, and after the session terminates, it will reuse the old
greeter" — which then breaks further logins for unrelated reasons in GDM.
The fix landed as a one-line flip of that key to `false`, in
[gnome-kiosk!119](https://gitlab.gnome.org/GNOME/gnome-kiosk/-/merge_requests/119),
present from GNOME Kiosk 50 onward. Noble's 46 predates it.

Because Stage 1 already writes `session/wayland-sessions/arktube.desktop`
as our own file rather than editing the package's, applying this fix costs
nothing extra — just a value flip in a file this branch already owns:

```ini
X-GDM-SessionRegisters=false
X-GDM-CanRunHeadless=true
```

(The X11 variant never had this key in the stock file, so
`session/xsessions/arktube.desktop` needed no change.)

## Logout, the deliberate case

Fixes 1 and 2 cover "ARKtube exits or is closed" and "the session ends
without hanging GDM." Neither one is *how* a logout gets requested in the
first place — that's still an open question for whatever ARKtube-side
control (button, keybinding) eventually triggers it, and that control
belongs in `main`, not here, per the root README's ARKtube/Webtop
boundary.

What Webtop can confirm is the correct primitive to call: `gnome-session-quit
--logout --no-prompt`. Reading `gnome-session-bin`'s actual dependency list
confirms this doesn't drag GNOME Shell or a full desktop in as a side
effect — its `Depends:` is GTK/GLib/session libraries only, no
`gnome-shell`. It's a thin D-Bus client for `org.gnome.SessionManager`'s
`Logout` method, which `gnome-session-binary` implements the same way
whether it's running the full desktop or this kiosk session. Combined with
Fix 1, a clean exit and a crash now end the session the same way; combined
with Fix 2, GDM comes back cleanly either way. `gnome-session-bin` isn't
currently in `install-webtop-session.sh`'s package list — it should be
added once something in ARKtube is ready to call it.

## What's still open: locking

The staged-implementation doc's Stage 2 also asks for lock/unlock wired to
"the normal Ubuntu/GDM lock mechanism." Reading the actual package
contents surfaced why that's harder than it sounds for this session
specifically, rather than something to paper over:

* `gnome-kiosk`'s own dependency list and `CONFIG.md` were checked for a
  screen-lock component. There isn't one. The `lock-on-monitor`,
  `lock-on-monitor-area`, and `lock-on-area` options added in GNOME Kiosk
  50 are about pinning *windows* to *monitors* (a "Zaphod mode" successor)
  — nothing to do with authentication or screen locking, despite the name.
* The "normal Ubuntu/GDM lock mechanism" people mean in practice is GNOME
  Shell's own screen-lock UI (`ScreenShield`), drawn in-process by the
  same compositor already running the session. It isn't a GDM feature at
  all — GDM isn't involved when a normal GNOME session locks. GNOME Kiosk
  uses the same compositor library (`libmutter`) but not GNOME Shell
  itself, and doesn't ship an equivalent lock UI.
* The obvious Wayland-native alternative — a standalone locker using the
  `ext-session-lock-v1` protocol (`waylock`, `swaylock` on compositors that
  support it, `gtklock`) — doesn't apply here either.
  [`gtk-session-lock`'s own compatibility notes](https://github.com/Cu3PO42/gtk-session-lock)
  list `ext-session-lock-v1` as supported on wlroots-based and Mir-based
  compositors, and explicitly **not supported on GNOME-on-Wayland**, i.e.
  mutter. That's the same compositor GNOME Kiosk embeds.
* `loginctl lock-session` still works — it sets the session's
  `LockedHint` over `systemd-logind` — but that's a status flag other
  tools can read, not a lock. Nothing here is subscribed to it, so calling
  it alone leaves ARKtube fully visible and interactive on screen; it
  would be locked in name only.

Faking a lock screen inside ARKtube was considered and rejected — the root
README is explicit that session controls "should not be faked inside the
ARKtube application when the underlying session can perform them
correctly," and a lock screen is exactly the kind of thing that needs to
survive the locked app being unresponsive or crashed, which an in-app
overlay can't guarantee.

The one option that uses only genuine, existing session primitives — not
yet implemented or verified here — is **user switching, used as a stand-in
for locking**: hand the seat to a fresh GDM login prompt while the ARKtube
session keeps running unattended in the background, then return to it
after re-authenticating. This is the same mechanism GNOME Shell's own
"Switch User" uses, and it's GDM-specific rather than a generic
`freedesktop.org` call:
[gnome-kiosk's D-Bus interface docs / gdm#220](https://gitlab.gnome.org/GNOME/gdm/-/issues/220)
confirm GDM does **not** implement the LightDM-style
`org.freedesktop.DisplayManager.Seat.SwitchToGreeter`; the GDM-specific
equivalent is `org.gnome.DisplayManager.LocalDisplayFactory.CreateTransientDisplay`
on the system bus. This is a real, documented GDM mechanism, not a guess —
but it has not been exercised against a real GDM in this container (no
display manager here, same limitation Stage 1 already noted), so it stays
unverified and unimplemented pending Stage 2 continuing on real hardware.

## What's genuinely unverified

* Nothing here was clicked through on a real GDM login screen, for the
  same reason as Stage 1: this container has no display manager. Fix 1
  and Fix 2 are confirmed against the exact files GDM and systemd would
  read, and against upstream's own description of the bugs they
  correspond to, but "logging out for real now returns to a normal,
  reusable login screen" is still an on-hardware check.
* `gnome-session-quit --logout --no-prompt` is confirmed to exist, to be
  dependency-light, and to be the standard client for
  `org.gnome.SessionManager.Logout` — but it hasn't been run against this
  session specifically.
* Locking is unimplemented, not just unverified — see above.

## Files

* `session/systemd/org.gnome.Kiosk.Script.service.d/override.conf` —
  deployed to `~/.config/systemd/user/org.gnome.Kiosk.Script.service.d/override.conf`,
  cancels the package's `Restart=always` (Fix 1)
* `session/wayland-sessions/arktube.desktop` — `X-GDM-SessionRegisters`
  flipped from `true` to `false` (Fix 2)
* `session/install-webtop-session.sh` — updated to deploy the drop-in and
  run `systemctl --user daemon-reload`
