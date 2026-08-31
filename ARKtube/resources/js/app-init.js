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
    function safeExit() {
        // Gracefully shut the native process down instead of relying on
        // exitProcessOnClose to hard-kill it (which skipped this handler entirely).
        Neutralino.app.exit();
    }

    function onWindowClose() {
        // Hook point for any future save-state/cleanup work (see docs/PROBLEM-STATEMENT.md
        // section 9, "Navigation State") before the process actually exits.
        safeExit();
    }

    function onTrayMenuItemClicked(event) {
        switch (event.detail.id) {
            case "VERSION":
                Neutralino.os.showMessageBox(
                    "Version information",
                    `Neutralinojs server: v${NL_VERSION} | Neutralinojs client: v${NL_CVERSION}`
                );
                break;
            case "QUIT":
                safeExit();
                break;
        }
    }

    function setTray() {
        if (NL_MODE !== "window") {
            return;
        }
        Neutralino.os.setTray({
            icon: "/resources/icons/trayIcon.png",
            menuItems: [
                { id: "VERSION", text: "Get version" },
                { id: "SEP", text: "-" },
                { id: "QUIT", text: "Quit" }
            ]
        });
    }

    Neutralino.init();

    // exitProcessOnClose is now false (see neutralino.config.json), so this listener
    // is what actually terminates the app on the native close ("X") button -
    // previously it was registered on a page that never loaded, so the close button
    // fell back to the native default with no chance to run cleanup logic.
    Neutralino.events.on("windowClose", onWindowClose);
    Neutralino.events.on("trayMenuItemClicked", onTrayMenuItemClicked);

    // TODO: https://github.com/neutralinojs/neutralinojs/issues/615
    if (NL_OS !== "Darwin") {
        setTray();
    }
})();
