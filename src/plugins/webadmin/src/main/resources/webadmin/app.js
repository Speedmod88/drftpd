"use strict";

const state = {
  csrf: "",
  username: "",
  configFiles: [],
  config: null,
  logTimer: null,
  jobTimer: null,
  expiryTimer: null,
  recoveryTimer: null,
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
  try {
    const data = await api("/api/overview");
    $("#metricSlaves").textContent = `${data.onlineSlaves} / ${data.totalSlaves}`;
    $("#metricRemerging").textContent = data.remergingSlaves;
    $("#metricStorage").textContent = formatBytes(data.freeBytes);
    $("#metricHeap").textContent = `${formatBytes(data.heapUsedBytes)} / ${formatBytes(data.heapMaximumBytes)}`;
    $("#metricCpu").textContent = data.processors;
    $("#metricUptime").textContent = formatDuration(data.uptimeMillis);
    $("#slaveCount").textContent = `${data.slaves.length} configured`;
    const body = $("#slaveRows");
    body.replaceChildren();
    for (const slave of data.slaves) {
      const row = document.createElement("tr");
      row.append(td(slave.name));
      const statusCell = document.createElement("td");
      const kind = !slave.online ? "offline" : slave.remerging ? "remerging" : "online";
      const label = !slave.online ? "Offline" : slave.remerging ? "Remerging" : "Online";
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
  try {
    const data = await api("/api/config/files");
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
  try {
    const data = await api(`/api/config/file?path=${encodeURIComponent(path)}`);
    state.config = data;
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
    const response = await api("/api/config/file", {
      method: "PUT",
      body: JSON.stringify({
        path: state.config.path,
        content: $("#configEditor").value,
        version: state.config.version
      })
    });
    state.config.version = response.version;
    state.config.modified = response.modified;
    state.config.content = $("#configEditor").value;
    $("#configModified").textContent = formatTime(response.modified);
    $("#saveConfig").disabled = true;
    $("#configStatus").textContent = "Saved. A timestamped backup was created.";
    toast("Configuration saved");
  } catch (error) {
    $("#configStatus").textContent = error.message;
    showError(error);
  }
}

async function loadLogFiles() {
  try {
    const data = await api("/api/logs/files");
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
  } catch (error) { showError(error); }
}

async function loadLog() {
  const file = $("#logFile").value;
  if (!file) return;
  try {
    const lines = $("#logLines").value || 500;
    const data = await api(`/api/logs/tail?file=${encodeURIComponent(file)}&lines=${encodeURIComponent(lines)}`);
    const output = $("#logOutput");
    const atBottom = output.scrollHeight - output.scrollTop - output.clientHeight < 35;
    output.textContent = data.content;
    if (atBottom || $("#logFollow").checked) output.scrollTop = output.scrollHeight;
  } catch (error) { showError(error); }
}

function updateLogFollow() {
  clearInterval(state.logTimer);
  state.logTimer = null;
  if ($("#logFollow").checked) state.logTimer = setInterval(loadLog, 3000);
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
  clearTimeout(state.recoveryTimer);
  $$(".nav-item").forEach(button => button.classList.toggle("active", button.dataset.view === name));
  $$(".view").forEach(view => view.classList.toggle("active", view.id === `view-${name}`));
  $("#sidebar").classList.remove("open");
  if (name === "overview") loadOverview();
  if (name === "timers") loadTimers();
  if (name === "config" && state.configFiles.length === 0) loadConfigFiles();
  if (name === "logs" && $("#logFile").options.length === 0) loadLogFiles();
  if (name === "srrdb") loadRecovery();
}

function renderRecovery(data) {
  const busy = data.scanState === "queued" || data.scanState === "running";
  $("#scanRecovery").disabled = busy;
  $("#cancelRecovery").disabled = !busy;
  $("#recoveryProgress").textContent = `${data.scanState}: ${data.scanned}/${data.total} releases, ${data.notFound || 0} not found in srrDB, ${data.errors} errors. ${data.scanMessage || ""}`;
  $("#recoveryErrors").hidden = !data.scanErrors.length;
  $("#recoveryErrorText").textContent = data.scanErrors.join("\n");
  const body = $("#recoveryRows");
  body.replaceChildren();
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
