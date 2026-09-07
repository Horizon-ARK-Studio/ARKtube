// session/overlay/static/app.js
//
// Frontend for Stage 8's TV-style system overlay. Talks to overlay.py
// only through `pywebview.api.*` — no network calls, ever (see
// overlay.py's own module docstring for why). Navigation is written to
// be usable from a keyboard (arrow keys / Enter / Escape), since that's
// the closest stand-in this environment has for a TV remote / game
// controller D-pad — the same input class ARKtube's own app-init.js
// already targets.

(() => {
  "use strict";

  const STATUS_POLL_MS = 2000;
  const SLIDER_DEBOUNCE_MS = 90;

  const launcher = document.getElementById("launcher");
  const scrim = document.getElementById("scrim");
  const panel = document.getElementById("panel");

  const launcherNetworkIcon = document.getElementById("launcher-network-icon");
  const launcherVolumeIcon = document.getElementById("launcher-volume-icon");
  const launcherBattery = document.getElementById("launcher-battery");
  const launcherBatteryPct = document.getElementById("launcher-battery-pct");

  const panelBattery = document.getElementById("panel-battery");
  const panelBatteryPct = document.getElementById("panel-battery-pct");

  const powerToggle = document.getElementById("power-toggle");
  const powerMenu = document.getElementById("power-menu");

  const tileRow = document.getElementById("tile-row");
  const tiles = Array.from(tileRow.querySelectorAll(".tile"));

  const contentNetwork = document.getElementById("content-network");
  const contentPlaceholder = document.getElementById("content-placeholder");
  const placeholderIcon = document.getElementById("placeholder-icon");
  const placeholderTitle = document.getElementById("placeholder-title");

  const networkStatusIcon = document.getElementById("network-status-icon");
  const networkStatusLabel = document.getElementById("network-status-label");
  const networkStatusSub = document.getElementById("network-status-sub");
  const wifiRadioToggle = document.getElementById("wifi-radio-toggle");
  const networkList = document.getElementById("network-list");

  const wifiConnectForm = document.getElementById("wifi-connect-form");
  const wifiConnectSsid = document.getElementById("wifi-connect-ssid");
  const wifiConnectPassword = document.getElementById("wifi-connect-password");
  const wifiConnectCancel = document.getElementById("wifi-connect-cancel");
  const wifiConnectStatus = document.getElementById("wifi-connect-status");

  const brightnessSlider = document.getElementById("brightness-slider");
  const brightnessValue = document.getElementById("brightness-value");
  const volumeSlider = document.getElementById("volume-slider");
  const volumeValue = document.getElementById("volume-value");
  const volumeIcon = document.getElementById("volume-icon");
  const volumeMuteBtn = document.getElementById("volume-mute");

  const contentPane = document.getElementById("content-pane");
  const sliderDock = document.querySelector(".slider-dock");

  const lockScreen = document.getElementById("lock-screen");
  const lockUnlockBtn = document.getElementById("lock-unlock");

  const powerPanel = document.getElementById("power-panel");
  const powerPanelButtons = Array.from(powerPanel.querySelectorAll(".power-panel-btn"));

  const osd = document.getElementById("osd");
  const osdIcon = document.getElementById("osd-icon");
  const osdValue = document.getElementById("osd-value");
  const osdFill = document.getElementById("osd-fill");

  // Placeholder copy per tile — see PLACEHOLDER_TILES in overlay.py.
  // Kept in one small table rather than three near-duplicate DOM
  // sections; adding a real backend for one of these later means
  // deleting its row here and giving it a real <section>, same as
  // "network" already has.
  const PLACEHOLDERS = {
    picture: { icon: "picture", title: "Picture settings" },
    sound: { icon: "sound", title: "Sound settings" },
    bluetooth: { icon: "bluetooth", title: "Bluetooth settings" },
  };

  let panelOpen = false;
  let activeTile = "network";
  let lastNetworkType = "ethernet";
  let pendingConnectSsid = null;
  let debounceTimers = {};
  let locked = false;

  // ---- panel open/close ---------------------------------------------------
  //
  // openPanel()/closePanel() used to reach into the DOM directly
  // (un-hiding/hiding #scrim and #panel themselves) around an awaited
  // call to Python's set_panel(). That had a real, previously-fixed
  // race: revealing the panel's CSS before the awaited resize actually
  // landed meant #panel's max-height (calc(100% - 48px)) resolved
  // against the window's still-56px-tall BAR_HEIGHT for however long
  // the resize took, clipping hard.
  //
  // Since overlay.py's set_panel() was generalized to also drive
  // 'power' and 'osd' (docs/planning/REMOTE-INPUT-MAPPING.md), it now
  // pushes the resulting panel name back into __cgSetPanel() below
  // right after applying the resize -- see that function's own
  // comment for why that's the *only* place any of these four
  // states' DOM visibility changes now. openPanel()/closePanel() are
  // reduced to just asking for the state change and letting that
  // push (which necessarily arrives after the resize, since Python
  // does both in the same GTK-thread callback) do the reveal --
  // structurally the same fix as before, just with one code path
  // instead of two.
  async function openPanel() {
    if (panelOpen || locked) return;
    await callApi("set_panel", "overlay");
  }

  function closePanel() {
    if (!panelOpen) return;
    callApi("set_panel", "none");
  }

  // Pushed from overlay.py's SystemAPI.set_panel() after every
  // geometry change it applies -- whether that change came from a
  // click here (openPanel/closePanel above) or from the remote's
  // Menu/Power button firing a Sway bindsym straight into overlay.py's
  // own SIGUSR1/SIGUSR2 handlers, bypassing JS entirely until this
  // push. DOM-only: this must never call back into set_panel/callApi
  // itself, or a click-triggered change would loop forever between
  // here and Python.
  window.__cgSetPanel = function (panelState) {
    if (locked) return; // lock screen owns the DOM until unlock()
    clearTimeout(osdHideTimer);
    osd.classList.add("hidden");

    if (panelState === "power") {
      scrim.classList.add("hidden");
      panel.classList.add("hidden");
      powerMenu.classList.add("hidden");
      hideConnectForm();
      panelOpen = false;
      powerPanel.classList.remove("hidden");
      requestAnimationFrame(() => powerPanelButtons[0] && powerPanelButtons[0].focus());
      return;
    }

    powerPanel.classList.add("hidden");

    if (panelState === "overlay") {
      if (panelOpen) return; // already showing; e.g. a redundant push
      panelOpen = true;
      scrim.classList.remove("hidden");
      panel.classList.remove("hidden");
      refreshStatus();
      if (activeTile === "network") refreshNetworkList();
      requestAnimationFrame(() => tiles[0] && tiles[0].focus());
      return;
    }

    // Anything else (explicitly 'none', or 'osd' -- which manages its
    // own #osd element directly in showOsd()/hideOsd() rather than
    // through this push) collapses back to the corner bar.
    if (panelOpen) {
      panelOpen = false;
      scrim.classList.add("hidden");
      panel.classList.add("hidden");
      powerMenu.classList.add("hidden");
      hideConnectForm();
      launcher.focus();
    }
  };

  launcher.addEventListener("click", openPanel);
  launcher.addEventListener("keydown", (e) => {
    if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      openPanel();
    }
  });
  scrim.addEventListener("click", closePanel);

  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && panelOpen) {
      e.preventDefault();
      closePanel();
    }
  });

  // ---- tile navigation ------------------------------------------------------

  function setActiveTile(name) {
    activeTile = name;
    tiles.forEach((t) => {
      const isActive = t.dataset.tile === name;
      t.classList.toggle("active", isActive);
      t.setAttribute("aria-selected", isActive ? "true" : "false");
      t.tabIndex = isActive ? 0 : -1;
    });

    if (name === "network") {
      contentNetwork.classList.remove("hidden");
      contentPlaceholder.classList.add("hidden");
      refreshNetworkList();
    } else {
      contentNetwork.classList.add("hidden");
      contentPlaceholder.classList.remove("hidden");
      const info = PLACEHOLDERS[name];
      if (info) {
        placeholderIcon.setAttribute("data-icon", info.icon);
        placeholderTitle.textContent = info.title;
      }
    }
  }

  tiles.forEach((tile) => {
    tile.addEventListener("click", () => setActiveTile(tile.dataset.tile));
  });

  tileRow.addEventListener("keydown", (e) => {
    const currentIndex = tiles.findIndex((t) => t.dataset.tile === activeTile);
    if (e.key === "ArrowRight") {
      e.preventDefault();
      const next = tiles[(currentIndex + 1) % tiles.length];
      setActiveTile(next.dataset.tile);
      next.focus();
    } else if (e.key === "ArrowLeft") {
      e.preventDefault();
      const prev = tiles[(currentIndex - 1 + tiles.length) % tiles.length];
      setActiveTile(prev.dataset.tile);
      prev.focus();
    } else if (e.key === "ArrowDown") {
      e.preventDefault();
      const focusable = panel.querySelector(
        ".content:not(.hidden) button, .content:not(.hidden) input, .slider-dock input"
      );
      if (focusable) focusable.focus();
    }
  });

  // Reverse of ArrowDown above. Previously there was no scripted way
  // back up to the tile row from the content pane or the slider dock
  // (see the bug report, item 4) -- a remote/controller user who
  // pressed Down had only Escape (closes the whole panel) to get back,
  // since app-init.js's gamepad poller never emits Tab. This restores
  // Up as the mirror of Down: from anywhere inside the content pane or
  // the always-visible slider dock, it returns focus to the
  // currently-active tile, one level, not the whole panel.
  panel.addEventListener("keydown", (e) => {
    if (e.key !== "ArrowUp") return;
    const active = document.activeElement;
    const withinContentOrSliders =
      (contentPane && contentPane.contains(active)) ||
      (sliderDock && sliderDock.contains(active));
    if (!withinContentOrSliders) return;
    e.preventDefault();
    const activeTileEl = tiles.find((t) => t.dataset.tile === activeTile);
    if (activeTileEl) activeTileEl.focus();
  });

  // ---- power menu -----------------------------------------------------------

  powerToggle.addEventListener("click", () => {
    powerMenu.classList.toggle("hidden");
  });

  // Previously only Lock/Log Out/Restart/Power Off or the whole panel's
  // own Escape handler closed this -- clicking a tile or a slider while
  // it was open just left it floating over the content pane (bug
  // report item 6). Mirrors #scrim's existing click-to-close for the
  // panel itself.
  document.addEventListener("click", (e) => {
    if (powerMenu.classList.contains("hidden")) return;
    if (powerMenu.contains(e.target) || powerToggle.contains(e.target)) return;
    powerMenu.classList.add("hidden");
  });
  document.getElementById("power-lock").addEventListener("click", () => {
    callApi("lock");
    powerMenu.classList.add("hidden");
  });
  document.getElementById("power-logout").addEventListener("click", () => {
    callApi("logout");
  });
  document.getElementById("power-reboot").addEventListener("click", () => {
    callApi("reboot");
  });
  document.getElementById("power-poweroff").addEventListener("click", () => {
    callApi("poweroff");
  });

  // ---- centered power menu (remote Power button, or reached from the
  // corner dropdown above) --------------------------------------------------

  document.getElementById("power-panel-shutdown").addEventListener("click", () => {
    callApi("poweroff");
  });
  document.getElementById("power-panel-restart").addEventListener("click", () => {
    callApi("reboot");
  });
  document.getElementById("power-panel-logout").addEventListener("click", () => {
    callApi("logout");
  });

  powerPanel.addEventListener("keydown", (e) => {
    const idx = powerPanelButtons.indexOf(document.activeElement);
    if (e.key === "ArrowDown" || e.key === "ArrowRight") {
      e.preventDefault();
      powerPanelButtons[(idx + 1 + powerPanelButtons.length) % powerPanelButtons.length].focus();
    } else if (e.key === "ArrowUp" || e.key === "ArrowLeft") {
      e.preventDefault();
      powerPanelButtons[(idx - 1 + powerPanelButtons.length) % powerPanelButtons.length].focus();
    } else if (e.key === "Escape") {
      e.preventDefault();
      callApi("set_panel", "none");
    }
  });

  // ---- network content --------------------------------------------------------

  function iconForNetworkType(type) {
    return type === "wifi" ? "wifi" : "ethernet";
  }

  function applyNetworkStatus(net) {
    if (!net) return;
    lastNetworkType = net.type;
    const icon = iconForNetworkType(net.type);
    launcherNetworkIcon.setAttribute("data-icon", icon);
    networkStatusIcon.setAttribute("data-icon", icon);
    networkStatusLabel.textContent = net.label;
    networkStatusSub.textContent = net.sub;
    document.getElementById("tile-network-sub").textContent = net.sub;
    wifiRadioToggle.classList.toggle("on", !!net.wifi_radio_on);
  }

  wifiRadioToggle.addEventListener("click", async () => {
    const net = await callApi("network_toggle_wifi_radio");
    applyNetworkStatus(net);
    refreshNetworkList();
  });

  function renderNetworkList(networks) {
    networkList.innerHTML = "";
    if (!networks || networks.length === 0) {
      const li = document.createElement("li");
      li.className = "network-empty";
      li.textContent = "No networks found.";
      networkList.appendChild(li);
      return;
    }
    networks.forEach((net) => {
      const li = document.createElement("li");
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = "network-item" + (net.in_use ? " in-use" : "");
      btn.innerHTML =
        '<span class="icon" data-icon="wifi"></span>' +
        '<span class="ssid"></span>' +
        '<span class="signal"></span>';
      btn.querySelector(".ssid").textContent = net.ssid;
      btn.querySelector(".signal").textContent = net.signal + "%";
      btn.addEventListener("click", () => onNetworkItemChosen(net));
      li.appendChild(btn);
      networkList.appendChild(li);
    });
  }

  async function refreshNetworkList() {
    if (activeTile !== "network" || !panelOpen) return;
    const networks = await callApi("network_scan");
    renderNetworkList(networks);
  }

  function onNetworkItemChosen(net) {
    if (net.in_use) return; // already connected to this one
    if (!net.secured) {
      connectToNetwork(net.ssid, "");
      return;
    }
    pendingConnectSsid = net.ssid;
    wifiConnectSsid.textContent = net.ssid;
    wifiConnectPassword.value = "";
    wifiConnectStatus.textContent = "";
    wifiConnectForm.classList.remove("hidden");
    requestAnimationFrame(() => wifiConnectPassword.focus());
  }

  function hideConnectForm() {
    wifiConnectForm.classList.add("hidden");
    pendingConnectSsid = null;
  }

  wifiConnectCancel.addEventListener("click", hideConnectForm);

  wifiConnectForm.addEventListener("submit", (e) => {
    e.preventDefault();
    if (!pendingConnectSsid) return;
    connectToNetwork(pendingConnectSsid, wifiConnectPassword.value);
  });

  async function connectToNetwork(ssid, password) {
    wifiConnectStatus.textContent = "Connecting to " + ssid + "…";
    const result = await callApi("network_connect", ssid, password);
    if (result && result.success) {
      wifiConnectStatus.textContent = "Connected.";
      applyNetworkStatus(result.network);
      setTimeout(() => {
        hideConnectForm();
        refreshNetworkList();
      }, 700);
    } else {
      wifiConnectStatus.textContent = "Couldn't connect. Check the password and try again.";
    }
  }

  // ---- sliders: brightness + volume (the two essentials that are always
  // visible regardless of which tile is active) ------------------------------

  function debounce(key, fn, ms) {
    clearTimeout(debounceTimers[key]);
    debounceTimers[key] = setTimeout(fn, ms);
  }

  brightnessSlider.addEventListener("input", () => {
    const level = parseInt(brightnessSlider.value, 10);
    brightnessValue.textContent = level + "%";
    // Set the tracking var before showOsd() so the next status poll
    // (which also drives the OSD -- see applyBrightness()) sees no
    // further change and doesn't pop a redundant, stale toast.
    lastBrightnessLevel = level;
    showOsd("brightness", level, false);
    debounce("brightness", () => callApi("set_brightness", level), SLIDER_DEBOUNCE_MS);
  });

  volumeSlider.addEventListener("input", () => {
    const level = parseInt(volumeSlider.value, 10);
    volumeValue.textContent = level + "%";
    lastVolumeLevel = level;
    lastVolumeMuted = false;
    showOsd("volume", level, false);
    debounce("volume", () => callApi("set_volume", level), SLIDER_DEBOUNCE_MS);
  });

  volumeMuteBtn.addEventListener("click", async () => {
    const vol = await callApi("toggle_mute");
    applyVolume(vol);
  });

  function applyVolume(vol) {
    if (!vol) return;
    volumeSlider.value = vol.level;
    volumeValue.textContent = vol.level + "%";
    const icon = vol.muted ? "volume-muted" : "volume";
    volumeIcon.setAttribute("data-icon", icon);
    launcherVolumeIcon.setAttribute("data-icon", icon);
    // Fires the OSD toast for changes this file didn't already know
    // about -- chiefly the volume hardware buttons on the remote,
    // which (per docs/planning/REMOTE-INPUT-MAPPING.md's "Volume —
    // config only") go straight to wpctl via a Sway bindsym and never
    // touch this file's own set_volume()/toggle_mute() at all. This
    // status poll (every STATUS_POLL_MS) is the only way this window
    // finds out such a change happened, so the toast can lag a
    // hardware button press by up to that interval -- same latency
    // the mapping doc already accepted for the plain on-screen pill
    // this reuses. statusInitialized guards the very first poll from
    // popping a toast for the ambient volume the session just booted
    // with, rather than an actual change.
    if (statusInitialized && (vol.level !== lastVolumeLevel || vol.muted !== lastVolumeMuted)) {
      showOsd("volume", vol.level, vol.muted);
    }
    lastVolumeLevel = vol.level;
    lastVolumeMuted = vol.muted;
  }

  function applyBrightness(b) {
    if (!b) return;
    brightnessSlider.value = b.level;
    brightnessValue.textContent = b.level + "%";
    if (statusInitialized && b.level !== lastBrightnessLevel) {
      showOsd("brightness", b.level, false);
    }
    lastBrightnessLevel = b.level;
  }

  // ---- OSD toast: transient volume/brightness indicator ----------------------
  //
  // TV-style "on-screen display" -- see the .osd rules in style.css and
  // overlay.py's PANEL_GEOMETRY['osd'] for the small, centered window
  // geometry this borrows while it's shown. Skipped entirely while the
  // full settings panel or the lock screen is up: the sliders (or
  // nothing at all, if locked) are already the right thing to show in
  // those states, and set_panel('osd') would fight either one's own
  // geometry if called on top of it.
  let osdHideTimer = null;
  let lastVolumeLevel = null;
  let lastVolumeMuted = null;
  let lastBrightnessLevel = null;
  let statusInitialized = false;
  const OSD_HIDE_MS = 1600;

  function showOsd(kind, level, muted) {
    if (panelOpen || locked) return;
    osd.classList.remove("osd-volume", "osd-brightness");
    osd.classList.add(kind === "volume" ? "osd-volume" : "osd-brightness");
    osd.classList.remove("hidden");
    osdValue.textContent = muted ? "Muted" : level + "%";
    osdFill.style.width = (muted ? 0 : level) + "%";

    if (kind === "volume") {
      osdIcon.setAttribute("data-icon", muted || level === 0 ? "volume-muted" : "volume");
      // Tiers step the glyph itself as level crosses 0/34/67% -- the
      // "moves" half of the design brief this was built from.
      // Brightness has no equivalent: its icon is a static sun: see
      // the .osd.osd-volume-only rules in style.css.
      osdIcon.classList.toggle("tier-1", !muted && level > 0);
      osdIcon.classList.toggle("tier-2", !muted && level >= 34);
      osdIcon.classList.toggle("tier-3", !muted && level >= 67);
    } else {
      osdIcon.setAttribute("data-icon", "brightness");
      osdIcon.classList.remove("tier-1", "tier-2", "tier-3");
    }

    callApi("set_panel", "osd");
    clearTimeout(osdHideTimer);
    osdHideTimer = setTimeout(hideOsd, OSD_HIDE_MS);
  }

  function hideOsd() {
    osd.classList.add("hidden");
    if (!panelOpen && !locked) callApi("set_panel", "none");
  }

  // ---- status polling --------------------------------------------------------

  function applyBattery(battery, isLaptop) {
    const show = isLaptop && battery;
    launcherBattery.classList.toggle("hidden", !show);
    panelBattery.classList.toggle("hidden", !show);
    if (show) {
      launcherBatteryPct.textContent = battery.percent + "%";
      panelBatteryPct.textContent = battery.percent + "%";
    }
  }

  async function refreshStatus() {
    const status = await callApi("get_status");
    if (!status) return;
    applyNetworkStatus(status.network);
    applyVolume(status.volume);
    applyBrightness(status.brightness);
    applyBattery(status.battery, status.is_laptop);
    statusInitialized = true;
  }

  // ---- lock screen ------------------------------------------------------------

  lockUnlockBtn.addEventListener("click", () => callApi("unlock"));

  // ---- pywebview bridge --------------------------------------------------------

  function callApi(method, ...args) {
    if (!window.pywebview || !window.pywebview.api || !window.pywebview.api[method]) {
      return Promise.resolve(null);
    }
    return window.pywebview.api[method](...args).catch(() => null);
  }

  // ---- hook called from overlay.py (SystemAPI.lock/unlock) ------------------
  //
  // A plain window-global function, not a pywebview.api method, because
  // this is a push from Python into JS (window.evaluate_js), the
  // opposite direction from callApi() above. Guarded with
  // `window.__cgX && ...` on the Python side, so it's safe for this to
  // simply not exist yet on an older overlay.py -- see overlay.py's own
  // lock()/unlock().
  //
  // There is no __cgSetImmersiveHidden hook any more, and no
  // Immersive-Mode-driven auto-hide behind it -- see overlay.py's
  // module-level comment (above PLACEHOLDER_TILES) for why. The
  // launcher has no "hidden" class applied anywhere at load, so it's
  // simply always shown by default; nothing here needs to toggle it.

  window.__cgSetLocked = function (isLocked) {
    locked = !!isLocked;
    if (locked) {
      // Direct DOM cleanup here, not closePanel()/callApi("set_panel",
      // ...): overlay.py's set_panel() bails out immediately whenever
      // self.locked is true (see its own docstring), before it would
      // ever push a state back into __cgSetPanel -- so a round trip
      // through Python would leave panelOpen stuck true forever with
      // nothing left to flip it back. Reset everything locally instead.
      clearTimeout(osdHideTimer);
      osd.classList.add("hidden");
      powerPanel.classList.add("hidden");
      scrim.classList.add("hidden");
      panel.classList.add("hidden");
      powerMenu.classList.add("hidden");
      panelOpen = false;
      lockScreen.classList.remove("hidden");
      requestAnimationFrame(() => lockUnlockBtn.focus());
    } else {
      lockScreen.classList.add("hidden");
      launcher.focus();
    }
  };

  function init() {
    setActiveTile("network");
    refreshStatus();
    setInterval(refreshStatus, STATUS_POLL_MS);
  }

  if (window.pywebview) {
    init();
  } else {
    window.addEventListener("pywebviewready", init, { once: true });
  }
})();
