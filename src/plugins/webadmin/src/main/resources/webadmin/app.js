"use strict";

const state = {
  csrf: "",
  username: "",
  configFiles: [],
  config: null,
  logTimer: null,
  logBusy: false,
  jobTimer: null,
  expiryTimer: null,
  recoveryTimer: null,
  dashboardTimer: null,
  dashboardCommandsTimer: null,
  overviewBusy: false,
  dashboardBusy: false,
  samples: [],
  dupeJob: null,
  dupePaths: [],
  dupeSelected: new Set(),
  dupePage: 0,
  dupeBusy: false,
  view: "overview"
};

const $ = (selector) => document.querySelector(selector);
const $$ = (selector) => Array.from(document.querySelectorAll(selector));

async function api(path, options = {}) {
  const headers = new Headers(options.headers || {});
  if (options.body && !headers.has("Content-Type")) headers.set("Content-Type", "application/json");
  if (state.csrf && options.method && options.method !== "GET") headers.set("X-CSRF-Token", state.csrf);
  const response = await fetch(path, { ...options, headers, credentials: "same-origin" });
  let body = {};
  try { body = await response.json(); } catch (_) { body = {}; }
  if (response.status === 401 && path !== "/api/login") showLogin();
  if (!response.ok) throw new Error(body.error || `Request failed (${response.status})`);
  $("#connectionState").classList.add("online");
  $("#connectionState").textContent = "Online";
  return body;
}

function showLogin(expired = false) {
  state.csrf = "";
  state.username = "";
  $("#appView").hidden = true;
  $("#loginView").hidden = false;
  clearInterval(state.logTimer);
  clearInterval(state.jobTimer);
  clearTimeout(state.expiryTimer);
  clearTimeout(state.recoveryTimer);
  clearTimeout(state.dashboardTimer);
  clearTimeout(state.dashboardCommandsTimer);
  state.dupeJob = null;
  state.dupePaths = [];
  state.dupeSelected.clear();
  if ($("#confirmDialog").open) $("#confirmDialog").close("cancel");
  if (location.pathname === "/home") {
    location.replace(expired ? "/login?expired=1" : "/login");
    return;
  }
  history.replaceState(null, "", "/login");
  if (expired) $("#loginError").textContent = "Session expired. Sign in again.";
}

function showApp(session) {
  state.csrf = session.csrf;
  state.username = session.username;
  $("#currentUser").textContent = session.username;
  $("#loginView").hidden = true;
  $("#appView").hidden = false;
  history.replaceState(null, "", "/home");
  clearTimeout(state.expiryTimer);
  state.expiryTimer = setTimeout(() => showLogin(true), Math.max(0, session.expiresAt - session.serverTime));
  selectView("overview");
  loadFileScopes();
}

function toast(message) {
  const element = $("#toast");
  element.textContent = message;
  element.hidden = false;
  clearTimeout(element._timer);
  element._timer = setTimeout(() => { element.hidden = true; }, 4000);
}

function showError(error) {
  $("#connectionState").classList.remove("online");
  $("#connectionState").textContent = "Issue";
  toast(error.message || String(error));
}

async function confirmAction(title, text) {
  const dialog = $("#confirmDialog");
  $("#confirmTitle").textContent = title;
  $("#confirmText").textContent = text;
  dialog.showModal();
  return new Promise((resolve) => {
    dialog.addEventListener("close", () => resolve(dialog.returnValue === "confirm"), { once: true });
  });
}

function formatBytes(value, suffix = "") {
  if (!Number.isFinite(value) || value < 0) return "-";
  const units = ["B", "KB", "MB", "GB", "TB", "PB"];
  let size = value;
  let index = 0;
  while (size >= 1024 && index < units.length - 1) {
    size /= 1024;
    index++;
  }
  const digits = index > 2 ? 1 : index > 0 ? 1 : 0;
  return `${size.toFixed(digits)}${units[index]}${suffix}`;
}

function formatDuration(milliseconds) {
  if (!Number.isFinite(milliseconds)) return "-";
  const seconds = Math.floor(milliseconds / 1000);
  const days = Math.floor(seconds / 86400);
  const hours = Math.floor((seconds % 86400) / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  if (days) return `${days}d ${hours}h`;
  if (hours) return `${hours}h ${minutes}m`;
  return `${minutes}m`;
}

function formatTime(value) {
  return value ? new Date(value).toLocaleString() : "Never";
}

function td(text, className = "") {
  const cell = document.createElement("td");
  cell.textContent = text;
  if (className) cell.className = className;
  return cell;
}

function stateBadge(text, kind) {
  const span = document.createElement("span");
  span.className = `state ${kind}`;
  span.textContent = text;
  return span;
}

async function loadOverview() {
  clearTimeout(state.dashboardTimer);
  if (state.overviewBusy || !state.csrf) return;
  state.overviewBusy = true;
  try {
    const data = await api("/api/overview");
    $("#metricSlaves").textContent = `${data.onlineSlaves} / ${data.totalSlaves}`;
    $("#metricRemerging").textContent = data.remergingSlaves;
    $("#metricStorage").textContent = formatBytes(data.freeBytes);
    $("#metricHeap").textContent = `${formatBytes(data.heapUsedBytes)} / ${formatBytes(data.heapMaximumBytes)}`;
    $("#metricCpu").textContent = data.processors;
    $("#metricUptime").textContent = formatDuration(data.uptimeMillis);
    const percent = value => Number.isFinite(value) && value >= 0 ? (value * 100).toFixed(1) + "%" : "-";
    $("#metricCpuUsage").textContent = percent(data.systemCpu) + " / " + percent(data.processCpu);
    $("#metricRam").textContent = data.memoryTotalBytes >= 0
      ? formatBytes(data.memoryTotalBytes - data.memoryFreeBytes) + " / " + formatBytes(data.memoryTotalBytes) : "-";
    $("#metricLoad").textContent = (data.loadAverages || []).map(v => v < 0 ? "-" : v.toFixed(2)).join(" / ");
    $("#metricThreads").textContent = data.threads;
    $("#dashboardUpdated").textContent = "Updated " + new Date(data.sampledAt).toLocaleTimeString();
    state.samples.push([data.systemCpu, data.heapUsedBytes / data.heapMaximumBytes]);
    if (state.samples.length > 60) state.samples.shift();
    drawRuntimeChart();
    $("#slaveCount").textContent = `${data.slaves.length} configured`;
    const body = $("#slaveRows");
    body.replaceChildren();
    for (const slave of data.slaves) {
      const row = document.createElement("tr");
      row.append(td(slave.name));
      const statusCell = document.createElement("td");
      const kind = !slave.online ? "offline" : slave.remerging ? "remerging" : "online";
      const label = (!slave.online ? "Offline" : slave.remerging ? "Remerging" : "Online") + (slave.full ? " FULL" : "");
      statusCell.append(stateBadge(label, kind));
      row.append(statusCell);
      row.append(td(slave.online ? `${slave.uploadTransfers || 0} up / ${slave.downloadTransfers || 0} down` : "-"));
      row.append(td(slave.online
        ? `${formatBytes(slave.uploadBytesPerSecond || 0, "/s")} up / ${formatBytes(slave.downloadBytesPerSecond || 0, "/s")} down`
        : "-"));
      row.append(td(slave.online ? `${formatBytes(slave.freeBytes)} / ${formatBytes(slave.capacityBytes)}` : "-"));
      row.append(td(`${slave.renameQueue} rename / ${slave.remergeQueue} merge / ${slave.crcQueue} CRC`));
      body.append(row);
    }
  } catch (error) { showError(error); }
  finally {
    state.overviewBusy = false;
    if (state.csrf && state.view === "overview") state.dashboardTimer = setTimeout(loadOverview, 5000);
  }
}

function drawRuntimeChart() {
  const canvas = $("#runtimeChart");
  const width = Math.max(1, canvas.clientWidth);
  const ratio = window.devicePixelRatio || 1;
  canvas.width = width * ratio;
  canvas.height = 130 * ratio;
  const ctx = canvas.getContext("2d");
  ctx.scale(ratio, ratio);
  ctx.strokeStyle = "#d8ddda";
  for (const y of [10, 65, 120]) { ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(width, y); ctx.stroke(); }
  ["#13795b", "#1769aa"].forEach((color, series) => {
    ctx.strokeStyle = color;
    ctx.lineWidth = 2;
    ctx.beginPath();
    let drawing = false;
    state.samples.forEach((sample, i) => {
      if (!Number.isFinite(sample[series]) || sample[series] < 0) { drawing = false; return; }
      const x = i * width / 59;
      const y = 120 - Math.max(0, Math.min(1, sample[series])) * 110;
      if (drawing) ctx.lineTo(x, y); else ctx.moveTo(x, y);
      drawing = true;
    });
    ctx.stroke();
  });
}

async function waitForJob(id, update) {
  const token = state.csrf;
  while (token && state.csrf === token) {
    const job = await api("/api/jobs/" + encodeURIComponent(id));
    if (state.csrf !== token) throw new Error("Session ended");
    update(job);
    if (job.state === "completed" || job.state === "failed") return job;
    await new Promise(resolve => setTimeout(resolve, 1200));
  }
  throw new Error("Session ended");
}

async function loadDashboardCommands() {
  clearTimeout(state.dashboardCommandsTimer);
  if (state.dashboardBusy || !state.csrf) return;
  state.dashboardBusy = true;
  try {
    for (const [command, target] of [["SITE SWHO", "#dashboardWho"], ["SITE STATUS ALL", "#dashboardStatus"]]) {
      try {
        const job = await api("/api/commands", {method: "POST", body: JSON.stringify({command})});
        await waitForJob(job.id, result => {
          $(target).textContent = (result.output || []).join("\n") + (result.error ? "\n" + result.error : "");
        });
      } catch (error) { $(target).textContent = error.message; }
      if (!state.csrf) break;
    }
  } finally {
    state.dashboardBusy = false;
    if (state.csrf && state.view === "overview") state.dashboardCommandsTimer = setTimeout(loadDashboardCommands, 30000);
  }
}

async function loadFileScopes() {
  try {
    const data = await api("/api/file-scopes");
    for (const [target, kind] of [["#configScope", "config"], ["#logScope", "logs"]]) {
      $(target).replaceChildren();
      data.scopes.filter(scope => scope[kind]).forEach(scope => {
        const option = document.createElement("option");
        option.value = scope.name;
        option.textContent = scope.label || (scope.name === "master" ? "Master" : scope.name);
        $(target).append(option);
      });
    }
  } catch (error) { showError(error); }
}

function fileApi(path, kind, selectedScope) {
  const scope = selectedScope || $(kind === "config" ? "#configScope" : "#logScope").value;
  return path + (path.includes("?") ? "&" : "?") + "scope=" + encodeURIComponent(scope);
}

async function loadTimers() {
  try {
    const data = await api("/api/timers");
    const body = $("#timerRows");
    body.replaceChildren();
    for (const timer of data.timers) {
      const row = document.createElement("tr");
      row.append(td(timer.name), td(timer.owner));
      const statusCell = document.createElement("td");
      const label = timer.running ? "Running" : timer.enabled ? "Waiting" : "Disabled";
      statusCell.append(stateBadge(label, timer.running ? "running" : timer.enabled ? "online" : "offline"));
      row.append(statusCell);
      row.append(td(timer.periodMillis ? formatDuration(timer.periodMillis) : "One shot"));
      row.append(td(formatTime(timer.lastRun)));
      row.append(td(timer.lastError || "-", timer.lastError ? "" : "muted"));
      body.append(row);
    }
    const eventList = $("#timeEventRows");
    eventList.replaceChildren();
    for (const name of data.timeEvents || []) {
      const item = document.createElement("li");
      item.textContent = name;
      eventList.append(item);
    }
    $("#timeEventCount").textContent = `${(data.timeEvents || []).length} registered`;
  } catch (error) { showError(error); }
}

async function loadConfigFiles() {
  const scope = $("#configScope").value;
  try {
    const data = await api(fileApi("/api/config/files", "config", scope));
    if (scope !== $("#configScope").value) return;
    state.configFiles = data.files;
    renderConfigFiles();
  } catch (error) { showError(error); }
}

function renderConfigFiles() {
  const filter = $("#configFilter").value.toLowerCase();
  const list = $("#configFiles");
  list.replaceChildren();
  for (const file of state.configFiles.filter(item => item.path.toLowerCase().includes(filter))) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "file-item";
    button.textContent = file.path;
    button.title = file.path;
    if (state.config && state.config.path === file.path) button.classList.add("active");
    button.addEventListener("click", () => openConfig(file.path));
    list.append(button);
  }
}

async function openConfig(path) {
  const scope = $("#configScope").value;
  try {
    const data = await api(fileApi(`/api/config/file?path=${encodeURIComponent(path)}`, "config", scope));
    if (scope !== $("#configScope").value) return;
    state.config = { ...data, scope };
    $("#configPath").textContent = data.path;
    $("#configModified").textContent = formatTime(data.modified);
    $("#configEditor").disabled = false;
    $("#configEditor").value = data.content;
    $("#saveConfig").disabled = true;
    $("#configStatus").textContent = "";
    renderConfigFiles();
  } catch (error) { showError(error); }
}

async function saveConfig() {
  if (!state.config) return;
  try {
    const config = state.config;
    const content = $("#configEditor").value;
    const response = await api(fileApi("/api/config/file", "config", config.scope), {
      method: "PUT",
      body: JSON.stringify({
        path: state.config.path,
        content,
        version: state.config.version
      })
    });
    if (state.config !== config) return;
    state.config.version = response.version;
    state.config.modified = response.modified;
    state.config.content = content;
    $("#configModified").textContent = formatTime(response.modified);
    $("#saveConfig").disabled = $("#configEditor").value === content;
    $("#configStatus").textContent = "Saved. A timestamped backup was created.";
    toast("Configuration saved");
  } catch (error) {
    $("#configStatus").textContent = error.message;
    showError(error);
  }
}

async function loadLogFiles() {
  const scope = $("#logScope").value;
  try {
    const data = await api(fileApi("/api/logs/files", "logs", scope));
    if (scope !== $("#logScope").value) return;
    $("#logLines").max = data.maximumLines;
    $("#logLines").value = data.defaultLines;
    const select = $("#logFile");
    const selected = select.value;
    select.replaceChildren();
    for (const file of data.files) {
      const option = document.createElement("option");
      option.value = file.path;
      option.textContent = file.path;
      select.append(option);
    }
    if (selected && data.files.some(file => file.path === selected)) select.value = selected;
    if (select.value) await loadLog();
  } catch (error) {
    if (scope !== $("#logScope").value) return;
    $("#logFile").replaceChildren();
    $("#logOutput").textContent = error.message;
    $("#logFollow").checked = false;
    updateLogFollow();
    showError(error);
  }
}

async function loadLog() {
  if (state.logBusy) return;
  const scope = $("#logScope").value;
  const file = $("#logFile").value;
  if (!file) return;
  state.logBusy = true;
  try {
    const lines = $("#logLines").value || 500;
    const data = await api(fileApi(`/api/logs/tail?file=${encodeURIComponent(file)}&lines=${encodeURIComponent(lines)}`, "logs", scope));
    if (scope !== $("#logScope").value || file !== $("#logFile").value) return;
    const output = $("#logOutput");
    const atBottom = output.scrollHeight - output.scrollTop - output.clientHeight < 35;
    output.textContent = data.content;
    if (atBottom || $("#logFollow").checked) output.scrollTop = output.scrollHeight;
  } catch (error) {
    if (scope !== $("#logScope").value || file !== $("#logFile").value) return;
    $("#logOutput").textContent = error.message;
    $("#logFollow").checked = false;
    updateLogFollow();
    showError(error);
  } finally {
    state.logBusy = false;
    if (state.csrf && state.view === "logs"
        && (scope !== $("#logScope").value || file !== $("#logFile").value)) loadLog();
  }
}

function updateLogFollow() {
  clearInterval(state.logTimer);
  state.logTimer = null;
  if ($("#logFollow").checked && state.view === "logs") state.logTimer = setInterval(loadLog, 3000);
}

function dangerousCommand(command) {
  return /^\s*site\s+(wipe|nuke|unnuke|delete|addslave|slave|reload)\b/i.test(command)
    || /\s-(wipe|delete)\b/i.test(command);
}

async function runCommand(commandOverride) {
  const command = (commandOverride || $("#commandInput").value).trim();
  if (!command) return;
  if (dangerousCommand(command)) {
    const accepted = await confirmAction("Confirm SITE command", command);
    if (!accepted) return;
  }
  clearInterval(state.jobTimer);
  $("#commandOutput").textContent = "";
  $("#jobState").textContent = "Submitting";
  try {
    const job = await api("/api/commands", {
      method: "POST",
      body: JSON.stringify({ command })
    });
    $("#jobState").textContent = job.state;
    await pollJob(job.id);
    state.jobTimer = setInterval(() => pollJob(job.id), 1000);
  } catch (error) { showError(error); }
}

async function pollJob(id) {
  try {
    const job = await api(`/api/jobs/${encodeURIComponent(id)}`);
    $("#jobState").textContent = job.state;
    $("#commandOutput").textContent = (job.output || []).join("\n")
      + (job.error ? `\nERROR: ${job.error}` : "");
    if (job.state === "completed" || job.state === "failed") {
      clearInterval(state.jobTimer);
      state.jobTimer = null;
    }
  } catch (error) {
    clearInterval(state.jobTimer);
    state.jobTimer = null;
    showError(error);
  }
}

async function restartMaster() {
  const accepted = await confirmAction("Restart master", "Gracefully stop the DrFTPD master now?");
  if (!accepted) return;
  try {
    await api("/api/restart", {
      method: "POST",
      body: JSON.stringify({ confirm: "restart" })
    });
    toast("Master shutdown accepted");
  } catch (error) { showError(error); }
}

function selectView(name) {
  state.view = name;
  updateLogFollow();
  clearTimeout(state.dashboardTimer);
  clearTimeout(state.dashboardCommandsTimer);
  clearTimeout(state.recoveryTimer);
  $$(".nav-item").forEach(button => button.classList.toggle("active", button.dataset.view === name));
  $$(".view").forEach(view => view.classList.toggle("active", view.id === `view-${name}`));
  $("#sidebar").classList.remove("open");
  if (name === "overview") { loadOverview(); loadDashboardCommands(); }
  if (name === "dupe2") { renderDupes(); loadDupeSections(); }
  if (name === "archive") loadArchiveRules();
  if (name === "timers") loadTimers();
  if (name === "config" && state.configFiles.length === 0) loadConfigFiles();
  if (name === "logs" && $("#logFile").options.length === 0) loadLogFiles();
  if (name === "srrdb") loadRecovery();
}

async function loadArchiveRules() {
  try {
    const rules = await api("/api/archive/rules");
    const select = $("#archiveRule");
    select.replaceChildren(new Option("Custom folder rule", "0"));
    rules.forEach(rule => {
      const option = document.createElement("option");
      option.value = rule.id;
      option.dataset.rule = JSON.stringify(rule);
      option.textContent = rule.id + ": " + rule.section + " -> " + rule.destination + " (" + rule.template + ")";
      select.append(option);
    });
    if (rules.length) select.value = rules[0].id;
    const sections = await api("/api/dupe2/sections");
    $("#archiveSection").replaceChildren(...sections.map(section => new Option(section.name + " - " + section.path, section.name)));
    applyArchiveRule();
    $("#archiveStatus").textContent = "";
    $("#archivePreview").disabled = !sections.length;
  } catch (error) { showError(error); }
}

function applyArchiveRule() {
  const encoded = $("#archiveRule").selectedOptions[0]?.dataset.rule;
  const rule = encoded ? JSON.parse(encoded) : null;
  if (rule) $("#archiveSection").value = rule.section;
  $("#archiveDestination").value = rule?.destination || "";
  $("#archiveTemplate").value = rule?.template || "rls:${rls}";
}
$("#archiveRule").addEventListener("change", applyArchiveRule);

$("#archiveForm").addEventListener("submit", async event => {
  event.preventDefault();
  $("#archivePreview").disabled = true;
  $("#archiveRows").replaceChildren();
  try {
    const job = await api("/api/archive/preview", {method: "POST", body: JSON.stringify({
      rule: Number($("#archiveRule").value), limit: Number($("#archiveLimit").value),
      section: $("#archiveSection").value, destination: $("#archiveDestination").value.trim(),
      template: $("#archiveTemplate").value.trim()
    })});
    const result = await waitForJob(job.id, current => { $("#archiveStatus").textContent = current.state; });
    $("#archiveStatus").textContent = result.error || (result.output || []).join("\n");
    (result.archiveRows || []).forEach(item => {
      const row = document.createElement("tr");
      [item.source, item.destination, item.status].forEach(value => {
        const cell = document.createElement("td");
        cell.textContent = value;
        row.append(cell);
      });
      row.lastChild.className = item.status === "READY" ? "dupe-keep" : "dupe-incomplete";
      $("#archiveRows").append(row);
    });
  } catch (error) { showError(error); }
  finally { $("#archivePreview").disabled = false; }
});

async function loadDupeSections() {
  try {
    const sections = await api("/api/dupe2/sections");
    const select = $("#dupeSection"), previous = select.value;
    select.replaceChildren(new Option("Choose a section", ""));
    sections.forEach(section => select.append(new Option(section.name + " - " + section.path, section.path)));
    select.value = previous;
  } catch (error) { showError(error); }
}

function dupeGroups() {
  return Array.from(new Set(state.dupePaths.map(item => item.key)));
}
function visibleDupes() {
  const keys = new Set(dupeGroups().slice(state.dupePage * 10, (state.dupePage + 1) * 10));
  return state.dupePaths.filter(item => keys.has(item.key));
}

function renderDupes() {
  const pages = Math.max(1, Math.ceil(dupeGroups().length / 10));
  state.dupePage = Math.min(state.dupePage, pages - 1);
  const visible = visibleDupes();
  const body = $("#dupeRows");
  body.replaceChildren();
  let previousKey;
  for (const item of visible) {
    if (item.key !== previousKey) {
      const heading = document.createElement("tr");
      heading.className = "dupe-group";
      const label = td(item.key);
      label.colSpan = 4;
      heading.append(label);
      body.append(heading);
      previousKey = item.key;
    }
    const path = item.path;
    const row = document.createElement("tr");
    const cell = document.createElement("td");
    const checkbox = document.createElement("input");
    checkbox.type = "checkbox";
    checkbox.checked = state.dupeSelected.has(path);
    checkbox.disabled = state.dupeBusy;
    checkbox.setAttribute("aria-label", "Select " + path);
    checkbox.addEventListener("change", () => {
      if (checkbox.checked && state.dupeSelected.size >= 10000) {
        checkbox.checked = false;
        toast("Maximum 10000 selected releases.");
        return;
      }
      if (checkbox.checked) state.dupeSelected.add(path); else state.dupeSelected.delete(path);
      renderDupes();
    });
    cell.append(checkbox);
    const status = td(item.status);
    status.className = "dupe-status " + (item.status === "KEEP" ? "dupe-keep"
      : item.status === "Can delete" ? "dupe-delete" : "dupe-incomplete");
    row.append(cell, status, td(String(item.score)), td(path));
    body.append(row);
  }
  $("#dupePage").textContent = "Page " + (state.dupePage + 1) + " / " + pages + " - " + state.dupePaths.length + " results";
  $("#dupePrevious").disabled = state.dupePage === 0 || state.dupeBusy;
  $("#dupeNext").disabled = state.dupePage + 1 >= pages || state.dupeBusy;
  const eligible = visible.filter(item => item.eligible);
  $("#dupeSelectPage").disabled = !eligible.length || state.dupeBusy;
  $("#dupeSelectPage").checked = eligible.length > 0 && eligible.every(item => state.dupeSelected.has(item.path));
  $("#deleteDupes").disabled = state.dupeBusy || !state.dupeSelected.size;
  $("#deleteDupes").textContent = "Delete selected (" + state.dupeSelected.size + ")";
  $("#scanDupe").disabled = state.dupeBusy;
  $("#verifyDupes").disabled = state.dupeBusy || !state.dupePaths.some(item => item.status === "UNKNOWN" && state.dupeSelected.has(item.path));
  $("#selectUnknown").disabled = state.dupeBusy || !state.dupePaths.some(item => item.status === "UNKNOWN");
  $("#verifySection").disabled = state.dupeBusy;
  $("#verifyAllSections").disabled = state.dupeBusy;
  $("#moveDupes").disabled = state.dupeBusy || !state.dupeSelected.size;
}

async function submitDupe(payload) {
  state.dupeBusy = true;
  renderDupes();
  $("#dupeStatus").textContent = "Submitting";
  $("#dupeOutput").textContent = "";
  try {
    const job = await api("/api/dupe2", {method: "POST", body: JSON.stringify(payload)});
    const result = await waitForJob(job.id, current => {
      $("#dupeStatus").textContent = current.state;
      $("#dupeOutput").textContent = (current.output || []).join("\n") + (current.error ? "\n" + current.error : "");
    });
    if ((payload.action === "scan" || payload.action.startsWith("verify")) && result.state === "completed") {
      state.dupeJob = result.id;
      state.dupePaths = result.dupeResults || [];
      $("#dupeStatus").textContent = state.dupePaths.length + " releases; "
        + dupeGroups().length + " duplicate groups";
      state.dupeSelected.clear();
      state.dupePage = 0;
    }
    if (payload.action === "wipe" || payload.action === "move") {
      // Even partial failures invalidate the review; never repeat a destructive job automatically.
      state.dupeJob = null;
      state.dupePaths = [];
      state.dupeSelected.clear();
    }
  } catch (error) {
    $("#dupeStatus").textContent = error.message;
    if (payload.action === "wipe" || payload.action === "move") {
      state.dupeJob = null;
      state.dupePaths = [];
      state.dupeSelected.clear();
    }
    showError(error);
  } finally {
    state.dupeBusy = false;
    renderDupes();
  }
}

$("#dupeForm").addEventListener("submit", event => {
  event.preventDefault();
  state.dupeJob = null;
  state.dupePaths = [];
  state.dupeSelected.clear();
  state.dupePage = 0;
  const query = $("#dupePath").value.trim() || $("#dupeSection").value;
  if (!query) { toast("Choose a section or enter a release name."); return; }
  submitDupe({action: "scan", path: query,
    limit: Number($("#dupeLimit").value)});
});
$("#dupeSelectPage").addEventListener("change", () => {
  const visible = visibleDupes()
    .filter(item => item.eligible).map(item => item.path);
  if ($("#dupeSelectPage").checked) {
    for (const path of visible) {
      if (state.dupeSelected.size >= 100) break;
      state.dupeSelected.add(path);
    }
  } else { visible.forEach(path => state.dupeSelected.delete(path)); }
  renderDupes();
});
$("#dupePrevious").addEventListener("click", () => { state.dupePage--; renderDupes(); });
$("#dupeNext").addEventListener("click", () => { state.dupePage++; renderDupes(); });
$("#deleteDupes").addEventListener("click", async () => {
  const paths = Array.from(state.dupeSelected);
  if (paths.length > 100) { toast("Delete at most 100 releases per confirmation."); return; }
  if (!paths.length || state.dupeBusy || !state.dupeJob) return;
  if (!await confirmAction("Permanently wipe " + paths.length + " releases?",
    paths.map(path => (state.dupePaths.find(item => item.path === path)?.status || "UNKNOWN") + ": " + path).join("\n")
      + "\n\nPermanent recursive deletion, including any selected KEEP or UNKNOWN releases. This can remove your only valid copy.")) return;
  await submitDupe({action: "wipe", scanId: state.dupeJob, paths, confirm: "wipe-any-status"});
});

$("#verifyDupes").addEventListener("click", async () => {
  const paths = state.dupePaths.filter(item => item.status === "UNKNOWN" && state.dupeSelected.has(item.path)).map(item => item.path);
  if (!paths.length || !state.dupeJob || state.dupeBusy) return;
  if (!await confirmAction("Verify selected releases", "Fetch missing SFV/DIZ metadata and scan missing CRCs in the background? This reads slave disks; no files are deleted.\n\n" + paths.join("\n"))) return;
  await submitDupe({action: "verify", scanId: state.dupeJob, paths});
});

$("#selectUnknown").addEventListener("click", () => {
  const unknown = state.dupePaths.filter(item => item.status === "UNKNOWN").map(item => item.path);
  state.dupeSelected = new Set(unknown.slice(0, 10000));
  if (unknown.length > 10000) toast("Selected 10000 UNKNOWN releases. Use section verification for the entire section.");
  renderDupes();
});
async function verifyScope(all) {
  const path = $("#dupeSection").value;
  if (!all && !path) { toast("Choose a section first."); return; }
  if (!await confirmAction("Verify UNKNOWN releases", (all ? "All configured visible sections" : path)
    + "\nScan missing metadata and CRCs in the background. This may take a long time and reads slave disks. No files are deleted.")) return;
  await submitDupe({action: all ? "verify-all" : "verify-section", path, confirm: "verify-scope"});
}
$("#verifySection").addEventListener("click", () => verifyScope(false));
$("#verifyAllSections").addEventListener("click", () => verifyScope(true));

let moveDestination = "";
let moveBrowseRequest = 0;
async function browseMove(path) {
  const request = ++moveBrowseRequest;
  $("#moveConfirm").disabled = true;
  const folders = await api("/api/dupe2/folders?path=" + encodeURIComponent(path));
  if (request !== moveBrowseRequest) return;
  moveDestination = path;
  $("#moveDestination").textContent = path;
  $("#moveFolder").replaceChildren(new Option("This folder", ""), ...folders.map(folder => new Option(folder.split("/").pop(), folder)));
  $("#moveUp").disabled = path === $("#moveSection").value;
  $("#moveConfirm").disabled = false;
}
$("#moveDupes").addEventListener("click", async () => {
  if (state.dupeSelected.size > 100) { toast("Move at most 100 releases per confirmation."); return; }
  try {
    const sections = await api("/api/dupe2/sections");
    $("#moveSection").replaceChildren(...sections.map(section => new Option(section.name + " - " + section.path, section.path)));
    if (!sections.length) { toast("No visible sections."); return; }
    await browseMove($("#moveSection").value);
    $("#moveDialog").showModal();
  } catch (error) { showError(error); }
});
$("#moveSection").addEventListener("change", () => browseMove($("#moveSection").value).catch(showError));
$("#moveOpen").addEventListener("click", () => { if ($("#moveFolder").value) browseMove($("#moveFolder").value).catch(showError); });
$("#moveUp").addEventListener("click", () => {
  const root = $("#moveSection").value;
  const parent = moveDestination.substring(0, moveDestination.lastIndexOf("/")) || "/";
  browseMove(parent.startsWith(root + "/") || parent === root ? parent : root).catch(showError);
});
$("#moveCancel").addEventListener("click", () => $("#moveDialog").close());
$("#moveForm").addEventListener("submit", async event => {
  event.preventDefault();
  const paths = Array.from(state.dupeSelected), destination = $("#moveFolder").value || moveDestination;
  $("#moveDialog").close();
  if (!await confirmAction("Move " + paths.length + " releases?", paths.join("\n") + "\n\nDestination: " + destination)) return;
  await submitDupe({action: "move", scanId: state.dupeJob, paths, destination, confirm: "move-selected"});
});

function renderRecovery(data) {
  const busy = data.scanState === "queued" || data.scanState === "running";
  $("#scanRecovery").disabled = busy;
  $("#cancelRecovery").disabled = !busy;
  $("#recoveryProgress").textContent = `${data.scanState}: ${data.scanned}/${data.total} releases, ${data.notFound || 0} not found in srrDB, ${data.errors} errors. ${data.scanMessage || ""}`;
  $("#recoveryErrors").hidden = !data.scanErrors.length;
  $("#recoveryErrorText").textContent = data.scanErrors.join("\n");
  const body = $("#recoveryRows");
  body.replaceChildren();
  const comparisons = $("#recoveryComparisons");
  comparisons.replaceChildren();
  for (const item of data.comparisons || []) {
    const row = document.createElement("tr");
    const target = td(item.releasePath + "/" + item.file);
    const source = document.createElement("a");
    source.textContent = item.source;
    source.href = item.source;
    source.target = "_blank";
    source.rel = "noopener noreferrer";
    target.append(document.createElement("br"), source);
    const local = item.local || {};
    row.append(target, td(local.exists ? (local.name || item.file) + " | " + formatBytes(local.size) + " | CRC " + (local.crc || "UNKNOWN") : "MISSING"),
      td(formatBytes(item.size) + " | CRC " + item.crc), td(item.status));
    comparisons.append(row);
  }
  if (!data.files.length) {
    const row = document.createElement("tr");
    const cell = td("No files awaiting review.");
    cell.colSpan = 4;
    row.append(cell);
    body.append(row);
  }
  for (const file of data.files) {
    const row = document.createElement("tr");
    const destination = td(`${file.releasePath}/${file.file}`);
    const source = document.createElement("a");
    source.textContent = "srrDB source";
    source.href = file.source;
    source.target = "_blank";
    source.rel = "noopener noreferrer";
    const sourceLine = document.createElement("small");
    sourceLine.append(source);
    destination.append(sourceLine);
    const details = td(formatBytes(file.size));
    const crc = document.createElement("small");
    crc.textContent = `CRC32 ${file.crc}`;
    details.append(crc);
    const status = td(file.state);
    const message = document.createElement("small");
    message.textContent = file.message;
    status.append(message);
    const reviewer = document.createElement("small");
    reviewer.textContent = file.decidedBy ? `Reviewed by ${file.decidedBy}` : `Requested by ${file.requestedBy}`;
    status.append(reviewer);
    const actions = document.createElement("td");
    const buttons = document.createElement("div");
    buttons.className = "button-row";
    if (file.state === "pending" || file.state === "failed") {
      for (const action of ["accept", "reject"]) {
        const button = document.createElement("button");
        button.type = "button";
        button.textContent = action === "accept" ? (file.state === "failed" ? "Retry" : "Accept") : "Reject";
        if (action === "accept") button.className = "primary";
        button.addEventListener("click", async () => {
          if (action === "accept" && !await confirmAction("Download missing metadata", `${file.releasePath}/${file.file} (${formatBytes(file.size)})`)) return;
          button.disabled = true;
          await recoveryAction({ action, id: file.id });
        });
        buttons.append(button);
      }
    }
    actions.append(buttons);
    row.append(destination, details, status, actions);
    body.append(row);
  }
}

async function loadRecovery() {
  clearTimeout(state.recoveryTimer);
  if (!state.csrf || state.view !== "srrdb") return;
  try { renderRecovery(await api("/api/srrdb")); }
  catch (error) { showError(error); }
  if (state.csrf && state.view === "srrdb") state.recoveryTimer = setTimeout(loadRecovery, 3000);
}

async function recoveryAction(payload) {
  try {
    renderRecovery(await api("/api/srrdb", { method: "POST", body: JSON.stringify(payload) }));
  } catch (error) { showError(error); }
  await loadRecovery();
}

$("#recoveryForm").addEventListener("submit", async event => {
  event.preventDefault();
  const path = $("#recoveryPath").value.trim().replace(/\/+$/, "") || "/";
  const recursive = $("#recoveryScope").value === "tree";
  if (!await confirmAction("Start srrDB lookup", `Look up release names from ${path} on srrDB?`)) return;
  await recoveryAction({ action: "scan", path, recursive });
});
$("#refreshRecovery").addEventListener("click", loadRecovery);
$("#cancelRecovery").addEventListener("click", () => recoveryAction({ action: "cancel" }));
$("#clearRecovery").addEventListener("click", () => recoveryAction({ action: "clear" }));

document.addEventListener("visibilitychange", async () => {
  if (document.visibilityState !== "visible" || !state.csrf) return;
  try {
    const session = await api("/api/session");
    clearTimeout(state.expiryTimer);
    state.expiryTimer = setTimeout(() => showLogin(true), Math.max(0, session.expiresAt - session.serverTime));
  } catch (error) { if (state.csrf) showError(error); }
});

$("#loginForm").addEventListener("submit", async (event) => {
  event.preventDefault();
  $("#loginError").textContent = "";
  try {
    const session = await api("/api/login", {
      method: "POST",
      body: JSON.stringify({
        username: $("#username").value,
        password: $("#password").value
      })
    });
    $("#password").value = "";
    showApp(session);
  } catch (error) {
    $("#loginError").textContent = error.message;
  }
});

$("#logoutButton").addEventListener("click", async () => {
  try { await api("/api/logout", { method: "POST", body: "{}" }); } catch (_) { }
  showLogin();
});

$$(".nav-item").forEach(button => button.addEventListener("click", () => selectView(button.dataset.view)));
$("#menuButton").addEventListener("click", () => $("#sidebar").classList.toggle("open"));
$("#refreshOverview").addEventListener("click", loadOverview);
$("#refreshDashboardCommands").addEventListener("click", loadDashboardCommands);
$("#configScope").addEventListener("change", () => {
  state.config = null;
  state.configFiles = [];
  $("#configEditor").value = "";
  $("#configEditor").disabled = true;
  $("#saveConfig").disabled = true;
  $("#configPath").textContent = "Select a file";
  $("#reloadMasterConfig").disabled = $("#configScope").value !== "master";
  loadConfigFiles();
});
$("#logScope").addEventListener("change", () => { $("#logOutput").textContent = ""; loadLogFiles(); });
$("#refreshTimers").addEventListener("click", loadTimers);
$("#reloadConfigList").addEventListener("click", loadConfigFiles);
$("#configFilter").addEventListener("input", renderConfigFiles);
$("#configEditor").addEventListener("input", () => {
  $("#saveConfig").disabled = !state.config || $("#configEditor").value === state.config.content;
});
$("#saveConfig").addEventListener("click", saveConfig);
$("#reloadMasterConfig").addEventListener("click", () => runCommand("SITE RELOAD"));
$("#logFile").addEventListener("change", loadLog);
$("#logLines").addEventListener("change", loadLog);
$("#logFollow").addEventListener("change", updateLogFollow);
$("#refreshLog").addEventListener("click", loadLog);
$("#runCommand").addEventListener("click", () => runCommand());
$$("[data-command]").forEach(button => button.addEventListener("click", () => {
  $("#commandInput").value = button.dataset.command;
  $("#commandInput").focus();
}));
$("#restartButton").addEventListener("click", restartMaster);

(async () => {
  const expired = new URLSearchParams(location.search).has("expired");
  try {
    const session = await api("/api/session");
    showApp(session);
  } catch (_) {
    showLogin(expired);
  }
})();
