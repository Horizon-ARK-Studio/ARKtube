/*
    user-script.js -- ARKtube Linux native port.

    Injected by src/main.c via WebKitUserContentManager, at
    document-start, into every frame of youtube.com/tv. Unlike the old
    Neutralino app-init.js (../../ARKtube/resources/js/app-init.js),
    this script runs directly on the real youtube.com/tv page -- there
    is no separate local shell page and no second embedded Chrome
    process in this architecture, so there's no Neutralino global to
    guard against and nothing here needs a "which page/process is this"
    branch.

    F11 (fullscreen) and Escape (exit fullscreen) are handled natively
    in src/main.c instead of here, since a plain GtkWindow can do that
    directly with no round-trip through page JS -- see
    on_window_key_press() there. Everything below is logic that only
    ever made sense as page-side JS to begin with, ported as close to
    1:1 as the drop of the Neutralino APIs allows. See
    ../../docs/PORTING-NOTES.md for what from app-init.js is NOT yet
    here (tray menu, Immersive Mode lockdown, persisted settings) and
    why.
*/
(function () {
    if (window.__arktubeLinuxInitialized) {
        return;
    }
    window.__arktubeLinuxInitialized = true;

    // --- Device identity spoof ------------------------------------------
    //
    // The User-Agent HTTP header (see src/main.c's
    // webkit_settings_set_user_agent()) is necessary but not sufficient:
    // youtube.com/tv's own bootstrap JS also reads navigator.* and
    // screen.* directly, and a plain desktop WebKitGTK view reports
    // desktop-shaped values there regardless of what the UA header says
    // (mouse-capable, arbitrary window size, high maxTouchPoints-less
    // desktop signature). Every open-source TV-mode wrapper that works
    // reliably (VacuumTube, youtube-tv-ua-spoof, the MTA browser's
    // "; SMART-TV; Tizen 4.0" / "; Roku 3/7.0" workaround) pairs its UA
    // string with exactly this: patch what the page's own JS can
    // introspect, before that JS runs.
    //
    // This has to happen at document-start (see main.c's
    // WEBKIT_USER_SCRIPT_INJECT_AT_DOCUMENT_START), ahead of any of
    // youtube.com/tv's own script tags, or the page will have already
    // read and cached the real values by the time this ran.
    //
    // Defined on the *prototype*, not the navigator/screen instance
    // directly: youtube.com/tv can call
    // Object.getOwnPropertyDescriptor(Navigator.prototype, 'userAgent')
    // as an integrity check the way several anti-fingerprinting-evasion
    // libraries do, and an own-property shadowing the prototype getter
    // would fail that check even though navigator.userAgent itself
    // reads back correctly either way.
    function spoofProperty(proto, name, value) {
        try {
            const desc = Object.getOwnPropertyDescriptor(proto, name);
            if (!desc || !desc.configurable) {
                return;
            }
            Object.defineProperty(proto, name, {
                get: function () { return value; },
                configurable: true,
                enumerable: desc.enumerable
            });
        } catch (err) {
            console.error(`ARKtube: error spoofing ${name}:`, err);
        }
    }

    // Kept in sync with src/main.c's ARKTUBE_USER_AGENT -- the HTTP
    // header and navigator.userAgent must agree, or the mismatch itself
    // becomes a detectable signal.
    const ARKTUBE_USER_AGENT =
        "Mozilla/5.0 (PS4; Leanback Shell) Cobalt/26.lts.0-qa (compatible)";

    spoofProperty(Navigator.prototype, "userAgent", ARKTUBE_USER_AGENT);
    spoofProperty(Navigator.prototype, "appVersion", ARKTUBE_USER_AGENT);
    spoofProperty(Navigator.prototype, "platform", "PS4");
    spoofProperty(Navigator.prototype, "vendor", "Sony Interactive Entertainment Inc.");
    // A remote-driven TV has no touchscreen and no mouse pointer in the
    // sense the page's own touch/pointer feature checks expect -- 0 is
    // what a real Leanback client reports, and what stops the page from
    // ever offering the touch-optimized (rather than D-pad-optimized)
    // control layout.
    spoofProperty(Navigator.prototype, "maxTouchPoints", 0);

    // A real TV panel is a fixed native resolution -- 1080p is what the
    // UA string above implies and is the safest choice: it matches
    // what a PS4 typically reports and is common enough not to look
    // anomalous on its own, regardless of what size this app's actual
    // GTK window happens to be maximized/resized to.
    const ARKTUBE_SCREEN_WIDTH = 1920;
    const ARKTUBE_SCREEN_HEIGHT = 1080;
    spoofProperty(Screen.prototype, "width", ARKTUBE_SCREEN_WIDTH);
    spoofProperty(Screen.prototype, "height", ARKTUBE_SCREEN_HEIGHT);
    spoofProperty(Screen.prototype, "availWidth", ARKTUBE_SCREEN_WIDTH);
    spoofProperty(Screen.prototype, "availHeight", ARKTUBE_SCREEN_HEIGHT);
    // Same own-vs-inherited concern as above: devicePixelRatio is a
    // Window.prototype getter, not an own property of window itself.
    spoofProperty(Window.prototype, "devicePixelRatio", 1);

    // --- Home key: SPA navigation --------------------------------------
    //
    // /tv is a hash-routed SPA. Changing the hash lets its own router
    // handle navigation in place, instead of a full document reload --
    // which would drop playback and re-run this script from scratch on
    // a fresh, un-initialized document. Ported unchanged from
    // app-init.js's goHome().
    function goHome() {
        try {
            if (location.hash !== "#/") {
                location.hash = "/";
            }
        } catch (err) {
            console.error("ARKtube: error navigating home:", err);
        }
    }

    function onKeyDown(e) {
        if (e.key === "Home") {
            e.preventDefault();
            goHome();
        }
    }

    // --- Gamepad / joystick / TV-remote input --------------------------
    //
    // youtube.com/tv already drives its whole UI off keyboard events
    // (ArrowUp/Down/Left/Right, Enter, Escape/Backspace), so the
    // simplest way to make a physical controller or an IR/Bluetooth
    // remote that reports itself to the OS as a HID gamepad "just
    // work" is to read it with the standard Gamepad API and re-dispatch
    // the same synthetic KeyboardEvents a real keyboard press would
    // produce, instead of teaching the page a second input model.
    // Ported unchanged from app-init.js.
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
            console.error("ARKtube: error dispatching synthetic key for gamepad input:", err);
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
            console.error("ARKtube: error polling gamepad state:", err);
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
        console.log(
            `ARKtube: gamepad/remote connected: "${e.gamepad.id}" ` +
            `(index ${e.gamepad.index}, ${e.gamepad.buttons.length} buttons, ` +
            `${e.gamepad.axes.length} axes)`
        );
        startGamepadPolling();
    }

    function onGamepadDisconnected(e) {
        connectedGamepadCount = Math.max(0, connectedGamepadCount - 1);
        console.log(`ARKtube: gamepad/remote disconnected: "${e.gamepad.id}" (index ${e.gamepad.index})`);
        stopGamepadPollingIfIdle();
    }

    function initGamepadSupport() {
        if (!("getGamepads" in navigator)) {
            console.log("ARKtube: Gamepad API not available in this environment; skipping controller/remote support.");
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
                console.log(`ARKtube: ${connectedGamepadCount} gamepad/remote already connected at startup.`);
                startGamepadPolling();
            }
        } catch (err) {
            console.error("ARKtube: error checking for already-connected gamepads:", err);
        }
    }

    // --- Cursor auto-hide (idle) ----------------------------------------
    //
    // WebKitGTK's own webview draws the cursor itself (this app's window
    // is the client that owns the surface under the pointer, on both X11
    // and Wayland), so doing this here -- inside the page -- covers both
    // sessions with one implementation, the same reasoning app-init.js
    // used. Ported unchanged, at the same 10s timeout.
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
        // events (see dispatchSyntheticKey() above) so youtube.com/tv's
        // own D-pad handling picks it up -- but that isn't cursor
        // activity, and letting it count would mean a controller-only
        // session never actually keeps the cursor hidden. isTrusted is
        // false on events constructed with `new KeyboardEvent(...)`,
        // true on anything the platform generated from real hardware
        // input, so it's what distinguishes the two here.
        if (!e.isTrusted) {
            return;
        }
        resetCursorIdleTimer();
    }

    function initCursorAutoHide() {
        if (!document.head) {
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

    window.addEventListener("keydown", onKeyDown, true);
    initGamepadSupport();
    initCursorAutoHide();
})();
