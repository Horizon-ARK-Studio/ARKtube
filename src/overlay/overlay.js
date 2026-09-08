#!/usr/bin/env -S gjs -m
//
// overlay.js — Astal-based replacement for the hand-rolled GTK3 +
// gtk-layer-shell + shelled-out-nmcli/wpctl/upower version of this file.
//
// The window/WebView/bridge architecture is unchanged: a GTK3 window
// hosting a WebKit2 WebView that loads static/index.html/style.css/app.js
// unmodified, talking back to this process over
// WebKitUserContentManager's script-message channel (static/bridge.js).
// What changed is everything *around* that WebView:
//
//   * The window is now an `Astal.Window` (from libastal's GTK3 widget
//     library, GI namespace `Astal` 3.0) instead of a plain `Gtk.Window`
//     manually promoted to a layer surface with hand-called
//     `GtkLayerShell.set_anchor/set_layer/set_exclusive_zone/...`.
//     `Astal.Window` exposes exactly those same wlr-layer-shell-v1
//     concepts (anchor, layer, exclusivity, keymode) as plain GObject
//     properties, set at construction and reassignable afterwards --
//     `updateLayerShell()` below is now a handful of property writes
//     instead of five separate GtkLayerShell.* calls per edge.
//   * Volume/mute now comes from `AstalWp` (a wrapper over wireplumber),
//     not `wpctl`/`pactl` shelled out and text-parsed with a two-way
//     fallback between the two.
//   * Network status (and the wifi radio toggle) now comes from
//     `AstalNetwork`, not `nmcli` invoked and grep/cut-style parsed on a
//     poll.
//   * Battery state now comes from `AstalBattery` (a thin wrapper over
//     upowerd), not `upower -e` / `upower -i` shelled out and parsed
//     line by line.
//
// What deliberately did NOT move to Astal, because no Astal library
// covers it:
//   * Brightness (`brightnessctl`) -- there is no `AstalBacklight`.
//   * Session lifecycle (`loginctl`, `systemctl`) -- Astal has no
//     logind/systemd wrapper; these stay exactly as shelled-out calls.
//   * Actually *connecting* to a WPA-secured wifi network with a
//     password (`nmcli dev wifi connect <ssid> password <pw>`) --
//     `AstalNetwork.AccessPoint.activate()` exists, but its documented
//     behavior ("creates a new SimpleConnection using wpa-psk and
//     activates it") doesn't describe how a password is supplied, and
//     guessing at a secrets-handling API is worse than just keeping the
//     one nmcli call this repo already knew worked. Scanning/reading the
//     resulting network list DID move to AstalNetwork, since that's pure
//     state, not a secret.
// `runCmd()` (Gio.Subprocess-based) survives for exactly those three
// things, plus the still-necessary network_connect nmcli call.
//
// osd.c and power-menu.c (this directory's siblings) are untouched --
// they're already small, standalone GTK3 + gtk-layer-shell C programs
// with nothing this refactor concerns itself with.

import GLib from "gi://GLib";
import Gio from "gi://Gio";
import Gtk from "gi://Gtk?version=3.0";
import Gdk from "gi://Gdk?version=3.0";

// ---------------------------------------------------------------------------
// Paths + logging. Sway launches this via `exec`, which gives no terminal
// to see stdout/stderr on. Everything of interest goes to a real file as
// well as stderr, and logging is set up before the risky imports below
// run, so a missing typelib logs a specific, readable reason instead of
// GJS just exiting silently.
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
  // Falls back to stderr-only below.
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
// PID file -- written here, BEFORE the risky imports below, not at the
// bottom of the file. When any of WebKit2/Astal/AstalWp/AstalNetwork/
// AstalBattery fail to import (missing typelib -- see the try/catches
// right below), the process throws and exits during module load, which
// is *before* a later writePidFile() call would ever run.
// overlay-watchdog.sh then restarts overlay.js in a crash loop, and
// 20-arktube.conf's $mod+m / Menu bindsyms keep signaling whatever PID
// happened to be left over from the last time overlay.js actually got
// far enough to write one, instead of anything alive. Writing the PID
// immediately, before anything that can throw, means the file always
// reflects the current process, crash-looping or not.
// ---------------------------------------------------------------------------

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
    // A missing/unreadable PID file at shutdown isn't worth failing over.
  }
}

const earlyPid = getPid();
if (earlyPid) writePidFile(earlyPid);

// ---------------------------------------------------------------------------
// Risky imports, guarded individually and loaded dynamically (rather than
// as static `import` statements) so a missing typelib logs a clear
// diagnostic here instead of GJS refusing to even start the module.
//
// Astal, AstalWp, AstalNetwork, AstalBattery are new in this refactor.
// WebKit2 is unchanged. GtkLayerShell is GONE -- Astal.Window wraps it
// internally, so this file no longer talks to gtk-layer-shell directly.
// ---------------------------------------------------------------------------

let WebKit2, Astal, AstalWp, AstalNetwork, AstalBattery;

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
  ({ default: Astal } = await import("gi://Astal?version=3.0"));
} catch (e) {
  logError(
    `failed to import Astal 3.0 -- this is the single most likely ` +
      `startup failure now that this file is Astal-based. It means ` +
      `libastal's GTK3 widget library (and its typelib) isn't built/ ` +
      `installed. Without this, the corner menu/power panel/network ` +
      `tile/OSD have no process behind them at all -- volume/brightness ` +
      `still work because those bypass this file entirely via direct ` +
      `Sway bindsyms. (${e})`
  );
  throw e;
}

// AstalWp/AstalNetwork/AstalBattery are each optional in isolation --
// losing one degrades exactly the feature it backs (volume, network,
// battery respectively) rather than the whole overlay, so these are
// logged as warnings, not thrown, and the affected Overlay methods below
// fall back to "unavailable" results the same way runCmd()'s callers
// already handle a missing binary.

try {
  ({ default: AstalWp } = await import("gi://AstalWp?version=0.1"));
} catch (e) {
  logWarn(`failed to import AstalWp 0.1 -- volume/mute will report unavailable. (${e})`);
  AstalWp = null;
}

try {
  ({ default: AstalNetwork } = await import("gi://AstalNetwork?version=0.1"));
} catch (e) {
  logWarn(`failed to import AstalNetwork 0.1 -- the network tile will report unavailable. (${e})`);
  AstalNetwork = null;
}

try {
  ({ default: AstalBattery } = await import("gi://AstalBattery?version=0.1"));
} catch (e) {
  logWarn(`failed to import AstalBattery 0.1 -- battery status will report absent (treated as desktop, not laptop). (${e})`);
  AstalBattery = null;
}

logInfo("all imports succeeded, continuing startup");

Gio._promisify(
  Gio.Subprocess.prototype,
  "communicate_utf8_async",
  "communicate_utf8_finish"
);

// ---------------------------------------------------------------------------
// Panel geometry -- same four states as before: the collapsed top-right
// corner bar ('none'), the full settings panel ('overlay'), the centered
// power menu ('power'), and the centered volume/brightness toast ('osd').
// `Astal.WindowAnchor` is a flags enum (bitwise-OR combinable, same as
// GtkLayerShell.Edge was used as an array of edges before); leaving an
// axis unanchored on both edges is still what centers a surface on that
// axis, so 'power' still ends up dead-center and 'osd' horizontally
// centered near the top without either needing the real screen width.
// ---------------------------------------------------------------------------

const BAR_HEIGHT = 56;
const PANEL_HEIGHT = 620;
const POWER_MENU_WIDTH = 420;
const POWER_MENU_HEIGHT = 300;
const OSD_WIDTH = 320;
const OSD_HEIGHT = 210;

function panelGeometry(name, width) {
  const { TOP, RIGHT } = Astal.WindowAnchor;
  switch (name) {
    case "overlay":
      return {
        size: [width, PANEL_HEIGHT],
        anchor: TOP | RIGHT,
        // EXCLUSIVE, not the default ON_DEMAND -- becoming visible
        // doesn't grant a Wayland surface keyboard focus by itself;
        // something has to claim it, same as lock()/unlock() below.
        keymode: Astal.Keymode.EXCLUSIVE,
      };
    case "power":
      return {
        size: [POWER_MENU_WIDTH, POWER_MENU_HEIGHT],
        anchor: 0, // no edges anchored on either axis -> centered
        keymode: Astal.Keymode.EXCLUSIVE,
      };
    case "osd":
      return {
        size: [OSD_WIDTH, OSD_HEIGHT],
        anchor: TOP,
        // No keyboard interaction happens on the toast, so this stays
        // ON_DEMAND rather than stealing focus from ARKtube mid-playback.
        keymode: Astal.Keymode.ON_DEMAND,
      };
    case "none":
    default:
      return {
        size: [width, BAR_HEIGHT],
        anchor: TOP | RIGHT,
        keymode: Astal.Keymode.ON_DEMAND,
      };
  }
}

// Every panel state here uses IGNORE (never reserve screen space for
// itself -- ARKtube's WebView underneath should never be pushed around
// by the corner bar or an open panel). The old code's exclusiveZone was
// always -1 or 0 for exactly the same reason; neither value ever asked
// for reserved space, so both collapse onto Astal.Exclusivity.IGNORE.
function updateLayerShell(astalWindow, { anchor, keymode }) {
  astalWindow.layer = Astal.Layer.OVERLAY;
  astalWindow.exclusivity = Astal.Exclusivity.IGNORE;
  astalWindow.anchor = anchor;
  astalWindow.keymode = keymode;
  astalWindow.margin_top = 0;
  astalWindow.margin_right = 0;
  astalWindow.margin_bottom = 0;
  astalWindow.margin_left = 0;
}

// ---------------------------------------------------------------------------
// Shell-out helper. Every caller treats `null` as "control unavailable"
// and degrades the UI rather than throwing. Still needed for brightness
// (brightnessctl), session lifecycle (loginctl/systemctl), and the one
// nmcli connect call -- see this file's header for why those didn't move
// to Astal. Built on Gio.Subprocess (async, non-blocking) rather than a
// synchronous call, since this runs on the same GTK main loop thread
// that also has to keep the WebView responsive.
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

function sleep(ms) {
  return new Promise((resolve) => {
    GLib.timeout_add(GLib.PRIORITY_DEFAULT, ms, () => {
      resolve();
      return GLib.SOURCE_REMOVE;
    });
  });
}

// ---------------------------------------------------------------------------
// Overlay state + the same set of operations the bridge exposes to
// app.js, just as plain (mostly async) methods. The bridge dispatch
// table further down is what actually connects these to
// static/bridge.js's calls.
// ---------------------------------------------------------------------------

class Overlay {
  constructor() {
    this.window = null; // Astal.Window (a Gtk.Window subclass)
    this.webView = null; // WebKit2.WebView
    this.width = 1920; // overwritten in main() from the real screen
    this.locked = false;
    this.pageReady = false; // set once WebKit2 fires LoadEvent.FINISHED
    this.pendingJs = []; // scripts queued while the page isn't ready yet
  }

  // Called once from buildWindow() when the WebView finishes loading
  // index.html. Flushes anything that was queued by runJs() below.
  onPageReady() {
    this.pageReady = true;
    const queued = this.pendingJs;
    this.pendingJs = [];
    for (const script of queued) this._execJs(script);
  }

  runJs(script) {
    if (!this.webView) return;
    if (!this.pageReady) {
      // A SIGUSR1 (or any bridge call) that lands while WebKit2 is still
      // loading index.html/app.js would otherwise silently lose its
      // `run_javascript` call. Queueing instead of dropping means it's
      // delivered as soon as the page actually finishes loading.
      this.pendingJs.push(script);
      return;
    }
    this._execJs(script);
  }

  _execJs(script) {
    this.webView.run_javascript(script, null, (webView, result) => {
      try {
        webView.run_javascript_finish(result);
      } catch (e) {
        logWarn(`runJs: script threw or was rejected: ${e}`);
      }
    });
  }

  // ---- panel state --------------------------------------------------------

  async setPanel(panel) {
    if (this.locked) {
      // Self-heal against a stuck `this.locked`: cross-check against the
      // real session lock state (loginctl) rather than trusting our own
      // flag blindly, and clear it if they disagree instead of staying
      // wedged with every future panel request silently no-op'ing.
      const hint = await runCmd(["loginctl", "show-session", "self", "-p", "LockedHint", "--value"]);
      if (hint !== null && hint.trim().toLowerCase() !== "yes") {
        logWarn(
          "setPanel: this.locked was true but loginctl reports the " +
            "session is not actually locked -- clearing the stale flag " +
            "instead of continuing to no-op every panel request."
        );
        this.locked = false;
      } else {
        return "locked";
      }
    }
    const geometry = panelGeometry(panel, this.width);
    GLib.idle_add(GLib.PRIORITY_DEFAULT, () => {
      if (this.window) {
        const [width, height] = geometry.size;
        updateLayerShell(this.window, geometry);
        this.window.resize(width, height);
        this.runJs(`window.__cgSetPanel && window.__cgSetPanel(${JSON.stringify(panel)})`);
      } else {
        logWarn(`setPanel(${panel}): this.window is not set yet -- dropping this request.`);
      }
      return GLib.SOURCE_REMOVE;
    });
    return panel;
  }

  // ---- status polling -------------------------------------------------------

  async getStatus() {
    const battery = this.battery();
    return {
      network: this.network(),
      volume: this.volume(),
      brightness: await this.brightness(),
      battery,
      is_laptop: battery !== null,
      locked: this.locked,
    };
  }

  // ---- volume (AstalWp) ------------------------------------------------------
  //
  // Talks to wireplumber directly through libastal's own binding, rather
  // than shelling out to `wpctl`/`pactl` and text-parsing the result --
  // this also removes the old two-way wpctl-then-pactl fallback, since
  // AstalWp is the one thing being asked, not a command that might not
  // be on PATH.

  _speaker() {
    if (!AstalWp) return null;
    try {
      return AstalWp.get_default()?.audio?.default_speaker || null;
    } catch (e) {
      return null;
    }
  }

  volume() {
    const speaker = this._speaker();
    if (!speaker) return { level: 0, muted: true, available: false };
    return {
      level: Math.round(speaker.volume * 100),
      muted: speaker.mute,
      available: true,
    };
  }

  async setVolume(level) {
    const speaker = this._speaker();
    if (speaker) {
      speaker.volume = Math.max(0, Math.min(100, Math.trunc(level))) / 100;
    }
    return this.volume();
  }

  async toggleMute() {
    const speaker = this._speaker();
    if (speaker) speaker.mute = !speaker.mute;
    return this.volume();
  }

  // ---- brightness (still shells out -- no AstalBacklight exists) ------------

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

  async setBrightness(level) {
    level = Math.max(1, Math.min(100, Math.trunc(level)));
    await runCmd(["brightnessctl", "set", `${level}%`]);
    return this.brightness();
  }

  // ---- network (AstalNetwork) -------------------------------------------------
  //
  // AstalNetwork's `Internet` enum (CONNECTED / CONNECTING / DISCONNECTED)
  // is itself derived from NetworkManager's own connectivity checking, so
  // the old code's manual "ethernet says connected but is it actually
  // stable" probe (`nmcli networking connectivity`) isn't needed here --
  // asking `wired.internet`/`wifi.internet` already answers that.

  network() {
    if (!AstalNetwork) {
      return { type: "none", label: "Network", sub: "Not Connected", connected: false, wifi_radio_on: false };
    }
    const net = AstalNetwork.get_default();
    const wired = net?.wired;
    const wifi = net?.wifi;
    const CONNECTED = AstalNetwork.Internet.CONNECTED;
    const wifiRadioOn = wifi ? wifi.enabled : false;

    if (wired && wired.internet === CONNECTED) {
      return {
        type: "ethernet",
        label: "Ethernet",
        sub: "Connected",
        connected: true,
        wifi_radio_on: wifiRadioOn,
      };
    }
    if (wifi && wifi.internet === CONNECTED) {
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
      wifi_radio_on: wifiRadioOn,
    };
  }

  async networkScan() {
    if (!AstalNetwork) return [];
    const wifi = AstalNetwork.get_default()?.wifi;
    if (!wifi) return [];
    try {
      wifi.scan();
    } catch (e) {
      // Scan is fire-and-forget on top of NetworkManager; a failure here
      // just means we fall back to whatever access_points already holds.
    }
    // scan() kicks off an async NetworkManager scan and updates
    // access_points via a property change signal rather than returning
    // the fresh list directly -- give it a moment, then read what's
    // cached, mirroring nmcli's own "list what's currently known"
    // semantics rather than blocking indefinitely on a scan finishing.
    await sleep(1500);
    const active = wifi.active_access_point;
    const best = new Map();
    for (const ap of wifi.access_points || []) {
      if (!ap.ssid) continue;
      const existing = best.get(ap.ssid);
      if (!existing || ap.strength > existing.signal) {
        best.set(ap.ssid, {
          ssid: ap.ssid,
          signal: ap.strength,
          secured: Boolean(ap.wpa_flags || ap.rsn_flags),
          in_use: active ? active.ssid === ap.ssid : false,
        });
      }
    }
    return [...best.values()].sort((a, b) => b.signal - a.signal);
  }

  async networkToggleWifiRadio() {
    if (!AstalNetwork) return this.network();
    const wifi = AstalNetwork.get_default()?.wifi;
    if (wifi) wifi.enabled = !wifi.enabled;
    return this.network();
  }

  // Deliberately still nmcli, not AstalNetwork.AccessPoint.activate() --
  // see this file's header comment for why: connecting with a *password*
  // isn't something the Astal API documents clearly enough to trust.
  async networkConnect(ssid, password = "") {
    const cmd = ["nmcli", "dev", "wifi", "connect", ssid];
    if (password) cmd.push("password", password);
    const out = await runCmd(cmd, 20);
    return { success: out !== null, network: this.network() };
  }

  // ---- battery (AstalBattery) -------------------------------------------------

  battery() {
    if (!AstalBattery) return null;
    let device;
    try {
      device = AstalBattery.get_default();
    } catch (e) {
      return null;
    }
    if (!device || !device.is_present) return null;
    // UPower (and therefore AstalBattery, a thin wrapper over upowerd)
    // reports `percentage` already on a 0-100 scale, not 0-1.
    const state = device.state;
    const CHARGING = AstalBattery.State?.CHARGING;
    return {
      percent: Math.round(device.percentage),
      charging: CHARGING !== undefined ? state === CHARGING : false,
    };
  }

  // ---- session lifecycle (still shells out -- no Astal logind wrapper) ------

  lock() {
    runCmd(["loginctl", "lock-session"]);
    this.locked = true;
    GLib.idle_add(GLib.PRIORITY_DEFAULT, () => {
      if (this.window) {
        updateLayerShell(this.window, {
          anchor:
            Astal.WindowAnchor.TOP |
            Astal.WindowAnchor.BOTTOM |
            Astal.WindowAnchor.LEFT |
            Astal.WindowAnchor.RIGHT,
          keymode: Astal.Keymode.EXCLUSIVE,
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
          anchor: Astal.WindowAnchor.TOP | Astal.WindowAnchor.RIGHT,
          keymode: Astal.Keymode.ON_DEMAND,
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
// Unchanged by this refactor: static/bridge.js and static/app.js don't
// know or care whether volume/network/battery come from wpctl/nmcli/
// upower or from Astal -- that's exactly the point of the bridge.
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
// Power bindsyms can `kill -SIGUSR1/-SIGUSR2` this exact process).
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

// Builds the collapsed top-right corner bar's Astal.Window directly --
// unlike the old code, there's no separate "build a plain Gtk.Window,
// then call GtkLayerShell.init_for_window() on it before showing" step.
// Astal.Window IS the layer surface; its anchor/layer/exclusivity/
// keymode properties are set at construction time below, the same
// values panelGeometry("none", ...) would return.
function buildWindow() {
  const win = new Astal.Window({
    namespace: "arktube-overlay",
    layer: Astal.Layer.OVERLAY,
    anchor: Astal.WindowAnchor.TOP | Astal.WindowAnchor.RIGHT,
    exclusivity: Astal.Exclusivity.IGNORE,
    keymode: Astal.Keymode.ON_DEMAND,
    decorated: false,
    default_width: overlay.width,
    default_height: BAR_HEIGHT,
  });
  win.set_app_paintable(true);

  // Transparent background so the OSD/power panels render as floating
  // cards, not opaque rectangles.
  const screen = win.get_screen();
  const visual = screen && screen.get_rgba_visual();
  if (visual) win.set_visual(visual);

  const contentManager = new WebKit2.UserContentManager();
  setupBridge(contentManager);

  const webView = new WebKit2.WebView({ user_content_manager: contentManager });
  webView.set_background_color(new Gdk.RGBA({ red: 0, green: 0, blue: 0, alpha: 0 }));

  // Flush any runJs() calls (e.g. from a SIGUSR1 that arrived while the
  // page was still loading) once index.html/app.js has actually
  // finished loading -- see Overlay.runJs()'s own comment for the race
  // this closes.
  webView.connect("load-changed", (_wv, loadEvent) => {
    if (loadEvent === WebKit2.LoadEvent.FINISHED) {
      overlay.onPageReady();
    }
  });

  win.add(webView);

  overlay.window = win;
  overlay.webView = webView;

  const indexPath = GLib.build_filenamev([STATIC, "index.html"]);
  webView.load_uri(GLib.filename_to_uri(indexPath, null));

  return win;
}

function main() {
  overlay.width = screenWidth();

  // Wrapped in try/catch: if the compositor doesn't actually advertise
  // wlr-layer-shell-v1, Astal.Window's construction (or realization) can
  // throw the same way GtkLayerShell.init_for_window() used to. Falling
  // back to a plain Gtk.Window here is degraded -- no anchoring, no
  // keyboard-mode control, it'll behave like an ordinary window -- but
  // it keeps the process, and the SIGUSR1/SIGUSR2 handlers below, alive
  // instead of crash-looping forever on an environment issue a restart
  // can't fix anyway.
  let win;
  try {
    win = buildWindow();
  } catch (e) {
    logError(
      `Astal.Window construction failed -- continuing with a plain ` +
        `(non-layer-shell) window instead of crashing, so the overlay ` +
        `process and its SIGUSR1/SIGUSR2 handlers stay alive. The panel ` +
        `will not be anchored/positioned correctly until this is fixed ` +
        `(is the compositor actually Sway/wlroots, and does it support ` +
        `wlr-layer-shell-v1?). (${e})`
    );
    win = new Gtk.Window({ type: Gtk.WindowType.TOPLEVEL, decorated: false });
    win.set_default_size(overlay.width, BAR_HEIGHT);
    overlay.window = win;
  }

  win.connect("destroy", () => Gtk.main_quit());
  win.show_all();

  // Menu/Power remote buttons -> Sway bindsyms send SIGUSR1/SIGUSR2 to
  // this exact PID (read from overlay.pid -- see 20-arktube.conf).
  // GLib.unix_signal_add dispatches directly on the GTK main loop.
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

logInfo(`overlay.js starting (log file: ${LOG_PATH}, pid: ${earlyPid})`);
try {
  main();
} catch (e) {
  logError(
    `overlay.js crashed during startup -- this is almost certainly why ` +
      `the overlay/OSD/menu never appeared. ${e}\n${e.stack || ""}`
  );
  throw e;
} finally {
  if (earlyPid) removePidFile(earlyPid);
}
