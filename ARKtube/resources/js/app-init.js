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
        // youtube.com/tv is built for a 10-foot, full-screen, remote-driven
        // experience (this is the same UI Cobalt renders on certified TVs).
        // F11 mirrors normal browser/TV-app behavior; Escape only backs out
        // of fullscreen (it never quits the app outright); Home is desktop-
        // app augmentation on top of that, since a real remote's Home
        // button has no keyboard equivalent otherwise.
        if (e.key === "F11") {
            e.preventDefault();
            toggleFullScreen();
        } else if (e.key === "Escape") {
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
    initGamepadSupport();

    // TODO: https://github.com/neutralinojs/neutralinojs/issues/615
    if (NL_OS !== "Darwin") {
        setTray();
    }
})();
