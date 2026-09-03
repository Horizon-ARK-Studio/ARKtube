// app.js — Stage 6 topbar frontend.
//
// Talks only to `pywebview.api.*` (session/topbar/topbar.py), never to the
// network. Every control here is expected to keep working with Wi-Fi off
// or unavailable entirely — see docs/STAGE-6-OFFLINE-TOPBAR.md.

const WORLD_CLOCKS = [
  { name: "Tokyo", offsetHours: 9 },
];

let activePanel = "none"; // "none" | "calendar" | "quicksettings"
let calCursor = new Date(); // month currently shown in the calendar grid

function api() {
  return window.pywebview && window.pywebview.api;
}

function $(id) { return document.getElementById(id); }

// ---- panel open/close -----------------------------------------------------

function setPanel(panel) {
  activePanel = panel;
  $("calendar-panel").classList.toggle("open", panel === "calendar");
  $("quicksettings-panel").classList.toggle("open", panel === "quicksettings");
  $("backdrop").classList.toggle("visible", panel !== "none");
  if (panel !== "quicksettings") $("power-menu").classList.add("hidden");
  const a = api();
  if (a) a.set_panel(panel);
  if (panel === "calendar") renderCalendar();
  if (panel === "quicksettings") refreshStatus();
}

function togglePanel(panel) {
  setPanel(activePanel === panel ? "none" : panel);
}

$("clock-pill").addEventListener("click", () => togglePanel("calendar"));
$("wifi-btn").addEventListener("click", () => togglePanel("quicksettings"));
$("volume-btn").addEventListener("click", () => togglePanel("quicksettings"));
$("battery-btn").addEventListener("click", () => togglePanel("quicksettings"));
$("backdrop").addEventListener("click", () => setPanel("none"));

// ---- clock (ticks locally; only the backend calls are network-free-only) --

function tickClock() {
  const now = new Date();
  $("clock-time").textContent = now.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
}
setInterval(tickClock, 1000);
tickClock();

// ---- status polling (battery, volume, wifi, brightness, toggles) ---------

function refreshStatus() {
  const a = api();
  if (!a) return;
  a.get_status().then((s) => {
    if (s.battery) {
      $("battery-pct").textContent = s.battery.percent + "%";
      $("qs-battery-pct").textContent = s.battery.percent + "%";
    } else {
      $("battery-pct").textContent = "";
      $("qs-battery-pct").textContent = "N/A";
    }

    if (s.volume && s.volume.available) {
      $("volume-slider").value = s.volume.muted ? 0 : s.volume.level;
      $("volume-slider").style.setProperty("--val", ($("volume-slider").value) + "%");
    }

    if (s.brightness && s.brightness.available) {
      $("brightness-slider").value = s.brightness.level;
      $("brightness-slider").style.setProperty("--val", s.brightness.level + "%");
      $("brightness-slider").disabled = false;
    } else {
      $("brightness-slider").disabled = true;
    }

    const wifiTile = $("qs-wifi");
    const wifiSub = $("qs-wifi-sub");
    if (!s.wifi.available) {
      wifiTile.classList.remove("active");
      wifiTile.disabled = true;
      wifiSub.textContent = "Unavailable";
    } else {
      wifiTile.disabled = false;
      wifiTile.classList.toggle("active", s.wifi.connected);
      wifiSub.textContent = s.wifi.connected ? s.wifi.ssid : "Off";
    }

    $("qs-nightlight").classList.toggle("active", !!s.night_light);
    $("qs-darkstyle").classList.toggle("active", !!s.dark_style);
    $("qs-airplane").classList.toggle("active", !!s.airplane_mode);
  });
}
setInterval(refreshStatus, 8000);

// ---- quick settings interactions ------------------------------------------

$("volume-slider").addEventListener("input", (e) => {
  e.target.style.setProperty("--val", e.target.value + "%");
});
$("volume-slider").addEventListener("change", (e) => {
  const a = api();
  if (a) a.set_volume(e.target.value);
});

$("brightness-slider").addEventListener("input", (e) => {
  e.target.style.setProperty("--val", e.target.value + "%");
});
$("brightness-slider").addEventListener("change", (e) => {
  const a = api();
  if (a) a.set_brightness(e.target.value);
});

$("qs-wifi").addEventListener("click", () => { const a = api(); if (a) a.toggle_wifi().then(refreshStatus); });
$("qs-nightlight").addEventListener("click", () => { const a = api(); if (a) a.toggle_night_light().then(refreshStatus); });
$("qs-darkstyle").addEventListener("click", () => { const a = api(); if (a) a.toggle_dark_style().then(refreshStatus); });
$("qs-airplane").addEventListener("click", () => { const a = api(); if (a) a.toggle_airplane_mode().then(refreshStatus); });

$("qs-screenshot").addEventListener("click", () => { const a = api(); if (a) a.screenshot(); });
$("qs-lock").addEventListener("click", () => { const a = api(); if (a) a.lock(); setPanel("none"); });
$("qs-power").addEventListener("click", () => $("power-menu").classList.toggle("hidden"));
$("power-logout").addEventListener("click", () => { const a = api(); if (a) a.logout(); });
$("power-reboot").addEventListener("click", () => { const a = api(); if (a) a.reboot(); });
$("power-poweroff").addEventListener("click", () => { const a = api(); if (a) a.poweroff(); });

$("dnd-toggle").addEventListener("change", () => {
  // Local-only: no notification daemon integration exists yet to suppress
  // against. Tracked as an open item in docs/STAGE-6-OFFLINE-TOPBAR.md
  // rather than silently pretending this call already does something.
});
$("clear-notif").addEventListener("click", () => {});

// ---- calendar rendering -----------------------------------------------------

function renderCalendar() {
  const today = new Date();
  $("cal-weekday").textContent = today.toLocaleDateString([], { weekday: "long" });
  $("cal-date").textContent = today.toLocaleDateString([], { month: "long", day: "numeric", year: "numeric" });

  $("cal-month-label").textContent = calCursor.toLocaleDateString([], { month: "long" });

  const grid = $("cal-grid");
  grid.innerHTML = "";
  ["S", "M", "T", "W", "T", "F", "S"].forEach((d) => {
    const el = document.createElement("div");
    el.className = "dow";
    el.textContent = d;
    grid.appendChild(el);
  });

  const year = calCursor.getFullYear();
  const month = calCursor.getMonth();
  const firstOfMonth = new Date(year, month, 1);
  const startOffset = firstOfMonth.getDay(); // 0 = Sunday
  const gridStart = new Date(year, month, 1 - startOffset);

  for (let i = 0; i < 42; i++) {
    const cellDate = new Date(gridStart);
    cellDate.setDate(gridStart.getDate() + i);
    const el = document.createElement("div");
    el.className = "day";
    if (cellDate.getMonth() === month) el.classList.add("this-month");
    if (cellDate.toDateString() === today.toDateString()) el.classList.add("today");
    el.textContent = String(cellDate.getDate()).padStart(2, "0");
    grid.appendChild(el);
  }

  const worldClocksEl = $("world-clocks");
  worldClocksEl.innerHTML = "";
  if (WORLD_CLOCKS.length === 0) {
    worldClocksEl.textContent = "No Clocks";
  } else {
    WORLD_CLOCKS.forEach((clock) => {
      const now = new Date();
      const utc = now.getTime() + now.getTimezoneOffset() * 60000;
      const there = new Date(utc + clock.offsetHours * 3600000);
      const localOffset = -now.getTimezoneOffset() / 60;
      const diff = clock.offsetHours - localOffset;

      const row = document.createElement("div");
      const label = document.createElement("span");
      label.textContent = clock.name;
      const time = document.createElement("span");
      time.className = "wc-time";
      time.textContent = there.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
      const offset = document.createElement("span");
      offset.className = "wc-offset";
      offset.textContent = (diff >= 0 ? "+" : "") + diff;
      row.appendChild(label);
      const right = document.createElement("span");
      right.appendChild(time);
      right.appendChild(offset);
      row.appendChild(right);
      row.style.display = "flex";
      row.style.justifyContent = "space-between";
      worldClocksEl.appendChild(row);
    });
  }
}

$("cal-prev").addEventListener("click", () => {
  calCursor = new Date(calCursor.getFullYear(), calCursor.getMonth() - 1, 1);
  renderCalendar();
});
$("cal-next").addEventListener("click", () => {
  calCursor = new Date(calCursor.getFullYear(), calCursor.getMonth() + 1, 1);
  renderCalendar();
});

// Initial paint once pywebview's bridge is ready.
window.addEventListener("pywebviewready", refreshStatus);
refreshStatus();
