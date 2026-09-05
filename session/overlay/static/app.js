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

  // ---- panel open/close ---------------------------------------------------

  function openPanel() {
    if (panelOpen) return;
    panelOpen = true;
    scrim.classList.remove("hidden");
    panel.classList.remove("hidden");
    callApi("set_panel", "overlay");
    refreshStatus();
    if (activeTile === "network") refreshNetworkList();
    requestAnimationFrame(() => tiles[0] && tiles[0].focus());
  }

  function closePanel() {
    if (!panelOpen) return;
    panelOpen = false;
    scrim.classList.add("hidden");
    panel.classList.add("hidden");
    powerMenu.classList.add("hidden");
    hideConnectForm();
    callApi("set_panel", "none");
    launcher.focus();
  }

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

  // ---- power menu -----------------------------------------------------------

  powerToggle.addEventListener("click", () => {
    powerMenu.classList.toggle("hidden");
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
    const level = brightnessSlider.value;
    brightnessValue.textContent = level + "%";
    debounce("brightness", () => callApi("set_brightness", level), SLIDER_DEBOUNCE_MS);
  });

  volumeSlider.addEventListener("input", () => {
    const level = volumeSlider.value;
    volumeValue.textContent = level + "%";
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
  }

  function applyBrightness(b) {
    if (!b) return;
    brightnessSlider.value = b.level;
    brightnessValue.textContent = b.level + "%";
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
  }

  // ---- pywebview bridge --------------------------------------------------------

  function callApi(method, ...args) {
    if (!window.pywebview || !window.pywebview.api || !window.pywebview.api[method]) {
      return Promise.resolve(null);
    }
    return window.pywebview.api[method](...args).catch(() => null);
  }

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
