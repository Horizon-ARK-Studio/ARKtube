#!/usr/bin/env -S gjs -m
//
// overlay.js — GJS replacement for overlay.py.
//
// Same architecture as before: a GTK3 window promoted to a
// wlr-layer-shell-v1 surface via gtk-layer-shell, hosting a WebKit2
// WebView that loads static/index.html/style.css/app.js unmodified.
// What changed is the host language and runtime -- overlay.py's own
// PyGObject + pywebview stack turned out to fail in ways that left no
// trace in any log Sway's `exec` could surface (see this repo's own
// history for that debugging trail). GJS is used here for the same
// reason GNOME Shell itself is: it's a first-party binding of exactly
// the same GTK3/WebKit2/GLib libraries, run directly on the GTK main
// loop instead of through a second abstraction layer (pywebview) on
// top of a second language runtime (CPython) on top of PyGObject.
//
// static/bridge.js is the other half of this change -- it replaces
// pywebview's injected `window.pywebview.api` with a small shim that
// talks to this file over WebKitUserContentManager's script-message
// channel instead. static/app.js itself is untouched: it only ever
// called `window.pywebview.api.*`, never anything pywebview-specific,
// so nothing on that side needed to change.
//
// osd.c and power-menu.c (this directory's siblings) are left exactly
// as they are -- they're already small, standalone GTK3 +
// gtk-layer-shell C programs with no Python involved, and were never
// part of the failure this file exists to fix.

import GLib from "gi://GLib";
import Gio from "gi://Gio";
import Gtk from "gi://Gtk?version=3.0";
import Gdk from "gi://Gdk?version=3.0";

// ---------------------------------------------------------------------------
// Paths + logging. Sway launches this via `exec`, which -- same as it did
// for overlay.py -- gives no terminal to see stdout/stderr on. Everything
// of interest goes to a real file as well as stderr, and logging is set
// up before the two riskiest imports below (WebKit2, GtkLayerShell) run,
// so a missing gir1.2-webkit2-4.1 / gir1.2-gtklayershell-0.1 package logs
// a specific, readable reason instead of GJS just exiting silently.
// ---------------------------------------------------------------------------

const HERE = Gio.File.new_for_path(
  GLib.path_get_dirname(new Error().fileName?.replace(/^file:\/\//, "") || import.meta.url.replace(/^file:\/\//, ""))
).get_path();
const STATIC = GLib.build_filenamev([HERE, "static"]);
const LOG_PATH = GLib.build_filenamev([HERE, "overlay.log"]);
const PID_PATH = GLib.build_filenamev([HERE, "overlay.pid"]);

let logStream = null;
try {
  logStream = Gio.File.new_for_path(LOG_PATH).append_to(
    Gio.FileCreateFlags.NONE,
    null
  );
} catch (e) {
  // Falls back to stderr-only below -- matches overlay.py's own
  // fallback for an unwritable log directory.
  logStream = null;
}

function logLine(level, message) {
  const line = `${new Date().toISOString()} ${level} overlay: ${message}`;
  printerr(line);
  if (logStream) {
    try {
      logStream.write(line + "\n", null);
    } catch (e) {
      // Nothing else to do if the log file itself is failing.
    }
  }
}

const logInfo = (m) => logLine("INFO", m);
const logWarn = (m) => logLine("WARNING", m);
const logError = (m) => logLine("ERROR", m);

logInfo(`logging configured (log file: ${LOG_PATH})`);

// ---------------------------------------------------------------------------
// Risky imports, guarded individually and loaded dynamically (rather than
// as static `import` statements) specifically so a missing typelib logs a
// clear diagnostic here instead of GJS refusing to even start the module.
// ---------------------------------------------------------------------------

let WebKit2, GtkLayerShell;

try {
  ({ default: WebKit2 } = await import("gi://WebKit2?version=4.1"));
} catch (e) {
  logError(
    `failed to import WebKit2 4.1 -- is gir1.2-webkit2-4.1 / ` +
      `libwebkit2gtk-4.1-0 installed? (${e})`
  );
  throw e;
}

try {
  ({ default: GtkLayerShell } = await import("gi://GtkLayerShell?version=0.1"));
} catch (e) {
  logError(
    `failed to import GtkLayerShell 0.1 -- this is the single most likely ` +
      `startup failure. It means gir1.2-gtklayershell-0.1 and/or the ` +
      `libgtk-layer-shell0 shared library it wraps are missing. Without ` +
      `this, the corner menu/power panel/network tile/OSD have no process ` +
      `behind them at all -- volume/brightness still work because those ` +
      `bypass this file entirely via direct Sway bindsyms. (${e})`
  );
  throw e;
}

logInfo("all imports succeeded, continuing startup");

Gio._promisify(
  Gio.Subprocess.prototype,
  "communicate_utf8_async",
  "communicate_utf8_finish"
);

// ---------------------------------------------------------------------------
// Panel geometry -- same four states overlay.py had: the collapsed
// top-right corner bar ('none'), the full settings panel ('overlay'), the
// centered power menu ('power'), and the centered volume/brightness toast
// ('osd'). gtk-layer-shell centers a surface on any axis where neither of
// that axis's edges is anchored, which is what puts 'power' dead-center
// and 'osd' horizontally centered near the top without either needing to
// know the real screen width.
// ---------------------------------------------------------------------------

const BAR_HEIGHT = 56;
const PANEL_HEIGHT = 620;
const POWER_MENU_WIDTH = 420;
const POWER_MENU_HEIGHT = 300;
const OSD_WIDTH = 320;
const OSD_HEIGHT = 210;

function panelGeometry(name, width) {
  switch (name) {
    case "overlay":
      return {
        size: [width, PANEL_HEIGHT],
        anchors: [GtkLayerShell.Edge.TOP, GtkLayerShell.Edge.RIGHT],
        // EXCLUSIVE, not ON_DEMAND -- becoming visible doesn't grant a
        // Wayland surface keyboard focus by itself; something has to
        // claim it, same as lock()/unlock() below.
        keyboardMode: GtkLayerShell.KeyboardMode.EXCLUSIVE,
      };
    case "power":
      return {
        size: [POWER_MENU_WIDTH, POWER_MENU_HEIGHT],
        anchors: [],
        keyboardMode: GtkLayerShell.KeyboardMode.EXCLUSIVE,
      };
    case "osd":
      return {
        size: [OSD_WIDTH, OSD_HEIGHT],
        anchors: [GtkLayerShell.Edge.TOP],
        // No keyboard interaction happens on the toast, so this stays
        // ON_DEMAND rather than stealing focus from ARKtube mid-playback.
        keyboardMode: GtkLayerShell.KeyboardMode.ON_DEMAND,
      };
    case "none":
    default:
      return {
        size: [width, BAR_HEIGHT],
        anchors: [GtkLayerShell.Edge.TOP, GtkLayerShell.Edge.RIGHT],
        keyboardMode: GtkLayerShell.KeyboardMode.ON_DEMAND,
      };
  }
}

function updateLayerShell(gtkWindow, { layer, anchors, exclusiveZone, keyboardMode }) {
  GtkLayerShell.set_layer(gtkWindow, layer);
  for (const edge of [
    GtkLayerShell.Edge.TOP,
    GtkLayerShell.Edge.BOTTOM,
    GtkLayerShell.Edge.LEFT,
    GtkLayerShell.Edge.RIGHT,
  ]) {
    GtkLayerShell.set_anchor(gtkWindow, edge, anchors.includes(edge));
    GtkLayerShell.set_margin(gtkWindow, edge, 0);
  }
  GtkLayerShell.set_exclusive_zone(gtkWindow, exclusiveZone);
  GtkLayerShell.set_keyboard_mode(gtkWindow, keyboardMode);
}

function initLayerShell(gtkWindow, config) {
  let supported = null;
  try {
    supported = GtkLayerShell.is_supported();
  } catch (e) {
    supported = null;
  }
  if (supported === false) {
    logWarn(
      "GtkLayerShell.is_supported() returned false -- the running " +
        "Wayland compositor does not advertise wlr-layer-shell-v1. " +
        "init_for_window() below will likely fail."
    );
  }
  GtkLayerShell.init_for_window(gtkWindow);
  updateLayerShell(gtkWindow, config);
}

// ---------------------------------------------------------------------------
// Shell-out helper. Every caller treats `null` as "control unavailable"
// and degrades the UI rather than throwing -- same contract overlay.py's
// run() had. Built on Gio.Subprocess (async, non-blocking) rather than a
// synchronous call, since this runs on the same GTK main loop thread that
// also has to keep the WebView responsive.
// ---------------------------------------------------------------------------

async function runCmd(argv, timeoutSeconds = 3) {
  let proc;
  try {
    proc = Gio.Subprocess.new(
      argv,
      Gio.SubprocessFlags.STDOUT_PIPE | Gio.SubprocessFlags.STDERR_SILENCE
    );
  } catch (e) {
    return null; // e.g. the binary isn't on PATH
  }

  const cancellable = new Gio.Cancellable();
  const timeoutId = GLib.timeout_add(GLib.PRIORITY_DEFAULT, timeoutSeconds * 1000, () => {
    cancellable.cancel();
    return GLib.SOURCE_REMOVE;
  });

  try {
    const [stdout] = await proc.communicate_utf8_async(null, cancellable);
    GLib.source_remove(timeoutId);
    return proc.get_successful() ? stdout.trim() : null;
  } catch (e) {
    GLib.source_remove(timeoutId);
    return null;
  }
}

// ---------------------------------------------------------------------------
// Overlay state + the same set of operations SystemAPI exposed to app.js,
// just as plain (mostly async) methods instead of pywebview.api-bound
// ones. The bridge dispatch table further down is what actually connects
// these to static/bridge.js's calls.
// ---------------------------------------------------------------------------

class Overlay {
  constructor() {
    this.window = null; // Gtk.Window
    this.webView = null; // WebKit2.WebView
    this.width = 1920; // overwritten in main() from the real screen
    this.locked = false;
  }

  runJs(script) {
    if (!this.webView) return;
    this.webView.run_javascript(script, null, (webView, result) => {
      try {
        webView.run_javascript_finish(result);
      } catch (e) {
        // Page may not have finished loading yet, or the script threw
        // in JS -- neither is fatal here, same as overlay.py's
        // evaluate_js() calls, which never checked their result either.
      }
    });
  }

  // ---- panel state --------------------------------------------------------

  setPanel(panel) {
    if (this.locked) return "locked";
    const geometry = panelGeometry(panel, this.width);
    GLib.idle_add(GLib.PRIORITY_DEFAULT, () => {
      if (this.window) {
        const [width, height] = geometry.size;
        updateLayerShell(this.window, {
          layer: GtkLayerShell.Layer.OVERLAY,
          anchors: geometry.anchors,
          exclusiveZone: -1,
          keyboardMode: geometry.keyboardMode,
        });
        this.window.resize(width, height);
        this.runJs(`window.__cgSetPanel && window.__cgSetPanel(${JSON.stringify(panel)})`);
      }
      return GLib.SOURCE_REMOVE;
    });
    return panel;
  }

  // ---- status polling -------------------------------------------------------

  async getStatus() {
    const battery = await this.battery();
    return {
      network: await this.network(),
      volume: await this.volume(),
      brightness: await this.brightness(),
      battery,
      is_laptop: battery !== null,
      locked: this.locked,
    };
  }

  async devices() {
    const out = (await runCmd(["nmcli", "-t", "-f", "DEVICE,TYPE,STATE", "device", "status"])) || "";
    return out
      .split("\n")
      .filter(Boolean)
      .map((line) => {
        const parts = line.split(":");
        return { device: parts[0], type: parts[1], state: parts[2] };
      })
      .filter((d) => d.state !== undefined);
  }

  async wifi() {
    const out = await runCmd(["nmcli", "-t", "-f", "ACTIVE,SSID", "dev", "wifi"]);
    if (out === null) return { available: false, connected: false, ssid: null };
    for (const line of out.split("\n")) {
      if (!line) continue;
      const idx = line.indexOf(":");
      const active = idx === -1 ? line : line.slice(0, idx);
      const ssid = idx === -1 ? "" : line.slice(idx + 1);
      if (active === "yes") return { available: true, connected: true, ssid };
    }
    return { available: true, connected: false, ssid: null };
  }

  async connectivityFull() {
    // Plain cached read, not a fresh probe -- must never block a status
    // poll waiting on one, same contract as overlay.py's version.
    const out = await runCmd(["nmcli", "networking", "connectivity"]);
    return out !== null && out.trim().toLowerCase() === "full";
  }

  async network(wifi = null) {
    const devices = await this.devices();
    const ethernetConnected = devices.some(
      (d) => d.type === "ethernet" && d.state === "connected"
    );
    if (wifi === null) wifi = await this.wifi();
    const stable = await this.connectivityFull();

    if (ethernetConnected && stable) {
      return {
        type: "ethernet",
        label: "Ethernet",
        sub: "Connected",
        connected: true,
        wifi_radio_on: wifi.available,
      };
    }
    if (ethernetConnected && !stable) {
      if (wifi.connected) {
        return {
          type: "wifi",
          label: "Wi-Fi",
          sub: wifi.ssid || "Connected",
          connected: true,
          fallback_from_ethernet: true,
          wifi_radio_on: true,
        };
      }
      return {
        type: "ethernet",
        label: "Ethernet",
        sub: "Unstable",
        connected: true,
        unstable: true,
        wifi_radio_on: wifi.available,
      };
    }
    if (wifi.connected) {
      return {
        type: "wifi",
        label: "Wi-Fi",
        sub: wifi.ssid || "Connected",
        connected: true,
        wifi_radio_on: true,
      };
    }
    return {
      type: "none",
      label: "Network",
      sub: "Not Connected",
      connected: false,
      wifi_radio_on: wifi.available,
    };
  }

  async volume() {
    let out = await runCmd(["wpctl", "get-volume", "@DEFAULT_AUDIO_SINK@"]);
    if (out && out.includes("Volume:")) {
      const parts = out.split(/\s+/);
      const level = Math.round(parseFloat(parts[1]) * 100);
      if (!Number.isNaN(level)) {
        return { level, muted: out.includes("MUTED"), available: true };
      }
    }
    out = await runCmd(["pactl", "get-sink-volume", "@DEFAULT_SINK@"]);
    if (out && out.includes("%")) {
      const field = out.split("/")[1];
      const pct = field ? parseInt(field.trim().replace("%", ""), 10) : NaN;
      if (!Number.isNaN(pct)) {
        const mutedOut = (await runCmd(["pactl", "get-sink-mute", "@DEFAULT_SINK@"])) || "";
        return { level: pct, muted: mutedOut.includes("yes"), available: true };
      }
    }
    return { level: 0, muted: true, available: false };
  }

  async brightness() {
    const current = await runCmd(["brightnessctl", "get"]);
    const maximum = await runCmd(["brightnessctl", "max"]);
    if (current && maximum && /^\d+$/.test(maximum) && parseInt(maximum, 10) > 0) {
      return {
        level: Math.round((parseInt(current, 10) / parseInt(maximum, 10)) * 100),
        available: true,
      };
    }
    return { level: 0, available: false };
  }

  async battery() {
    const devices = (await runCmd(["upower", "-e"])) || "";
    const batteryPath = devices.split("\n").find((line) => line.includes("battery"));
    if (!batteryPath) return null;
    const out = await runCmd(["upower", "-i", batteryPath]);
    if (!out) return null;
    let percent = null;
    let state = null;
    for (let line of out.split("\n")) {
      line = line.trim();
      if (line.startsWith("percentage:")) {
        percent = line.split(":")[1].trim().replace("%", "");
      } else if (line.startsWith("state:")) {
        state = line.split(":")[1].trim();
      }
    }
    if (percent && /^\d+$/.test(percent)) {
      return { percent: parseInt(percent, 10), charging: state === "charging" };
    }
    return null;
  }

  // ---- essentials: sliders --------------------------------------------------

  async setVolume(level) {
    level = Math.max(0, Math.min(100, Math.trunc(level)));
    if ((await runCmd(["wpctl", "set-volume", "@DEFAULT_AUDIO_SINK@", `${level}%`])) === null) {
      await runCmd(["pactl", "set-sink-volume", "@DEFAULT_SINK@", `${level}%`]);
    }
    return this.volume();
  }

  async toggleMute() {
    if ((await runCmd(["wpctl", "set-mute", "@DEFAULT_AUDIO_SINK@", "toggle"])) === null) {
      await runCmd(["pactl", "set-sink-mute", "@DEFAULT_SINK@", "toggle"]);
    }
    return this.volume();
  }

  async setBrightness(level) {
    level = Math.max(1, Math.min(100, Math.trunc(level)));
    await runCmd(["brightnessctl", "set", `${level}%`]);
    return this.brightness();
  }

  // ---- essentials: network tile ---------------------------------------------

  async networkScan() {
    const out = await runCmd([
      "nmcli",
      "-t",
      "-f",
      "IN-USE,SSID,SIGNAL,SECURITY",
      "dev",
      "wifi",
      "list",
    ]);
    if (out === null) return [];
    const best = new Map();
    for (const line of out.split("\n")) {
      if (!line) continue;
      const fields = line.split(":");
      if (fields.length < 4) continue;
      const inUse = fields[0];
      const ssid = fields[1];
      if (!ssid) continue;
      let signal = parseInt(fields[2], 10);
      if (Number.isNaN(signal)) signal = 0;
      const security = fields.slice(3).join(":");
      const entry = {
        ssid,
        signal,
        secured: security.trim().length > 0,
        in_use: inUse.trim() === "*",
      };
      const existing = best.get(ssid);
      if (!existing || signal > existing.signal) best.set(ssid, entry);
    }
    return [...best.values()].sort((a, b) => b.signal - a.signal);
  }

  async networkToggleWifiRadio() {
    const wifi = await this.wifi();
    const current = await this.network(wifi);
    const turningOn = !wifi.available || !current.wifi_radio_on;
    await runCmd(["nmcli", "radio", "wifi", turningOn ? "on" : "off"]);
    return this.network();
  }

  async networkConnect(ssid, password = "") {
    const cmd = ["nmcli", "dev", "wifi", "connect", ssid];
    if (password) cmd.push("password", password);
    const out = await runCmd(cmd, 20);
    return { success: out !== null, network: await this.network() };
  }

  // ---- session lifecycle -----------------------------------------------------

  lock() {
    runCmd(["loginctl", "lock-session"]);
    this.locked = true;
    GLib.idle_add(GLib.PRIORITY_DEFAULT, () => {
      if (this.window) {
        updateLayerShell(this.window, {
          layer: GtkLayerShell.Layer.OVERLAY,
          anchors: [
            GtkLayerShell.Edge.TOP,
            GtkLayerShell.Edge.BOTTOM,
            GtkLayerShell.Edge.LEFT,
            GtkLayerShell.Edge.RIGHT,
          ],
          exclusiveZone: 0,
          keyboardMode: GtkLayerShell.KeyboardMode.EXCLUSIVE,
        });
        this.runJs("window.__cgSetLocked && window.__cgSetLocked(true)");
      }
      return GLib.SOURCE_REMOVE;
    });
    return { locked: true };
  }

  unlock() {
    runCmd(["loginctl", "unlock-session"]);
    this.locked = false;
    GLib.idle_add(GLib.PRIORITY_DEFAULT, () => {
      if (this.window) {
        updateLayerShell(this.window, {
          layer: GtkLayerShell.Layer.OVERLAY,
          anchors: [GtkLayerShell.Edge.TOP, GtkLayerShell.Edge.RIGHT],
          exclusiveZone: -1,
          keyboardMode: GtkLayerShell.KeyboardMode.ON_DEMAND,
        });
        this.window.resize(this.width, BAR_HEIGHT);
        this.runJs("window.__cgSetLocked && window.__cgSetLocked(false)");
      }
      return GLib.SOURCE_REMOVE;
    });
    return { locked: false };
  }

  async logout() {
    let sessionId = GLib.getenv("XDG_SESSION_ID");
    if (!sessionId) {
      sessionId = await runCmd(["loginctl", "show-session", "self", "-p", "Id", "--value"]);
    }
    if (sessionId) {
      await runCmd(["loginctl", "terminate-session", sessionId]);
      return null;
    }
    if (this.window) this.window.destroy();
    return null;
  }

  async poweroff() {
    await runCmd(["systemctl", "poweroff"]);
    return null;
  }

  async reboot() {
    await runCmd(["systemctl", "reboot"]);
    return null;
  }
}

const overlay = new Overlay();

// ---------------------------------------------------------------------------
// Bridge dispatch -- the other half of static/bridge.js. Every method name
// app.js's callApi() ever sends (see that file's own method list) must
// have an entry here, matched 1:1 with static/bridge.js's METHODS array.
// ---------------------------------------------------------------------------

const DISPATCH = {
  get_status: () => overlay.getStatus(),
  set_panel: ([panel]) => overlay.setPanel(panel),
  set_volume: ([level]) => overlay.setVolume(level),
  toggle_mute: () => overlay.toggleMute(),
  set_brightness: ([level]) => overlay.setBrightness(level),
  network_scan: () => overlay.networkScan(),
  network_toggle_wifi_radio: () => overlay.networkToggleWifiRadio(),
  network_connect: ([ssid, password]) => overlay.networkConnect(ssid, password),
  lock: () => overlay.lock(),
  unlock: () => overlay.unlock(),
  logout: () => overlay.logout(),
  poweroff: () => overlay.poweroff(),
  reboot: () => overlay.reboot(),
};

function setupBridge(contentManager) {
  contentManager.register_script_message_handler("arktube");
  contentManager.connect("script-message-received::arktube", (_mgr, jsValue) => {
    let payload;
    try {
      payload = JSON.parse(jsValue.to_string());
    } catch (e) {
      logError(`bridge: failed to parse message from page: ${e}`);
      return;
    }
    const { id, method, args } = payload;
    const handler = DISPATCH[method];
    if (!handler) {
      logError(`bridge: unknown method requested from page: ${method}`);
      overlay.runJs(
        `window.__cgReject && window.__cgReject(${id}, ${JSON.stringify("unknown method: " + method)})`
      );
      return;
    }
    Promise.resolve()
      .then(() => handler(args || []))
      .then((result) => {
        const json = JSON.stringify(result === undefined ? null : result);
        overlay.runJs(`window.__cgResolve && window.__cgResolve(${id}, ${JSON.stringify(json)})`);
      })
      .catch((err) => {
        logError(`bridge: '${method}' threw: ${err}`);
        overlay.runJs(
          `window.__cgReject && window.__cgReject(${id}, ${JSON.stringify(String(err))})`
        );
      });
  });
}

// ---------------------------------------------------------------------------
// Window / WebView construction + PID file (so 20-arktube.conf's Menu/
// Power bindsyms can `kill -SIGUSR1/-SIGUSR2` this exact process, same as
// they did for overlay.py).
// ---------------------------------------------------------------------------

function screenWidth(defaultWidth = 1920) {
  try {
    const display = Gdk.Display.get_default();
    const monitor = display && display.get_monitor(0);
    return monitor ? monitor.get_geometry().width : defaultWidth;
  } catch (e) {
    return defaultWidth;
  }
}

function buildWindow() {
  const win = new Gtk.Window({ type: Gtk.WindowType.TOPLEVEL, decorated: false });
  win.set_default_size(overlay.width, BAR_HEIGHT);
  win.set_app_paintable(true);

  // Transparent background so the OSD/power panels render as floating
  // cards, not opaque rectangles -- matches overlay.py's
  // transparent=True/frameless=True.
  const screen = win.get_screen();
  const visual = screen && screen.get_rgba_visual();
  if (visual) win.set_visual(visual);

  const contentManager = new WebKit2.UserContentManager();
  setupBridge(contentManager);

  const webView = new WebKit2.WebView({ user_content_manager: contentManager });
  webView.set_background_color(new Gdk.RGBA({ red: 0, green: 0, blue: 0, alpha: 0 }));

  win.add(webView);

  overlay.window = win;
  overlay.webView = webView;

  const indexPath = GLib.build_filenamev([STATIC, "index.html"]);
  webView.load_uri(GLib.filename_to_uri(indexPath, null));

  return win;
}

function getPid() {
  // /proc/self is a Linux-specific symlink to /proc/<pid> -- fine here
  // since this whole project only ever targets Ubuntu/Linux.
  try {
    return GLib.path_get_basename(GLib.file_read_link("/proc/self"));
  } catch (e) {
    return null;
  }
}

function writePidFile(pid) {
  try {
    GLib.file_set_contents(PID_PATH, String(pid));
  } catch (e) {
    logError(
      `failed to write PID file (${PID_PATH}) -- 20-arktube.conf's ` +
        `bindsyms won't be able to signal this process. (${e})`
    );
  }
}

function removePidFile(pid) {
  try {
    const [ok, contents] = GLib.file_get_contents(PID_PATH);
    if (ok) {
      const text = new TextDecoder().decode(contents).trim();
      if (text === String(pid)) {
        Gio.File.new_for_path(PID_PATH).delete(null);
      }
    }
  } catch (e) {
    // Matches overlay.py's own silent pass here -- a missing/unreadable
    // PID file at shutdown isn't worth failing over.
  }
}

function main() {
  overlay.width = screenWidth();
  const win = buildWindow();

  // gtk-layer-shell's contract is that init_for_window() runs before the
  // window is shown -- unlike overlay.py, which had to hook pywebview's
  // before_show event to find that moment, this file builds its own
  // Gtk.Window directly, so it's simplest to just call this right here,
  // before show_all() below.
  initLayerShell(win, {
    layer: GtkLayerShell.Layer.OVERLAY,
    anchors: [GtkLayerShell.Edge.TOP, GtkLayerShell.Edge.RIGHT],
    exclusiveZone: -1,
    keyboardMode: GtkLayerShell.KeyboardMode.ON_DEMAND,
  });

  win.connect("destroy", () => Gtk.main_quit());
  win.show_all();

  // Menu/Power remote buttons -> Sway bindsyms send SIGUSR1/SIGUSR2 to
  // this exact PID (read from overlay.pid -- see 20-arktube.conf).
  // GLib.unix_signal_add dispatches directly on the GTK main loop, no
  // extra marshalling needed the way Python's `signal` module required.
  // 10 and 12 are the standard Linux signal numbers for SIGUSR1/SIGUSR2.
  const SIGUSR1 = 10;
  const SIGUSR2 = 12;
  GLib.unix_signal_add(GLib.PRIORITY_DEFAULT, SIGUSR1, () => {
    overlay.setPanel("overlay");
    return GLib.SOURCE_CONTINUE;
  });
  GLib.unix_signal_add(GLib.PRIORITY_DEFAULT, SIGUSR2, () => {
    overlay.setPanel("power");
    return GLib.SOURCE_CONTINUE;
  });

  Gtk.main();
}

logInfo(`overlay.js starting (log file: ${LOG_PATH})`);
const pid = getPid();
if (pid) writePidFile(pid);
try {
  main();
} catch (e) {
  logError(
    `overlay.js crashed during startup -- this is almost certainly why ` +
      `the overlay/OSD/menu never appeared. ${e}\n${e.stack || ""}`
  );
  throw e;
} finally {
  if (pid) removePidFile(pid);
}
