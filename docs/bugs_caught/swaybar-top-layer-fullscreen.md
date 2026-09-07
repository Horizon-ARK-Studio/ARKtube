# Ubuntu's default swaybar vs. ARKtube's fullscreen surface

**Found:** Full sweeping scan of the compositor layer, following up
the "Open, not yet resolved" item `20-arktube.conf` had carried since
the REMOTE-INPUT-MAPPING work.
**Environment:** wlr-layer-shell-v1 placement rules generally
(protocol-level, not a specific Sway/wlroots version) -- research-only,
not confirmed against a real display, same caveat as
`idle-inhibit-gap.md`.

## Symptom

None observed directly. `20-arktube.conf` had flagged, but never
resolved, whether Ubuntu's stock `/etc/sway/config`'s own
`bar { swaybar_command ... }` block -- drawn via layer-shell at the
"top" layer with an exclusive zone -- would visibly compete with
ARKtube's `fullscreen global` surface.

## Root cause

Not a bug in this branch's own config -- the concern in the old
comment turns out to be based on an incomplete picture of
wlr-layer-shell-v1's own placement rules, not a confirmed problem.
Per the protocol's stacking order (independently corroborated
against a wlroots-compositor layer-shell reference, not just this
project's own assumption): a fullscreen surface is drawn above every
layer through "top" and yields only to "overlay". Since swaybar sits
at "top" and this branch's own overlay (`overlay.py`) is explicitly
placed at `layer: overlay` (see `attach_layer_shell()` in
`overlay.py` and the `20-arktube.conf` bindings), swaybar should not
actually paint over ARKtube during normal fullscreen playback -- the
stacking already favors ARKtube and the overlay over it.

That said, "shouldn't paint over it during normal fullscreen
playback" is narrower than "does nothing": swaybar's exclusive zone
and its own process are still live for every moment ARKtube isn't yet
fullscreen (startup, a config reload, a second output ARKtube isn't
on), and this project's own methodology elsewhere is to confirm
rather than assume -- which this scan couldn't do without a real
display.

## Fix

`src/session/sway/config.d/20-arktube.conf`: added

```
bar {
    mode invisible
}
```

This is additive (config.d fragment, not an edit to the package-owned
`/etc/sway/config`), consistent with how this project has always
avoided touching package-owned conffiles. `mode invisible` leaves
`swaybar_command` running (nothing here removes the stock bar block)
but stops it from drawing a surface or reserving an exclusive zone at
all, closing the edge cases above regardless of whether the
fullscreen-vs-top-layer research above turns out to hold on the
actual target Sway/wlroots version.

## Verification

Not verified against a real display. `sway -C -c <config>` was not
re-run in this environment (no `sway` binary available here); the
`bar { mode invisible }` syntax was checked against Sway's own `bar`
command documentation rather than run.

## Prevention

The original comment's uncertainty ("has not been confirmed against
a real display") was itself the useful signal -- it's what this scan
followed up on. Open items phrased that way in this branch's config
comments are worth treating as a checklist, not just narrative.
