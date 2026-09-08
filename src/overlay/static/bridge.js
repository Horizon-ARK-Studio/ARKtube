// bridge.js — replaces pywebview's injected `window.pywebview.api` object
// now that the host process (overlay.js, GJS) is a plain WebKit2 GTK app
// with no pywebview involved. Loaded before app.js (see index.html) so
// `window.pywebview` already exists by the time app.js's own
// `if (window.pywebview) { init(); } ...` check runs -- see that file's
// pywebview-bridge section for the calling side this replaces.
//
// Wire format: JS -> native calls go out through
// `window.webkit.messageHandlers.arktube.postMessage(...)`, which
// overlay.js listens for via WebKitUserContentManager's
// "script-message-received::arktube" signal. Each call carries a
// numeric `id`; overlay.js replies by running `window.__cgResolve(id,
// json)` or `window.__cgReject(id, message)` back into this page once
// the requested method finishes -- that's the other half of this
// file's pending-call table below.
(function () {
  "use strict";

  let nextId = 1;
  const pending = new Map();

  function callNative(method, args) {
    return new Promise((resolve, reject) => {
      if (!window.webkit || !window.webkit.messageHandlers || !window.webkit.messageHandlers.arktube) {
        reject(new Error("arktube message handler is not available"));
        return;
      }
      const id = nextId++;
      pending.set(id, { resolve, reject });
      try {
        window.webkit.messageHandlers.arktube.postMessage(
          JSON.stringify({ id, method, args })
        );
      } catch (err) {
        pending.delete(id);
        reject(err);
      }
    });
  }

  // Called from overlay.js once a dispatched call finishes successfully.
  // `resultJson` is the JSON-encoded return value (or null/undefined for
  // methods with no meaningful result, e.g. poweroff()).
  window.__cgResolve = function (id, resultJson) {
    const entry = pending.get(id);
    if (!entry) return;
    pending.delete(id);
    try {
      entry.resolve(
        resultJson === null || resultJson === undefined
          ? null
          : JSON.parse(resultJson)
      );
    } catch (err) {
      entry.reject(err);
    }
  };

  // Called from overlay.js when a dispatched call threw on the native
  // side -- see overlay.js's bridge dispatcher for what gets logged
  // there in addition to this rejection.
  window.__cgReject = function (id, message) {
    const entry = pending.get(id);
    if (!entry) return;
    pending.delete(id);
    entry.reject(new Error(message || "native call failed"));
  };

  // Every method static/app.js's callApi() ever invokes (see that
  // file's `grep callApi` surface) -- kept as a flat allowlist rather
  // than a catch-all proxy so a typo'd method name fails at the call
  // site instead of silently posting a message nothing on the other
  // end will recognize.
  const METHODS = [
    "get_status",
    "set_panel",
    "set_volume",
    "toggle_mute",
    "set_brightness",
    "network_scan",
    "network_toggle_wifi_radio",
    "network_connect",
    "lock",
    "unlock",
    "logout",
    "poweroff",
    "reboot",
  ];

  const api = {};
  for (const method of METHODS) {
    api[method] = (...args) => callNative(method, args);
  }

  // Set synchronously, before app.js runs (script tag order in
  // index.html) -- app.js's own `if (window.pywebview)` check sees this
  // immediately, so the `pywebviewready` event path in app.js is dead
  // code here but left alone since it's harmless and keeps app.js
  // unmodified.
  window.pywebview = { api };
})();
