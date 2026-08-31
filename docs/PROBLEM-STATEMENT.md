# YouTube Desktop Client

## Design Document

**Status:** Experimental
**Target:** Desktop
**Shell:** Neutralinojs
**Initial UI:** YouTube web UI
**Initial frontend technology:** Existing YouTube frontend / DOM
**Future migration:** Svelte
**Primary goal:** Make YouTube behave like an installed desktop application without redesigning the YouTube experience.

---

## 1. Objective

Build an installable desktop application that provides a **1:1 YouTube web experience** while behaving like a persistent desktop application.

The application should:

* look like the existing YouTube desktop website
* retain YouTube's familiar navigation, player, feeds, search, channels, playlists, and recommendations
* avoid unnecessary document-level navigation where technically possible
* preserve application state between navigations
* keep the desktop shell alive continuously
* use Neutralinojs as the native application container
* initially avoid rewriting the YouTube UI
* eventually replace individual pieces with native components
* eventually migrate the UI architecture to Svelte if worthwhile

The guiding principle is:

> **Do not redesign YouTube. Change the execution model around it.**

---

# 2. Non-Goals

This project is not initially intended to be:

* a new YouTube frontend
* a privacy-focused YouTube alternative
* a Piped/NewPipe client
* a visual redesign
* a replacement recommendation algorithm
* an independent video-hosting platform
* a full rewrite of YouTube's frontend
* an attempt to reproduce YouTube's proprietary source code

The first version should contain as little custom UI as possible.

---

# 3. High-Level Architecture

```text
┌──────────────────────────────────────────────────────┐
│                  Native Desktop App                  │
│                    Neutralinojs                       │
│                                                      │
│  ┌────────────────────────────────────────────────┐  │
│  │                WebView                         │  │
│  │                                                │  │
│  │              youtube.com                       │  │
│  │                                                │  │
│  │   ┌────────────┐     ┌─────────────────────┐  │  │
│  │   │ Sidebar    │     │                     │  │  │
│  │   │            │     │   YouTube content   │  │  │
│  │   │ Home       │     │                     │  │  │
│  │   │ Trending   │     │   Video / Search /  │  │  │
│  │   │ Subs       │     │   Channel / Feed    │  │  │
│  │   │ Library    │     │                     │  │  │
│  │   └────────────┘     └─────────────────────┘  │  │
│  │                                                │  │
│  └────────────────────────────────────────────────┘  │
│                         ▲                            │
│                         │                            │
│                 Navigation Controller                │
│                         │                            │
│              Neutralino JavaScript API               │
└──────────────────────────────────────────────────────┘
```

Neutralino owns:

* application lifecycle
* native window
* application installation
* filesystem access where required
* native menus
* window controls
* OS integration
* application settings
* future system media-key integration

YouTube owns:

* visual UI
* video player
* navigation
* recommendation UI
* authentication
* account state
* search
* subscriptions
* playlists
* watch history
* channel pages

The client initially acts as a **desktop shell and navigation layer**, not a replacement YouTube implementation.

---

# 4. Core Design Principle

The application should maintain a persistent top-level process.

Traditional navigation:

```text
User clicks video
        ↓
Browser navigation
        ↓
old document destroyed
        ↓
new document requested
        ↓
HTML parsed
        ↓
JavaScript initialized
        ↓
YouTube application initialized
        ↓
video displayed
```

Desired behavior:

```text
User clicks video
        ↓
Navigation intercepted
        ↓
existing application remains alive
        ↓
route/state changes
        ↓
required content updates
        ↓
video changes
```

The desktop shell must never be destroyed simply because the user navigates between YouTube pages.

---

# 5. Neutralino Application

The Neutralino application should initially be extremely thin.

```text
youtube-desktop/
│
├── neutralino.config.json
│
├── resources/
│   ├── index.html
│   ├── app.js
│   └── styles.css
│
├── native/
│   └── ...
│
└── README.md
```

The initial `index.html` should do almost nothing.

Conceptually:

```html
<body>
    <div id="app"></div>
    <script src="/app.js"></script>
</body>
```

The first prototype should determine whether the Neutralino WebView can reliably host the target YouTube experience before significant architecture is introduced.

---

# 6. WebView Strategy

The application will initially load YouTube inside the Neutralino WebView.

Conceptually:

```text
Neutralino
    │
    └── WebView
          │
          └── https://www.youtube.com
```

The client should not immediately attempt to copy YouTube's frontend.

The purpose of this phase is to determine:

1. whether YouTube operates correctly inside the WebView
2. whether login works
3. whether video playback works
4. whether cookies/session state persist
5. whether navigation can be observed
6. whether navigation can be intercepted
7. whether the WebView exposes enough functionality for the desired behavior

This is a feasibility phase, not the final implementation.

---

# 7. Navigation Controller

The most important custom component is the navigation controller.

```text
NavigationController
│
├── observeNavigation()
├── classifyNavigation()
├── preventFullNavigation()
├── navigate()
├── preserveState()
└── restoreState()
```

It should distinguish between:

```text
Internal YouTube navigation
External navigation
Authentication navigation
Download navigation
Browser/system navigation
```

Internal routes include examples such as:

```text
/
 /watch?v=...
 /results?search_query=...
 /@channel
 /channel/...
 /playlist?list=...
 /feed/subscriptions
 /shorts/...
```

The controller should treat internal navigation as application navigation whenever possible.

---

# 8. Persistent Application Shell

The desktop application should maintain one long-lived WebView/application context.

Conceptually:

```text
Application lifetime
─────────────────────────────────────────────>

┌───────────────────────────────────────────┐
│ WebView                                   │
│                                           │
│ Sidebar ───────────────────────────────┐  │
│                                        │  │
│ Header                                 │  │
│                                        │  │
│ Content changes                        │  │
│                                        │  │
│ Video player may remain alive          │  │
│                                        │  │
└────────────────────────────────────────┘  │
                                           │
           application never destroyed ───┘
```

The goal is not necessarily to keep every DOM node forever.

The goal is to keep the **application context** alive.

---

# 9. Navigation State

The client should maintain:

```js
const navigationState = {
    currentURL: null,
    previousURL: null,
    history: [],
    scrollPositions: new Map(),
    playerState: null
};
```

Before navigation:

```text
current page
    ↓
save state
    ↓
navigate
    ↓
restore relevant state
```

For example:

```text
Home
 ↓
Video A
 ↓
Channel
 ↓
Video B
 ↓
Back
```

Back should return to the previous application state rather than reconstructing an entire browser document.

---

# 10. YouTube UI Fidelity

The initial implementation should intentionally avoid changing:

* typography
* colors
* icons
* spacing
* thumbnails
* player controls
* sidebar
* cards
* channel pages
* search results
* recommendation layout

The UI should remain visually recognizable as YouTube.

Custom styling should initially be limited to desktop-shell concerns:

```text
Window frame
Window controls
Native menus
Optional title bar
Application-level loading states
Optional keyboard shortcuts
```

Do not spend time recreating YouTube CSS.

That is precisely the sort of noble engineering sacrifice that produces six months of CSS archaeology.

---

# 11. Desktop Behavior

The application should behave like a native desktop application.

Desired features:

### Window

* resizable
* maximizable
* minimizable
* persistent window size
* persistent window position where supported

### Application lifecycle

```text
Launch
 ↓
restore session
 ↓
load YouTube
 ↓
restore last route where possible
```

### Close

Closing the application should terminate the Neutralino process normally.

### Relaunch

The application may restore:

```text
last URL
last window dimensions
theme preference
sidebar state
player state where technically possible
```

---

# 12. Keyboard Architecture

Desktop shortcuts should eventually be handled outside YouTube's page-level event system where appropriate.

Examples:

```text
Ctrl/Cmd + L       focus application search
Ctrl/Cmd + K       search
Alt + Left         back
Alt + Right        forward
Space              play/pause
F                   fullscreen
M                   mute
```

However, YouTube's existing keyboard behavior should remain authoritative where conflicts exist.

The rule is:

> Do not break YouTube shortcuts merely to prove that we have a desktop application.

---

# 13. Performance Model

The primary performance objective is **navigation continuity**, not synthetic benchmark numbers.

The application should avoid unnecessary:

```text
DOM destruction
JavaScript reinitialization
network requests
image decoding
player initialization
authentication initialization
layout reconstruction
```

Ideal navigation:

```text
Click
 ↓
<100 ms
 ↓
route transition begins
 ↓
existing shell remains
 ↓
data/content changes
 ↓
new view settles
```

The client should prioritize:

1. persistent state
2. reduced network duplication
3. reduced DOM destruction
4. player persistence
5. thumbnail reuse
6. background prefetching

Only after those should low-level optimization be considered.

---

# 14. Background Work

Operations that do not need to block navigation should execute asynchronously.

Examples:

```text
thumbnail loading
recommendation fetching
history synchronization
metadata fetching
prefetching
settings persistence
analytics-related work
```

Conceptually:

```text
User action
    │
    ├── critical path
    │      └── update visible content
    │
    └── background path
           ├── recommendations
           ├── thumbnails
           ├── metadata
           └── cache updates
```

---

# 15. Caching

A lightweight client cache should eventually be introduced.

Potential cache targets:

```text
video metadata
channel metadata
thumbnails
search results
navigation state
preferences
```

Do not cache video streams initially.

Cache invalidation is already one of software's oldest ways of making a developer regret having free time.

---

# 16. Authentication

Authentication should initially remain YouTube's responsibility.

The application should preserve the WebView's supported session state rather than implementing a separate authentication system.

Desired behavior:

```text
User signs in
       ↓
YouTube session established
       ↓
cookies/session persisted
       ↓
application restarted
       ↓
session restored
```

No password or authentication token should be manually extracted into the Neutralino application unless explicitly required and legally/technically appropriate.

---

# 17. Player Strategy

Phase 1:

```text
Use YouTube's existing player.
```

Do not build a custom player.

The existing player already solves:

* adaptive streaming
* codecs
* quality selection
* subtitles
* playback state
* DRM-related behavior where applicable
* fullscreen
* buffering
* playback telemetry

Replacing it would multiply the project's complexity immediately.

Later:

```text
YouTube player
       │
       └── optional abstraction
                │
                ├── existing player
                └── future custom player
```

This keeps the architecture replaceable without making replacement the initial goal.

---

# 18. Neutralino Native Integration

Neutralino should expose native functionality through a small application bridge.

```text
Web UI
  │
  ▼
AppBridge
  │
  ├── Window
  ├── Storage
  ├── Filesystem
  ├── Menu
  ├── Notifications
  └── OS integration
```

Example conceptual API:

```js
App.window.minimize()
App.window.maximize()

App.storage.get("lastRoute")
App.storage.set("lastRoute", url)

App.settings.get()
App.settings.set()
```

The UI should not directly depend on Neutralino APIs everywhere.

Instead:

```text
Vue / future Svelte
        ↓
    AppBridge
        ↓
   Neutralino
```

This is important for the eventual Svelte migration.

---

# 19. Frontend Framework

The initial application does not need to introduce Vue or Svelte.

YouTube already provides the UI.

The custom layer should therefore remain framework-light:

```text
Neutralino
    │
    ├── thin JS bridge
    │
    └── YouTube WebView
```

If the project eventually replaces portions of the YouTube UI:

```text
Neutralino
    │
    └── WebView
          │
          ├── YouTube
          │
          └── custom components
                  │
                  └── initially Vue 3
```

Later:

```text
Vue 3
  ↓
Svelte
```

The API boundary should remain:

```text
UI
 ↓
application services
 ↓
YouTube integration
```

rather than:

```text
Vue component
 ↓
Neutralino API
 ↓
YouTube
```

---

# 20. Migration Path to Svelte

The migration should happen only after the behavior is proven.

### Phase A

```text
Neutralino
+
YouTube
```

### Phase B

```text
Neutralino
+
YouTube
+
navigation controller
+
desktop integration
```

### Phase C

Replace isolated UI elements:

```text
Custom sidebar
Custom header
Custom loading states
Custom settings
```

### Phase D

Introduce Vue 3 where custom UI becomes substantial.

### Phase E

Extract application services.

```text
services/
├── navigation.js
├── youtube.js
├── storage.js
├── player.js
└── settings.js
```

### Phase F

Replace Vue components with Svelte.

The underlying service layer remains unchanged.

---

# 21. Project Phases

## Phase 0: Feasibility

Goal:

> Can YouTube reliably operate inside Neutralino's WebView?

Test:

* homepage
* search
* video playback
* login
* cookies
* fullscreen
* navigation
* back/forward
* redirects
* popups
* downloads

No custom UI.

---

## Phase 1: Desktop Shell

Implement:

```text
Neutralino
+
YouTube
+
persistent window
+
window state
+
application settings
```

Result:

> YouTube in an installable desktop window.

---

## Phase 2: Navigation

Implement:

```text
navigation observation
route classification
history
back/forward
state persistence
```

Result:

> YouTube begins behaving like a desktop application.

---

## Phase 3: Continuity

Improve:

```text
navigation transitions
player persistence
scroll restoration
thumbnail reuse
background loading
```

Result:

> Clicking around YouTube feels continuous rather than document-oriented.

---

## Phase 4: Desktop Integration

Add:

```text
native menus
keyboard shortcuts
notifications
media keys
window controls
persistent settings
```

Result:

> It feels like an actual installed application.

---

## Phase 5: Selective UI Replacement

Only now begin replacing YouTube components.

Potential candidates:

```text
sidebar
header
search box
navigation indicators
settings
desktop-specific controls
```

---

## Phase 6: Svelte

Once the architecture is stable:

```text
Vue/custom components
       ↓
Svelte components
```

No major backend or shell rewrite should be required.

---

# 22. Failure Modes

### Full-page navigation cannot be prevented

Fallback:

```text
Keep normal YouTube navigation.
```

Do not create a fragile interception layer merely for the sake of avoiding reloads.

---

### YouTube detects or rejects the embedded environment

Fallback:

```text
Use the system browser or supported external navigation.
```

The application should fail gracefully instead of trying to impersonate a browser indefinitely.

---

### Authentication does not persist

Fallback:

```text
Investigate WebView profile/storage configuration.
```

Do not manually copy credentials.

---

### YouTube changes its frontend

This is expected.

Because the initial architecture uses YouTube's own UI, visual updates should automatically arrive with YouTube rather than requiring the client to reproduce every redesign.

That is one of the primary advantages of this architecture.

---

# 23. Security

The application must treat YouTube content as untrusted web content.

Neutralino native APIs should not be exposed unnecessarily to the web context.

The bridge should expose only explicitly required operations.

Prefer:

```text
YouTube
   │
   │ restricted
   ▼
AppBridge
   │
   ▼
Neutralino
```

rather than exposing arbitrary filesystem/process functionality.

Native capabilities should follow least privilege.

---

# 24. Success Criteria

The project is successful when:

### Visual

```text
YouTube desktop UI
≈
YouTube desktop website
```

No unnecessary redesign.

### Behavioral

```text
Navigation
≈
desktop application
```

### Technical

```text
Persistent Neutralino process
Persistent WebView context
Minimal unnecessary document reloads
Background non-critical work
Persistent session
Persistent window state
```

### User experience

The user can:

```text
Launch application
        ↓
YouTube appears
        ↓
Search
        ↓
Open video
        ↓
Open channel
        ↓
Return to video
        ↓
Continue browsing
```

without the application feeling like:

```text
"website inside a box"
```

Instead it should feel like:

```text
"YouTube is the application."
```

---

# 25. Guiding Principle

The project should follow one rule above all others:

> **Borrow behavior and appearance before rebuilding anything.**

The first implementation should contain very little custom code.

Every piece of functionality should pass this test:

```text
Can YouTube already do this?
        │
       YES
        │
        └── Let YouTube do it.

       NO
        │
        └── Add the smallest possible desktop layer.
```

The final architecture should therefore evolve from:

```text
Neutralino
    ↓
YouTube
```

into:

```text
Neutralino
    ↓
Application shell
    ↓
YouTube experience
    ↓
Selective custom desktop behavior
```

and eventually, if justified:

```text
Neutralino
    ↓
Svelte application
    ↓
YouTube-compatible services
    ↓
YouTube
```

The migration is progressive rather than a rewrite.

---

# 26. Initial Repository Structure

```text
youtube-desktop/
│
├── neutralino.config.json
│
├── resources/
│   ├── index.html
│   ├── app.js
│   │
│   ├── bridge/
│   │   ├── neutralino.js
│   │   └── app-bridge.js
│   │
│   └── styles/
│       └── shell.css
│
├── native/
│
├── docs/
│   └── DESIGN.md
│
├── package.json
│
└── README.md
```

Keep it tiny.

The project should earn complexity rather than starting with it.

---

# 27. Immediate Implementation Order

The first development session should contain only these steps:

```text
1. Create Neutralino project
2. Load YouTube
3. Verify video playback
4. Verify login/session persistence
5. Verify navigation
6. Determine what navigation events Neutralino/WebView exposes
7. Prototype navigation interception
8. Measure whether the desired behavior is actually achievable
```

Only after step 8 should the project acquire a frontend architecture.

If the WebView cannot provide the required navigation/control primitives, that discovery is far more valuable than spending a week building a beautiful Vue application around an impossible assumption.

---

## Final Architecture

```text
                         ┌───────────────────────┐
                         │     Native Desktop    │
                         │       Neutralino      │
                         └───────────┬───────────┘
                                     │
                                     ▼
                         ┌───────────────────────┐
                         │   Persistent WebView  │
                         └───────────┬───────────┘
                                     │
                    ┌────────────────┴────────────────┐
                    │                                 │
                    ▼                                 ▼
             YouTube Website                  Desktop Bridge
                    │                                 │
                    │                                 ├── Window
                    │                                 ├── Storage
                    │                                 ├── Settings
                    │                                 ├── Shortcuts
                    │                                 └── OS integration
                    │
                    ▼
             Existing YouTube UI
                    │
                    ├── Home
                    ├── Search
                    ├── Watch
                    ├── Channels
                    ├── Playlists
                    ├── Subscriptions
                    └── Player
```

**Version 0.1 is intentionally boring.**

That's a feature.

The first milestone isn't "build YouTube."

It's:

> **Put YouTube in a Neutralino window and prove we can control navigation without destroying the application context.**

Once that works, the rest becomes incremental engineering instead of a giant speculative rewrite.
