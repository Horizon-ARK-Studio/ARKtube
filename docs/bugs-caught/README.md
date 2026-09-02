# Bugs caught — webtop

Log of session/kiosk-specific issues found while building the Webtop layer:
GDM session-file problems, GNOME Kiosk compositor quirks, input that never
reaches ARKtube, lock/logout not returning cleanly to the login screen, and
similar.

**Status: empty.** Nothing has been logged yet.

## Format, once there's something to record

Follow the shape of `main`'s `docs/BUGS-CAUGHT.md`: symptom first (the
actual error or log output), then root cause, then the fix, then how to
verify it. One file per bug, named `BUG-XXXX-short-description.md`,
referenced from a table in this index.

## Scope

Stays to session integration: GDM, the kiosk compositor, session lifecycle,
and input mapping at the compositor layer. Bugs inside ARKtube itself
(playback, rendering, the app's own JS) belong in `main`'s bug log, not
here — see the root-level `README.md`'s "Design principles" section on why
that separation matters.
