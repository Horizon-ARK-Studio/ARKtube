# `bar { mode invisible }` declares a new bar, doesn't silence the old one

**Found:** Second full sweeping scan of the compositor layer, checking
the previous scan's own fixes rather than assuming they landed
correctly.
**Environment:** Sway's config-parsing semantics for `bar` — not
version-specific; confirmed against `sway-bar(5)` and
`swaywm/sway#5956`.

## Symptom

None observed directly (no display in this environment, same caveat
as every entry in this log). Found by re-reading the previous scan's
own fix — `bar { mode invisible }` added to
`20-arktube.conf` — against `sway-bar(5)` rather than trusting that a
merged patch was necessarily correct.

## Root cause

A `bar { ... }` block in a Sway config is not a way to edit
"the bar" — Sway has no single implicit bar object a bare block
attaches to. Every `bar { ... }` block, wherever it appears across
however many included config files, declares a **new**, independently
running `swaybar` instance, auto-assigned the next unused `bar-N` id
if it doesn't set one explicitly. This is directly demonstrated by
`swaywm/sway#5956`'s own repro config:

```
bar { swaybar_command build/swaybar/swaybar }
bar { id bar-0 swaybar_command build/swaybar/swaybar }
```

— where the second, un-idd block would have auto-numbered itself
`bar-1` (a second bar) had its explicit `id bar-0` not collided with
the first.

Ubuntu's stock `/etc/sway/config` already declares its own
`bar { swaybar_command ... }` (implicitly `bar-0`) before `config.d/*`
is included. The previous scan's `bar { mode invisible }` block in
`20-arktube.conf`, loaded after that via `config.d/*`, didn't touch
`bar-0` at all — it declared a second bar (`bar-1`), running swaybar's
default command, permanently invisible. Harmless on its own, but it
did nothing to address the original concern (`bar-0`, Ubuntu's real
default bar, was left exactly as it was before that fix — still
running, still potentially visible if the fullscreen-vs-top-layer
research in `swaybar-top-layer-fullscreen.md` turns out not to hold).

## Fix

Replaced the block with the i3-compatible top-level *command* form —
no braces, not a new declaration:

```
bar mode invisible
```

Per `sway-bar(5)`: "`bar mode <mode> [<bar-id>]` ... if bar-id is
omitted, the mode will be changed for all bars." This retargets
whatever bar(s) already exist at the point this line runs — which,
because `config.d/*` is included after the base config's own
`bar { ... }` block, is Ubuntu's real default bar (`bar-0`) — instead
of declaring a new one.

## Verification

Not verified against a real display, same caveat as every entry here.
What was checked: `sway-bar(5)`'s own documented grammar for the
i3-compat `bar mode` command (confirmed the no-bar-id, "all bars"
behavior is documented, not assumed), and `swaywm/sway#5956` for
independent confirmation that bare `bar { }` blocks are separate
declarations, not edits. `sway -C -c <config>` was not re-run (no
`sway` binary in this environment).

## Prevention

A patch this project's own scan produced and got merged was still
worth re-checking on the next pass, the same way any other line in
this config gets checked rather than trusted — "we already fixed
this" isn't itself verification. Read the actual grammar for a
config directive before relying on block syntax to mean "modify",
particularly for i3/sway config where several directives have both a
block form (declare) and a bare command form (mutate) that look
similar but aren't interchangeable.
