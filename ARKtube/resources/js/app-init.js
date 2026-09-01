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
    // the window mode." In chrome mode (this app's defaultMode) there's no
    // Neutralino-owned native window handle for it to control, and the
    // calls silently no-op instead of erroring (see
    // neutralinojs/neutralinojs#751). That's why F11/Escape used to appear
    // to do nothing, and why the *real* window being maximized/restored --
    // the actual OS-native Chrome window -- was invisible to this script
    // entirely. In chrome mode we're just a normal page in a real browser
    // tab, so the standard DOM Fullscreen API is the correct tool instead.
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

    // TODO: https://github.com/neutralinojs/neutralinojs/issues/615
    if (NL_OS !== "Darwin") {
        setTray();
    }
})();
