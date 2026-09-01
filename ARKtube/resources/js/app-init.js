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

    async function toggleFullScreen() {
        try {
            const isFull = await Neutralino.window.isFullScreen();
            if (isFull) {
                await Neutralino.window.exitFullScreen();
            } else {
                await Neutralino.window.setFullScreen();
            }
        } catch (err) {
            console.error("Error toggling fullscreen:", err);
        }
    }

    function onKeyDown(e) {
        // youtube.com/tv is built for a 10-foot, full-screen, remote-driven
        // experience (this is the same UI Cobalt renders on certified TVs).
        // F11 mirrors normal browser/TV-app behavior; Escape only backs out
        // of fullscreen (it never quits the app outright).
        if (e.key === "F11") {
            e.preventDefault();
            toggleFullScreen();
        } else if (e.key === "Escape") {
            try {
                Neutralino.window.isFullScreen().then((isFull) => {
                    if (isFull) {
                        Neutralino.window.exitFullScreen();
                    }
                });
            } catch (err) {
                console.error("Error handling Escape key:", err);
            }
        }
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

    // TODO: https://github.com/neutralinojs/neutralinojs/issues/615
    if (NL_OS !== "Darwin") {
        setTray();
    }
})();
