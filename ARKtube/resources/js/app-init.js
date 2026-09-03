/*
    app-init.js

    This file is loaded via the `injectScript` window-mode config option, which means
    it runs *inside the youtube.com page itself* (after Neutralino's globals and client
    library have been injected), not via resources/index.html. That file is never
    served in production because `url` in neutralino.config.json points directly at
    an external site, bypassing documentRoot entirely.

    Keep this script defensive: it is running on a page we don't control, and YouTube's
    own script may run before or after it.
*/
(function () {
    // Guard against multiple initializations (YouTube or other scripts may run this)
    if (window.__neutralinoAppInitialized) {
        return;
    }
    window.__neutralinoAppInitialized = true;

    function safeExit() {
        // Gracefully shut the native process down instead of relying on
        // exitProcessOnClose to hard-kill it (which skipped this handler entirely).
        try {
            Neutralino.app.exit();
        } catch (err) {
            console.error("Error calling Neutralino.app.exit():", err);
        }
    }

    function onWindowClose() {
        // Hook point for any future save-state/cleanup work (see docs/PROBLEM-STATEMENT.md
        // section 9, "Navigation State") before the process actually exits.
        safeExit();
    }

    function onTrayMenuItemClicked(event) {
        switch (event.detail.id) {
            case "VERSION":
                try {
                    Neutralino.os.showMessageBox(
                        "Version information",
                        `Neutralinojs server: v${NL_VERSION} | Neutralinojs client: v${NL_CVERSION}`
                    );
                } catch (err) {
                    console.error("Error showing message box:", err);
                }
                break;
            case "QUIT":
                safeExit();
                break;
        }
    }

    // Neutralino.window.* only works when running in window mode -- per
    // Neutralino's own docs, "This namespace's methods will work only for
    // the window mode." That's exactly the mode this app now runs in by
    // default (see neutralino.config.json / docs/BUGS-CAUGHT.md ยง9 for why
    // it moved off chrome mode), so these calls control the app's own
    // native window directly. They're kept mode-aware here only so this
    // script still degrades correctly if someone opts back into chrome
    // mode (see the same doc section) -- there, there's no Neutralino
    // -owned native window handle for it to control, and the calls
    // silently no-op instead of erroring (see
    // neutralinojs/neutralinojs#751), so the standard DOM Fullscreen API
    // is used as the fallback instead.
    function isNativeWindowMode() {
        return typeof NL_MODE !== "undefined" && NL_MODE === "window";
    }

    async function toggleFullScreen() {
        try {
            if (isNativeWindowMode()) {
                const isFull = await Neutralino.window.isFullScreen();
                if (isFull) {
                    await Neutralino.window.exitFullScreen();
                } else {
                    await Neutralino.window.setFullScreen();
                }
            } else if (document.fullscreenElement) {
                await document.exitFullscreen();
            } else {
                await document.documentElement.requestFullscreen();
            }
        } catch (err) {
            console.error("Error toggling fullscreen:", err);
        }
    }

    // --- On-screen fullscreen button -------------------------------------
    //
    // The app now boots maximized (--start-maximized, see
    // neutralino.config.json) rather than auto-entering true fullscreen, so
    // the taskbar/dock stays visible until the user explicitly asks for
    // fullscreen. This button is that explicit ask for anyone without a
    // keyboard/remote handy. It used to double up with F11 calling the same
    // toggleFullScreen() function; F11 now belongs to Chrome alone (see
    // onKeyDown above), so this button is the one remaining, deliberate way
    // this script itself drives fullscreen -- distinct from Immersive Mode
    // below, which is a separate, persisted, higher-stakes setting rather
    // than a plain view toggle.
    const FULLSCREEN_BTN_ID = "arktube-fullscreen-btn";

    function updateFullscreenButtonVisibility() {
        const btn = document.getElementById(FULLSCREEN_BTN_ID);
        if (!btn) {
            return;
        }
        // document.fullscreenElement only reflects HTML5 Fullscreen API
        // state -- the active path in chrome mode, which is this app's
        // actual default mode. Native window mode has no equivalent DOM
        // signal to poll cheaply, so the button is simply left visible
        // there rather than guessing at native window state.
        const alreadyFullscreen = !isNativeWindowMode() && !!document.fullscreenElement;
        btn.style.display = alreadyFullscreen ? "none" : "block";
    }

    function insertFullscreenButton() {
        if (document.getElementById(FULLSCREEN_BTN_ID)) {
            return;
        }

        const btn = document.createElement("button");
        btn.id = FULLSCREEN_BTN_ID;
        btn.type = "button";
        btn.title = "Enter fullscreen (F11)";
        btn.textContent = "\u26F6"; // ⛶
        // Stays out of the TV UI's own D-pad/arrow-key navigation order
        // (see the Gamepad section below for why this app avoids teaching
        // youtube.com/tv a second input model) -- click/touch only, by
        // design, not by omission.
        btn.tabIndex = -1;

        Object.assign(btn.style, {
            position: "fixed",
            bottom: "16px",
            right: "16px",
            zIndex: "2147483647",
            width: "44px",
            height: "44px",
            borderRadius: "50%",
            border: "none",
            background: "rgba(0, 0, 0, 0.55)",
            color: "#fff",
            fontSize: "20px",
            lineHeight: "44px",
            textAlign: "center",
            padding: "0",
            cursor: "pointer",
            opacity: "0.55",
            transition: "opacity 0.15s ease"
        });
        btn.addEventListener("mouseenter", () => { btn.style.opacity = "1"; });
        btn.addEventListener("mouseleave", () => { btn.style.opacity = "0.55"; });
        btn.addEventListener("click", (e) => {
            e.preventDefault();
            e.stopPropagation();
            toggleFullScreen();
        });

        document.body.appendChild(btn);
        document.addEventListener("fullscreenchange", updateFullscreenButtonVisibility);
        updateFullscreenButtonVisibility();
    }

    function initFullscreenButton() {
        if (document.body) {
            insertFullscreenButton();
        } else {
            // Defensive: injectScript timing relative to document readiness
            // isn't guaranteed on a page this app doesn't control.
            document.addEventListener("DOMContentLoaded", insertFullscreenButton, { once: true });
        }
    }

    // --- On-screen Immersive Mode button -----------------------------------
    //
    // What this is: a user-defined, persisted "lock this down" setting,
    // toggled by its own dedicated button (top-left, mirroring where a
    // native window control would sit, deliberately away from the
    // fullscreen button's bottom-right corner so the two are never
    // mistaken for one control) -- separate from, and not bound to, F11.
    //
    // What "immersive" means when it's on:
    //   1. Fullscreen, right now, this session (same mechanism as the
    //      fullscreen button above).
    //   2. A best-effort, same-session JS guard against the obvious
    //      keyboard/mouse paths into devtools (see isDevToolsShortcut()
    //      and the contextmenu listener registered below).
    //   3. The *real* lockdown -- actual Chrome-level devtools/kiosk
    //      hardening -- applied the next time ARKtube is launched, by
    //      whichever packaging launcher started it (see
    //      packaging/linux/build-deb.sh and packaging/linux/AppRun).
    //
    // Why (3) can't happen immediately, from here: chrome mode's Chrome
    // process is a separate, already-running process by the time this
    // script executes (chrome.cpp spawns it once, with args baked in at
    // that moment); nothing server-side re-reads its own config file
    // mid-session. And this script deliberately can't ask Neutralino to
    // relaunch itself with new args either -- that native call
    // (os.execCommand, which is what Neutralino.app.restartProcess()
    // uses under the hood) is intentionally left OFF this app's
    // nativeAllowList in neutralino.config.json, because this script runs
    // on youtube.com/tv's own origin, a page this app doesn't control,
    // and giving that page arbitrary command-exec capability just to
    // support this button would be a far bigger hole than the button is
    // worth. So instead: this script's only job is to remember the user's
    // choice (via Neutralino.storage, a small sandboxed key/value store --
    // see neutralino.config.json's nativeAllowList -- NOT youtube.com's
    // own localStorage, which this app doesn't own and could be cleared
    // from YouTube's own settings). The packaging launcher -- a trusted,
    // local script, not a remote page -- reads that same persisted value
    // directly off disk and decides the real Chrome flags for the next
    // launch. Two different layers, two different trust levels, each
    // deciding only what it's actually able to enforce.
    const IMMERSIVE_BTN_ID = "arktube-immersive-btn";
    const IMMERSIVE_STORAGE_KEY = "immersiveMode";

    let immersiveModeEnabled = false;

    function isDevToolsShortcut(e) {
        if (e.key === "F12") {
            return true;
        }
        const key = (e.key || "").toLowerCase();
        if ((e.ctrlKey || e.metaKey) && e.shiftKey && (key === "i" || key === "j" || key === "c")) {
            return true; // Inspect / Console / element-picker shortcuts
        }
        if ((e.ctrlKey || e.metaKey) && key === "u") {
            return true; // View source
        }
        return false;
    }

    function updateImmersiveButtonVisual() {
        const btn = document.getElementById(IMMERSIVE_BTN_ID);
        if (!btn) {
            return;
        }
        btn.textContent = immersiveModeEnabled ? "\u{1F512}" : "\u{1F513}"; // 🔒 / 🔓
        btn.title = immersiveModeEnabled
            ? "Immersive Mode: ON -- locked down fullscreen. Click to turn off (fully applies after restart)."
            : "Immersive Mode: OFF. Click to lock down fullscreen (fully applies after restart).";
    }

    async function loadImmersiveModePreference() {
        try {
            const value = await Neutralino.storage.getData(IMMERSIVE_STORAGE_KEY);
            immersiveModeEnabled = value === "1";
        } catch (err) {
            // No stored preference yet (first run, or the key was cleared)
            // -- default OFF. Never assume ON: an unreadable/missing
            // preference must fail open to today's existing behavior, not
            // silently lock someone out of devtools they never asked to
            // give up.
            immersiveModeEnabled = false;
        }
        updateImmersiveButtonVisual();
    }

    async function saveImmersiveModePreference(enabled) {
        try {
            await Neutralino.storage.setData(IMMERSIVE_STORAGE_KEY, enabled ? "1" : "0");
        } catch (err) {
            console.error("Error saving Immersive Mode preference:", err);
        }
    }

    async function toggleImmersiveMode() {
        immersiveModeEnabled = !immersiveModeEnabled;
        updateImmersiveButtonVisual();
        await saveImmersiveModePreference(immersiveModeEnabled);

        if (immersiveModeEnabled) {
            await toggleFullScreen();
        }

        try {
            Neutralino.os.showMessageBox(
                immersiveModeEnabled ? "Immersive Mode enabled" : "Immersive Mode disabled",
                immersiveModeEnabled
                    ? "Fullscreen is on now, and devtools shortcuts are blocked for this session. Restart ARKtube to also lock down Chrome itself (devtools, kiosk mode)."
                    : "Immersive Mode is off. Restart ARKtube to fully restore normal Chrome mode."
            );
        } catch (err) {
            console.error("Error showing Immersive Mode message box:", err);
        }
    }

    function insertImmersiveButton() {
        if (document.getElementById(IMMERSIVE_BTN_ID)) {
            return;
        }

        const btn = document.createElement("button");
        btn.id = IMMERSIVE_BTN_ID;
        btn.type = "button";
        btn.tabIndex = -1; // click/touch only, same reasoning as the fullscreen button above

        Object.assign(btn.style, {
            position: "fixed",
            top: "16px",
            left: "16px",
            zIndex: "2147483647",
            width: "44px",
            height: "44px",
            borderRadius: "50%",
            border: "none",
            background: "rgba(0, 0, 0, 0.55)",
            color: "#fff",
            fontSize: "20px",
            lineHeight: "44px",
            textAlign: "center",
            padding: "0",
            cursor: "pointer",
            opacity: "0.55",
            transition: "opacity 0.15s ease"
        });
        btn.addEventListener("mouseenter", () => { btn.style.opacity = "1"; });
        btn.addEventListener("mouseleave", () => { btn.style.opacity = "0.55"; });
        btn.addEventListener("click", (e) => {
            e.preventDefault();
            e.stopPropagation();
            toggleImmersiveMode();
        });

        document.body.appendChild(btn);
        updateImmersiveButtonVisual();
    }

    function initImmersiveButton() {
        if (document.body) {
            insertImmersiveButton();
        } else {
            document.addEventListener("DOMContentLoaded", insertImmersiveButton, { once: true });
        }
        loadImmersiveModePreference();
    }

    async function exitFullScreenIfActive() {
        try {
            if (isNativeWindowMode()) {
                const isFull = await Neutralino.window.isFullScreen();
                if (isFull) {
                    await Neutralino.window.exitFullScreen();
                }
            } else if (document.fullscreenElement) {
                await document.exitFullscreen();
            }
        } catch (err) {
            console.error("Error handling Escape key:", err);
        }
    }

    function goHome() {
        // /tv is a hash-routed SPA (see the url in neutralino.config.json).
        // Changing the hash lets its own router handle navigation in place,
        // instead of a full document reload -- which would drop playback
        // and force this injected script to run all over again from a
        // fresh, un-initialized document.
        try {
            if (location.hash !== "#/") {
                location.hash = "/";
            }
        } catch (err) {
            console.error("Error navigating home:", err);
        }
    }

    function onKeyDown(e) {
        // Immersive Mode's keyboard-shortcut guard runs first and can
        // swallow the event outright -- see insertImmersiveButton() below
        // for what this is and, importantly, what it isn't (a real
        // security boundary).
        if (immersiveModeEnabled && isDevToolsShortcut(e)) {
            e.preventDefault();
            e.stopPropagation();
            return;
        }

        // youtube.com/tv is built for a 10-foot, full-screen, remote-driven
        // experience (this is the same UI Cobalt renders on certified TVs).
        // Escape only backs out of fullscreen (it never quits the app
        // outright); Home is desktop-app augmentation on top of that,
        // since a real remote's Home button has no keyboard equivalent
        // otherwise.
        //
        // F11 is deliberately NOT handled here. Chrome mode (this app's
        // actual default -- see neutralino.config.json) launches a real,
        // separate Chrome/Chromium process (chrome.cpp), which already
        // owns a native, built-in F11 fullscreen toggle of its own. This
        // script used to *also* call toggleFullScreen() on the same
        // keypress, which meant two independent handlers -- Chrome's
        // native one and this injected one -- both raced to answer the
        // same physical key. F11 is now left alone as Chrome's own,
        // user-expected behavior. This app's own "Immersive Mode" concept
        // (see insertImmersiveButton() below) is a deliberately separate,
        // explicitly-triggered, persisted setting instead of being tied
        // to a key Chrome already owns -- no more dual authority over the
        // same input.
        if (e.key === "Escape") {
            exitFullScreenIfActive();
        } else if (e.key === "Home") {
            e.preventDefault();
            goHome();
        }
    }

    // --- Gamepad / joystick / TV-remote input --------------------------
    //
    // youtube.com/tv already drives its whole UI off keyboard events
    // (ArrowUp/Down/Left/Right, Enter, Escape/Backspace - see
    // onKeyDown above), so the simplest way to make a physical
    // controller or an IR/Bluetooth remote that reports itself to the
    // OS as a HID gamepad "just work" is to read it with the standard
    // Gamepad API and re-dispatch the same synthetic KeyboardEvents
    // that a real keyboard press would produce, instead of teaching
    // the YouTube page a second input model. This runs happily in both
    // window mode and chrome mode, since the Gamepad API is a normal
    // browser API with no native/Neutralino dependency.
    const GAMEPAD_BUTTON_TO_KEY = {
        0: "Enter",       // A / Cross / Select / OK
        1: "Escape",      // B / Circle / Back
        12: "ArrowUp",    // D-pad up
        13: "ArrowDown",  // D-pad down
        14: "ArrowLeft",  // D-pad left
        15: "ArrowRight", // D-pad right
        9: "Home",        // Start / Options / Menu - treated as the remote's Home key
        8: "Escape"       // Select / Back (second-controller layouts)
    };

    // Left-stick fallback for controllers that don't expose a D-pad as
    // discrete buttons (axes[0] = horizontal, axes[1] = vertical).
    const STICK_DEADZONE = 0.5;

    const REPEAT_INITIAL_DELAY_MS = 400;
    const REPEAT_INTERVAL_MS = 150;

    // Per-key repeat state, keyed by the KeyboardEvent.key value it maps to.
    const heldKeys = Object.create(null);
    let gamepadPollHandle = null;
    let connectedGamepadCount = 0;

    function dispatchSyntheticKey(key) {
        try {
            window.dispatchEvent(new KeyboardEvent("keydown", {
                key,
                bubbles: true,
                cancelable: true
            }));
        } catch (err) {
            console.error("Error dispatching synthetic key event for gamepad input:", err);
        }
    }

    function logGamepadEvent(message) {
        console.log(message);
        try {
            if (typeof Neutralino !== "undefined" && Neutralino.debug && Neutralino.debug.log) {
                Neutralino.debug.log(message);
            }
        } catch (err) {
            // debug.log is best-effort only; never let logging break input handling.
        }
    }

    function readActiveButtonKeys(gamepad) {
        const active = [];

        for (const [index, key] of Object.entries(GAMEPAD_BUTTON_TO_KEY)) {
            const button = gamepad.buttons[index];
            if (button && button.pressed) {
                active.push(key);
            }
        }

        // Only fall back to the analog stick when no D-pad button is
        // already driving navigation, so the two input styles don't fight.
        const dpadActive = active.some((k) =>
            k === "ArrowUp" || k === "ArrowDown" || k === "ArrowLeft" || k === "ArrowRight"
        );
        if (!dpadActive && gamepad.axes && gamepad.axes.length >= 2) {
            const [x, y] = gamepad.axes;
            if (y <= -STICK_DEADZONE) active.push("ArrowUp");
            else if (y >= STICK_DEADZONE) active.push("ArrowDown");
            if (x <= -STICK_DEADZONE) active.push("ArrowLeft");
            else if (x >= STICK_DEADZONE) active.push("ArrowRight");
        }

        return active;
    }

    function pollGamepads(timestamp) {
        try {
            const pads = (navigator.getGamepads && navigator.getGamepads()) || [];
            const activeThisFrame = new Set();

            for (const gamepad of pads) {
                if (!gamepad) continue;
                for (const key of readActiveButtonKeys(gamepad)) {
                    activeThisFrame.add(key);
                }
            }

            // Fire-on-press-and-repeat, mirroring how a held keyboard key
            // or a held remote button auto-repeats, instead of flooding
            // one synthetic keydown per animation frame (~60/sec).
            for (const key of activeThisFrame) {
                const state = heldKeys[key];
                if (!state) {
                    dispatchSyntheticKey(key);
                    heldKeys[key] = { pressedAt: timestamp, lastRepeat: timestamp };
                } else if (
                    timestamp - state.pressedAt >= REPEAT_INITIAL_DELAY_MS &&
                    timestamp - state.lastRepeat >= REPEAT_INTERVAL_MS
                ) {
                    dispatchSyntheticKey(key);
                    state.lastRepeat = timestamp;
                }
            }

            for (const key of Object.keys(heldKeys)) {
                if (!activeThisFrame.has(key)) {
                    delete heldKeys[key];
                }
            }
        } catch (err) {
            console.error("Error polling gamepad state:", err);
        }

        gamepadPollHandle = window.requestAnimationFrame(pollGamepads);
    }

    function startGamepadPolling() {
        if (gamepadPollHandle === null) {
            gamepadPollHandle = window.requestAnimationFrame(pollGamepads);
        }
    }

    function stopGamepadPollingIfIdle() {
        if (connectedGamepadCount <= 0 && gamepadPollHandle !== null) {
            window.cancelAnimationFrame(gamepadPollHandle);
            gamepadPollHandle = null;
            for (const key of Object.keys(heldKeys)) {
                delete heldKeys[key];
            }
        }
    }

    function onGamepadConnected(e) {
        connectedGamepadCount += 1;
        logGamepadEvent(
            `Gamepad/remote connected: "${e.gamepad.id}" (index ${e.gamepad.index}, ` +
            `${e.gamepad.buttons.length} buttons, ${e.gamepad.axes.length} axes)`
        );
        startGamepadPolling();
    }

    function onGamepadDisconnected(e) {
        connectedGamepadCount = Math.max(0, connectedGamepadCount - 1);
        logGamepadEvent(`Gamepad/remote disconnected: "${e.gamepad.id}" (index ${e.gamepad.index})`);
        stopGamepadPollingIfIdle();
    }

    function initGamepadSupport() {
        if (!("getGamepads" in navigator)) {
            logGamepadEvent("Gamepad API not available in this environment; skipping controller/remote support.");
            return;
        }

        window.addEventListener("gamepadconnected", onGamepadConnected);
        window.addEventListener("gamepaddisconnected", onGamepadDisconnected);

        // Some platforms (and some Bluetooth remotes that register as HID
        // gamepads) never fire "gamepadconnected" for a pad that was
        // already paired before the page loaded, so also check directly
        // on init in case one is already sitting there.
        try {
            const pads = navigator.getGamepads() || [];
            connectedGamepadCount = Array.from(pads).filter(Boolean).length;
            if (connectedGamepadCount > 0) {
                logGamepadEvent(`${connectedGamepadCount} gamepad/remote already connected at startup.`);
                startGamepadPolling();
            }
        } catch (err) {
            console.error("Error checking for already-connected gamepads:", err);
        }
    }

    let resizeSettleTimer = null;

    function onWindowResize() {
        // The TV/Leanback UI computes player and row-grid dimensions on
        // load and again on 'resize', but a real native maximize/restore
        // can fire an intermediate 'resize' mid-animation, before the
        // window has actually settled at its final size -- leaving the
        // page's own layout math stale at whatever size it caught partway
        // through. Debounce and re-fire 'resize' once movement has
        // actually stopped, so the page gets one more accurate measurement
        // to work with after the window is done changing size.
        if (resizeSettleTimer) {
            clearTimeout(resizeSettleTimer);
        }
        resizeSettleTimer = setTimeout(() => {
            resizeSettleTimer = null;
            try {
                window.dispatchEvent(new Event("resize"));
            } catch (err) {
                console.error("Error re-dispatching settled resize:", err);
            }
        }, 250);
    }

    function setTray() {
        if (NL_MODE !== "window") {
            return;
        }
        try {
            Neutralino.os.setTray({
                icon: "/resources/icons/trayIcon.png",
                menuItems: [
                    { id: "VERSION", text: "Get version" },
                    { id: "SEP", text: "-" },
                    { id: "QUIT", text: "Quit" }
                ]
            });
        } catch (err) {
            console.error("Error setting tray:", err);
        }
    }

    // --- Cursor auto-hide (idle) --------------------------------------
    //
    // Standardizes cursor-hiding across every session type and platform
    // ARKtube runs on. Webtop's gnome-kiosk-script (see the `webtop`
    // branch's docs/STAGE-7-VISIBILITY-AND-CURSOR.md) can only drive
    // this via unclutter-xfixes, and only on the X11 session at that --
    // under Wayland the cursor image is drawn by whichever client owns
    // the surface under the pointer, so a background daemon outside
    // that client structurally cannot reach in and hide it. Doing this
    // here instead, inside the webview that *is* that client, covers
    // Wayland, X11, Windows, and macOS with one implementation, rather
    // than depending on a session-layer tool that only ever worked on
    // one of ARKtube's supported sessions.
    //
    // 10s matches webtop's own `unclutter-xfixes --timeout 10` /
    // `unclutter -idle 10`, so the two layers agree on timing even
    // though only one of them can actually still be doing anything
    // (webtop's X11-only fallback and this both fire in that case; only
    // this one does anywhere else).
    const CURSOR_IDLE_MS = 10000;
    const CURSOR_HIDDEN_CLASS = "arktube-cursor-hidden";
    let cursorIdleTimer = null;

    function injectCursorAutoHideStyle() {
        if (document.getElementById("arktube-cursor-autohide-style")) {
            return;
        }
        const style = document.createElement("style");
        style.id = "arktube-cursor-autohide-style";
        style.textContent =
            `.${CURSOR_HIDDEN_CLASS}, .${CURSOR_HIDDEN_CLASS} * { cursor: none !important; }`;
        document.head.appendChild(style);
    }

    function resetCursorIdleTimer() {
        document.documentElement.classList.remove(CURSOR_HIDDEN_CLASS);
        if (cursorIdleTimer) {
            clearTimeout(cursorIdleTimer);
        }
        cursorIdleTimer = setTimeout(() => {
            document.documentElement.classList.add(CURSOR_HIDDEN_CLASS);
        }, CURSOR_IDLE_MS);
    }

    function onCursorActivity(e) {
        // Gamepad/remote input is re-dispatched as synthetic keydown
        // events (see dispatchSyntheticKey() below) so youtube.com/tv's
        // own D-pad handling picks it up -- but that isn't cursor
        // activity, and letting it count would mean a controller-only
        // session (exactly the TV/kiosk case this app targets) never
        // actually keeps the cursor hidden. `isTrusted` is false on
        // events constructed with `new KeyboardEvent(...)`, true on
        // anything the platform generated from real hardware input, so
        // it's what distinguishes the two here rather than tracking
        // "was this dispatch synthetic" by hand at every call site.
        if (!e.isTrusted) {
            return;
        }
        resetCursorIdleTimer();
    }

    function initCursorAutoHide() {
        if (!document.head) {
            // Defensive: same injectScript timing concern as elsewhere
            // in this file -- document.head isn't guaranteed to exist
            // yet when this script first runs.
            document.addEventListener("DOMContentLoaded", initCursorAutoHide, { once: true });
            return;
        }
        injectCursorAutoHideStyle();
        window.addEventListener("mousemove", onCursorActivity, true);
        window.addEventListener("mousedown", onCursorActivity, true);
        window.addEventListener("wheel", onCursorActivity, true);
        window.addEventListener("keydown", onCursorActivity, true);
        resetCursorIdleTimer();
    }

    // Initialize Neutralino with error handling
    try {
        Neutralino.init();
    } catch (err) {
        console.error("Error initializing Neutralino:", err);
        return;
    }

    // exitProcessOnClose is now false (see neutralino.config.json), so this listener
    // is what actually terminates the app on the native close ("X") button -
    // previously it was registered on a page that never loaded, so the close button
    // fell back to the native default with no chance to run cleanup logic.
    try {
        Neutralino.events.on("windowClose", onWindowClose);
        Neutralino.events.on("trayMenuItemClicked", onTrayMenuItemClicked);
    } catch (err) {
        console.error("Error registering Neutralino events:", err);
    }

    window.addEventListener("keydown", onKeyDown, true);
    window.addEventListener("resize", onWindowResize);
    // Same-session, best-effort half of Immersive Mode's devtools guard --
    // blocks the right-click "Inspect" entry point. See
    // insertImmersiveButton() above for what this can and can't do.
    document.addEventListener("contextmenu", (e) => {
        if (immersiveModeEnabled) {
            e.preventDefault();
        }
    }, true);
    initGamepadSupport();
    initFullscreenButton();
    initImmersiveButton();
    initCursorAutoHide();

    // TODO: https://github.com/neutralinojs/neutralinojs/issues/615
    if (NL_OS !== "Darwin") {
        setTray();
    }
})();
