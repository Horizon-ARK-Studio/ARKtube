# Index for ARKtube Documents

* [Foundational/PROBLEM-STATEMENT.md](Foundational/PROBLEM-STATEMENT.md) -- the design document: what ARKtube is, why it's built as a thin native shell around `m.youtube.com` instead of a rewrite, and why that specifically benefits low-end/older Android devices.
* [Foundational/CODE-STYLE.md](Foundational/CODE-STYLE.md) -- how we write code: package-per-concern layout, when a GoF pattern earns its place, the try/catch/finally + `ArkLogger` logging convention, and where a new file should go.
* [Foundational/SYSTEM-DESIGN-AGREEMENTS.md](Foundational/SYSTEM-DESIGN-AGREEMENTS.md) -- who's allowed to own what between this app's native layer and the WebView/Chromium runtime underneath it. The recurring root cause behind this project's worst bugs (BUG-0001, BUG-0004) is two well-behaved systems both claiming the same platform resource; this doc names that failure shape once so it stops recurring at the PR level.
* [bugs-caught/README.md](bugs-caught/README.md) -- active bug tracker. Bugs stay listed here until fixed, tested, and confirmed working.

---

## Philosophy

These documents exist for the same reason the app's architecture
keeps splitting into small, named pieces instead of accumulating in
one place: **the codebase should be legible to whoever opens it next,
including a future version of whoever wrote it.**

A few things that follow from that:

* **Explain the constraint, not just the code.** Nearly every doc
  comment and design doc in this project exists to answer "why is it
  built this way" rather than "what does this do" -- the latter is
  already visible in the code itself. `PROBLEM-STATEMENT.md` explains
  why the shell doesn't redesign YouTube; `CODE-STYLE.md` explains why
  a given file, pattern, or try/catch exists where it does. If a
  future change makes one of these explanations stop being true,
  updating the doc is part of the change, not a follow-up.
* **Don't reach for structure the problem hasn't asked for yet.** A
  GoF pattern, a new package, a new abstraction -- each is adopted
  here because it's the accurate name for a constraint the code
  already ran into, not because it's available. The inverse failure
  mode (one file doing everything because splitting it felt like
  premature effort) is exactly what `MainActivity.kt` had grown into
  before it was split -- see `CODE-STYLE.md` Section 1.
* **Assume things will fail, and make the failure observable.** The
  app's logging convention exists because a WebView JS bridge or a
  background service can fail in ways that never surface as a normal
  crash. The same instinct applies to documentation: a decision that
  isn't written down anywhere is a decision that will look like an
  accident the next time someone runs into its consequences.
* **Stay small on purpose.** Both the app itself (Section 2-3 of
  `PROBLEM-STATEMENT.md`) and this docs folder are deliberately
  narrow. A doc, like a native code path, should exist because
  something genuinely needs explaining that isn't obvious from
  reading the thing it describes -- not as a matter of course.
* **Bugs stay visible until they're actually gone.** `bugs-caught/`
  isn't a changelog -- an entry is removed only once it's fixed,
  tested, and confirmed, not once a fix has merely been attempted.
  Optimism about a fix isn't the same as the fix holding up.
