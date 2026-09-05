# ARKtube Webtop

A small system layer for launching the native ARKtube session from Ubuntu's login screen.

Webtop is not a desktop environment.

It exists to make **ARKtube the session**.

## What it does

Webtop provides the pieces needed to:

* select **ARKtube** from the Ubuntu login screen
* enter the native ARKtube session without starting the full GNOME Shell desktop
* provide the required session controls, including a small offline,
  TV-style system overlay for network (Wi-Fi/Ethernet), volume,
  brightness, lock, logout, and power — see
  `docs/STAGE-8-TV-STYLE-OVERLAY.md`
* map the required buttons and keyboard actions
* allow the session to be locked
* allow the user to log out
* return cleanly to the login screen
* keep the session focused on ARKtube rather than exposing a general-purpose desktop

The intended flow is simple:

```text
Ubuntu Login Screen
        │
        ├── Gear
        │
        └── ARKtube
              │
              ▼
        Native ARKtube Session
```

The gear icon is the entry point.

The user should not need to start GNOME Shell, open a terminal, or navigate through a desktop just to run ARKtube.

## What it is not

Webtop is not:

* a replacement for Ubuntu
* a general desktop session
* a second copy of GNOME
* a launcher for arbitrary applications
* a window manager
* a full kiosk application by itself
* responsible for reproducing the Ubuntu desktop

The goal is narrower:

> **Give Ubuntu a clean ARKtube session that can be selected from the login screen and provides the session controls ARKtube actually needs.**

## Session model

ARKtube runs as its own graphical session.

The session should use the lightweight kiosk/compositor stack rather than the normal GNOME Shell desktop.

GNOME Kiosk is designed for fixed-purpose and single-application deployments and provides a minimal Wayland compositor without the normal panels, docks, or desktop shell.

For current GNOME Kiosk releases, native compositor keybindings are disabled by default as part of kiosk hardening. VT switching must be explicitly re-enabled with `--enable-vt-switch`. Webtop should therefore treat the kiosk compositor as the first layer of session policy rather than attempting to solve everything from application JavaScript.

> This describes GNOME Kiosk 50 and later. Ubuntu 24.04 Noble — the version this branch actually targets and installs — ships `46.0-1build2`, which predates this flag entirely (confirmed directly against the installed binary's `--help-all` output; no `vt-switch` option exists in it). It also predates the compositor's VT-switch neutering that flag would restore. Practically this is moot for ARKtube's own key set either way — see `docs/STAGE-3-INPUT-MAPPING.md` for what was actually checked on the shipped version.

## Responsibilities

### Ubuntu / GDM

Ubuntu remains responsible for:

* authentication
* the login screen
* session selection
* the gear menu
* returning to the login screen after logout

Webtop should integrate with this existing mechanism rather than replacing it.

### Webtop session

Webtop is responsible for:

* starting the ARKtube session
* starting the kiosk compositor/session environment
* applying the required session configuration
* starting ARKtube
* handling the required session lifecycle

### ARKtube

ARKtube is responsible for:

* its own interface
* application navigation
* application-specific controls
* application-level keyboard handling
* presenting its own lock/session controls where required

This separation matters.

The session should provide the room in which ARKtube runs. ARKtube should not have to impersonate an entire operating-system desktop.

## Input

Input is handled in layers.

```text
Keyboard / Controller
        │
        ▼
Wayland / Kiosk compositor
        │
        ▼
ARKtube native window
        │
        ▼
Application input handling
```

Webtop should define only the mappings required for the ARKtube session.

It should not create a collection of desktop shortcuts merely because they happen to exist in a normal GNOME installation.

The normal desktop is not the product.

## Window policy

ARKtube should occupy the available display without exposing unnecessary desktop chrome.

Where Neutralino is used as the native application shell, its window configuration supports fullscreen, borderless windows, non-resizable windows, maximization control, process-on-close behavior, and inspector control.

A production configuration should keep developer tooling disabled:

```json
{
  "modes": {
    "window": {
      "fullScreen": true,
      "borderless": true,
      "resizable": false,
      "maximizable": false,
      "enableInspector": false,
      "exitProcessOnClose": true
    }
  }
}
```

These settings describe the application window.

They do **not** replace the kiosk session policy.

## Session lifecycle

The important lifecycle is:

```text
Login
  │
  ▼
Select ARKtube from Gear
  │
  ▼
Start Webtop Session
  │
  ▼
Start ARKtube
  │
  ├── Lock
  │     │
  │     ▼
  │   Login / Unlock flow
  │
  └── Logout
        │
        ▼
   Ubuntu Login Screen
```

Logging out should terminate the ARKtube session cleanly and return control to the display manager.

The session should not restart itself merely because ARKtube exited. GNOME Kiosk 50 specifically changed its script-session behavior so that a user can log out when the script terminates.

> Noble's `gnome-kiosk-script-session` (`46.0-1build2`) predates that upstream fix and ships `Restart=always` with no `RestartSec` override, which turns ARKtube exiting into an unremovable relaunch loop instead of a clean logout. This branch's `session/systemd/org.gnome.Kiosk.Script.service.d/override.conf` reproduces the GNOME Kiosk 50 behavior as a drop-in rather than waiting on a package update — see `docs/STAGE-2-SESSION-LIFECYCLE.md`.

## Design principles

### One job

Webtop exists to provide an ARKtube session.

Every component should justify its existence against that purpose.

### No desktop cosplay

Do not recreate GNOME Shell functionality that ARKtube does not need.

No panel.

No dock.

No desktop launcher.

No unnecessary shell extensions.

No second desktop hidden behind the application.

### Session controls remain real

Locking and logging out are operating-system session operations.

They should not be faked inside the ARKtube application when the underlying session can perform them correctly.

### Policy before JavaScript

Application JavaScript can manage application shortcuts.

It should not be the primary security boundary for compositor-level controls.

### Keep the path obvious

The intended user experience should be understandable without documentation:

```text
Ubuntu
→ Gear
→ ARKtube
→ ARKtube session
```

That is the product.

## Development

The `webtop` branch contains the session-specific work required to make the above flow possible.

Changes should remain focused on:

* session integration
* kiosk configuration
* ARKtube startup
* input mapping
* session lifecycle
* login/logout/lock behavior

Application features belong in the main ARKtube application code, not in Webtop.

## Production expectations

Before considering Webtop complete, verify:

* ARKtube appears in the Ubuntu session chooser.
* Selecting ARKtube does not start the normal GNOME Shell desktop.
* ARKtube starts automatically.
* ARKtube occupies the intended display area.
* Required controller/keyboard buttons work.
* Locking works.
* Unlocking returns to ARKtube.
* Logging out returns to the Ubuntu login screen.
* Volume, brightness, Wi-Fi, lock, logout, and power are all reachable
  from inside the session with no network connection.
* ARKtube exit does not unexpectedly return to a usable desktop.
* Developer tooling is unavailable in production.
* Unnecessary compositor shortcuts are not exposed.
* The session survives normal login/logout cycles cleanly.

## The short version

Webtop is the bridge between **Ubuntu's login screen** and **the native ARKtube session**.

It exists so that:

> **Gear → ARKtube → use ARKtube → lock/log out → return to Ubuntu**

No full GNOME Shell required.

No desktop detour required.

No elaborate operating-system cosplay required.
