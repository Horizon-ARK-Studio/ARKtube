# The Webtop Problem

## Context

ARKtube is intended to operate as a dedicated native session on Ubuntu.

Ubuntu already provides the correct place to choose such a session: the login screen's session selector, exposed through the gear icon.

The problem is what happens after that selection.

Starting ARKtube through the normal Ubuntu desktop means bringing along the full GNOME Shell environment and everything that comes with it.

That is unnecessary.

ARKtube does not need to become another application sitting inside a general desktop. It needs to become the session.

## The problem

We need a way to select:

```text
Ubuntu Login Screen
        ↓
      Gear
        ↓
    ARKtube
```

and arrive directly in:

```text
Native ARKtube Session
```

without starting the normal GNOME Shell desktop.

At the same time, the session must remain a proper Linux graphical session.

That means it must still support the things a real session is expected to support:

* locking
* unlocking
* logging out
* returning to the login screen
* session startup and shutdown
* required keyboard/controller mappings

The goal is therefore not simply to make a fullscreen window.

A fullscreen window is an application feature.

A selectable ARKtube session is an operating-system integration feature.

## Why a separate session?

Running ARKtube inside the normal desktop creates an unnecessary dependency on the desktop shell.

The resulting stack looks roughly like:

```text
Ubuntu
  └── GNOME Shell
       └── Desktop
            └── ARKtube
```

The desired stack is:

```text
Ubuntu
  └── ARKtube Session
       └── ARKtube
```

The second model is smaller, more predictable, and better aligned with a dedicated appliance-style application.

GNOME Kiosk exists specifically for this class of deployment. It provides a minimal Wayland compositor for fixed-purpose and single-application sessions without the normal desktop panels, docks, and other shell components.

## Why the gear icon matters

The gear icon is already the user's session-selection mechanism.

There is no reason to invent another launcher when the display manager already knows how to select graphical sessions.

Webtop should therefore integrate with the existing Ubuntu/GDM session model.

The desired experience is:

1. Boot Ubuntu.
2. Reach the login screen.
3. Open the session selector using the gear icon.
4. Select ARKtube.
5. Authenticate.
6. Enter the native ARKtube session.

The user should not have to know how the session is implemented.

## Why not use JavaScript for everything?

Some keyboard behavior belongs to the application.

Some keyboard behavior belongs to the compositor.

Those are different layers.

```text
Input
  ↓
Compositor / session
  ↓
Native application window
  ↓
ARKtube
```

Trying to solve compositor-level behavior from a webview keydown handler is fragile because the compositor may process an input event before the application receives it.

Webtop therefore needs to configure the session itself and leave application-specific behavior to ARKtube.

## GNOME Kiosk

Current GNOME Kiosk releases are particularly suitable for this model.

GNOME Kiosk 50 disables native keybindings, including VT-switching shortcuts, by default as part of kiosk hardening. VT switching can be explicitly restored with `--enable-vt-switch` when a deployment requires it.

This means Webtop should not be designed around the assumption that traditional GNOME desktop shortcuts are inevitably present.

The session should explicitly define the behavior it needs.

## Neutralino's role

If ARKtube uses Neutralino as its native application shell, Neutralino provides the window-level controls needed for the application itself, including fullscreen, borderless mode, resize/maximize controls, and inspector configuration.

That is useful, but Neutralino is not the session manager.

The responsibilities remain separate:

```text
GDM
  → chooses the session

GNOME Kiosk
  → provides the graphical session/compositor

Webtop
  → connects the session to ARKtube

Neutralino
  → provides the native ARKtube application window

ARKtube
  → provides the application
```

Each layer gets one job.

This is preferable to making one layer responsible for everything simply because computers have historically rewarded architectural optimism with maintenance work.

## Success criteria

The problem is solved when all of the following are true:

### Entry

ARKtube can be selected directly from Ubuntu's login screen using the existing session selector.

### Session

Selecting ARKtube starts the dedicated ARKtube session rather than the normal GNOME Shell desktop.

### Application

ARKtube launches automatically as the primary application.

### Input

The controls required by ARKtube are mapped and usable.

### Lock

The session can be locked normally.

### Unlock

Unlocking returns the user to ARKtube.

### Logout

Logging out terminates the ARKtube session and returns the user to the Ubuntu login screen.

### Isolation

The normal desktop shell, desktop panels, docks, and unnecessary desktop functionality are not part of the ARKtube session.

### Maintainability

Session behavior is configured at the session/compositor level where appropriate, while application behavior remains inside ARKtube.

## Non-goals

Webtop should not attempt to:

* replace GDM
* replace Ubuntu's login screen
* implement its own authentication system
* recreate GNOME Shell
* provide a general desktop
* become an application launcher
* duplicate ARKtube's UI
* solve application shortcuts that belong inside ARKtube
* act as a second window manager
* expose developer tooling in production

## Architectural boundary

The clean boundary is:

```text
Ubuntu / GDM
       │
       │ session selection
       ▼
GNOME Kiosk
       │
       │ session + compositor
       ▼
Webtop
       │
       │ launches/configures
       ▼
ARKtube
       │
       │ application behavior
       ▼
User
```

Webtop should remain the thin layer connecting these pieces.

Its success is measured by how little it needs to do, not by how many things it can accumulate.

## One-sentence definition

> **Webtop provides the Ubuntu session integration required to enter ARKtube directly from the login screen, using a lightweight native session instead of the full GNOME Shell desktop, while preserving normal lock, logout, and session lifecycle behavior.**
