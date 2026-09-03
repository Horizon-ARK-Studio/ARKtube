# Cursor auto-hide

**Status:** Implemented in `resources/js/app-init.js`.
**Built on:** nothing else in this branch; added specifically to close a
gap the `webtop` branch's Stage 7 could only partially close on its own
— see `docs/STAGE-7-VISIBILITY-AND-CURSOR.md` on that branch for the
full history.

## Why this lives here, not in `webtop`

`webtop`'s `gnome-kiosk-script` hides the idle mouse pointer with
`unclutter-xfixes`, but that tool only works on the X11 session
(`xsessions/arktube.desktop`). The primary session
(`wayland-sessions/arktube.desktop`) runs GNOME Kiosk's Wayland
compositor, where the cursor image is drawn by whichever client owns
the surface under the pointer — not by a bystander process. A
background daemon started from session scripts structurally cannot
reach into another app's surface and hide its cursor; that's Wayland's
client-isolation model working as intended, not a missing flag.

ARKtube's own webview *is* that client, on every platform it runs on
(Wayland, X11, Windows, macOS). So the fix that actually generalizes is
having the app hide its own cursor after idle, rather than depending on
a session-layer tool that only ever covered one of its sessions.

## What was built

`app-init.js` injects a small stylesheet:

```css
.arktube-cursor-hidden, .arktube-cursor-hidden * { cursor: none !important; }
```

and toggles the `arktube-cursor-hidden` class on `document.documentElement`
from a single idle timer, reset on `mousemove`, `mousedown`, `wheel`, and
`keydown`. 10 seconds of no matching activity hides the cursor; any of
those events restores it immediately — the same shape `unclutter`'s own
`--timeout`/`-idle` behavior already has, and the same 10s value webtop
uses, so the two layers agree on timing anywhere both happen to be
active at once (the X11 session).

Gamepad/remote input is deliberately excluded. `app-init.js` already
re-dispatches D-pad/stick/button input as synthetic `keydown` events
(see the Gamepad section further down in this file) so youtube.com/tv's
own key handling picks it up — but that isn't cursor activity, and
counting it would mean a controller-only session (exactly the TV/kiosk
case this app targets) never actually keeps the cursor hidden. Synthetic
events built with `new KeyboardEvent(...)` have `isTrusted === false`;
only trusted (real hardware) events reset the idle timer.

## What this does and doesn't replace

* On the Wayland session, this is now the only cursor-hiding mechanism,
  and it's the correct layer for it — see "Why this lives here" above.
* On the X11 session, `webtop`'s `unclutter-xfixes` still runs too. The
  two don't conflict: `unclutter-xfixes` hides the real X cursor sprite,
  this hides the CSS cursor the page requests instead of it, and either
  one alone is already sufficient — this isn't relied on to *replace*
  webtop's X11 handling, just to cover the session it can't reach.
* This does not touch Immersive Mode, and isn't gated by it — cursor
  auto-hide runs unconditionally, matching webtop's own
  session-wide-regardless-of-app-state behavior for the X11 case it
  already covers.

## What was not verified

Same caveat every doc in this tree carries for anything that needs a
real display/input session: on-hardware timing (whether 10s reads as
"idle" the same way across a mouse, a touchpad, and a TV remote's HID
cursor emulation, if any), and whether `cursor: none` on
`documentElement` reliably suppresses the cursor everywhere within
youtube.com/tv's own iframes/shadow content it doesn't directly control
the styling of. Not exercised end-to-end here — no display server in
this environment, the same limitation `docs/BUGS-CAUGHT.md` and
`IMMERSIVE-MODE.md` already carry for their own on-hardware claims.
