// Real supervised maintenance roundtrip for the backup workbench.
//
// Starts the full application on a fixed port with temporary storage, opens
// the real client in headless Chrome over CDP, creates and previews a backup,
// requests a supervised stop/restore/restart, waits for the HTTP/Sente drop,
// reconnects, and reads the terminal maintenance status from the journal.
//
// Usage: node test/hc/client/backup/run_sente_supervised_roundtrip.cjs

const { spawn } = require("child_process");
const fs = require("fs");
const os = require("os");
const path = require("path");
const WebSocket = require("ws");

const repo = path.resolve(__dirname, "../../../..");
const port = Number(process.env.BACKUP_SUPERVISED_PORT || 17891);
const chromePort = 9800 + Math.floor(Math.random() * 100);
const root = fs.mkdtempSync(path.join(os.tmpdir(), "hc-backup-supervised-"));
const chromeProfile = fs.mkdtempSync(path.join(os.tmpdir(), "hc-backup-chrome-"));
const dataDir = path.join(root, "data");
const workspace = path.join(root, "workspace");
const staticRoot = path.join(repo, "resources/public");
let app = null;
let chrome = null;
let cdp = null;

function log(...args) {
  console.log("[backup-supervised]", ...args);
}
function fail(message) {
  throw new Error(message);
}
function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
async function waitFor(fn, timeoutMs, label, intervalMs = 250) {
  const deadline = Date.now() + timeoutMs;
  let last = null;
  while (Date.now() < deadline) {
    try {
      const value = await fn();
      if (value) return value;
      last = value;
    } catch (error) {
      last = error;
    }
    await sleep(intervalMs);
  }
  fail(`${label} timed out; last=${last && (last.stack || last.message || last)}`);
}

function startApp() {
  const child = spawn(
    "clojure",
    ["-Sdeps", '{:paths ["src" "resources" "test"]}',
     "-M:server", "-m", "hc.server.app",
     "--host", "127.0.0.1",
     "--port", String(port),
     "--data-dir", dataDir,
     "--workspace-root", workspace,
     "--static-root", staticRoot,
     "--log-level", "warn"],
    { cwd: repo, stdio: ["ignore", "pipe", "pipe"] }
  );
  let buffer = "";
  let url = null;
  child.stdout.on("data", (chunk) => {
    buffer += chunk.toString();
    const lines = buffer.split("\n");
    buffer = lines.pop();
    for (const line of lines) {
      if (line.startsWith("Herdchestra listening on ")) {
        url = line.slice("Herdchestra listening on ".length).trim();
      }
    }
  });
  child.stderr.on("data", (chunk) => {
    const text = chunk.toString();
    if (/ERROR|Exception|Syntax error|Unable to resolve/.test(text)) {
      process.stderr.write(text);
    }
  });
  return { child, url: () => url };
}

function connectCdp(wsUrl) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(wsUrl);
    let nextId = 0;
    const pending = new Map();
    ws.on("open", () => {
      resolve({
        send(method, params = {}) {
          return new Promise((res, rej) => {
            const id = ++nextId;
            pending.set(id, { res, rej });
            ws.send(JSON.stringify({ id, method, params }));
          });
        },
        close() { ws.close(); },
      });
    });
    ws.on("message", (raw) => {
      const message = JSON.parse(raw.toString());
      if (message.id && pending.has(message.id)) {
        const { res, rej } = pending.get(message.id);
        pending.delete(message.id);
        if (message.error) rej(new Error(JSON.stringify(message.error)));
        else res(message.result);
      }
    });
    ws.on("error", reject);
  });
}

async function evaluate(expression, awaitPromise = false) {
  const result = await cdp.send("Runtime.evaluate", {
    expression,
    awaitPromise,
    returnByValue: true,
    userGesture: true,
  });
  if (result.exceptionDetails) {
    fail(`page exception: ${JSON.stringify(result.exceptionDetails)}`);
  }
  return result.result.value;
}

const kw = (name, ns) =>
  ns
    ? `cljs.core.keyword(${JSON.stringify(ns)},${JSON.stringify(name)})`
    : `cljs.core.keyword(${JSON.stringify(name)})`;

function action(type, pairs = []) {
  const parts = [kw("type"), JSON.stringify(type)];
  for (const [key, value] of pairs) parts.push(kw(key), value);
  return `cljs.core.array_map(${parts.join(",")})`;
}
async function dispatch(type, pairs = []) {
  await evaluate(`hc.client.backup.bridge.dispatch_BANG_(${action(type, pairs)}); true`);
}

async function selectDataCenter() {
  const selected = await evaluate(`(function(){
    const tab = Array.from(document.querySelectorAll('.hc-tabs button'))
      .find(function(button){ return button.textContent.trim() === "数据中心"; });
    if (!tab) return false;
    tab.click();
    return true;
  })()`);
  if (!selected) fail("data center tab is missing");
  await waitFor(async () => evaluate("!!document.querySelector('.hc-backup')"),
                10000, "backup panel after data center");
}

const stateExpression = `(function(){
  const kw = cljs.core.keyword;
  const app = hc.client.workbench.app.current_app();
  const db = com.fulcrologic.fulcro.application.current_state(app);
  const backup = cljs.core.get(db, kw("ui", "backup"));
  return cljs.core.pr_str(backup);
})()`;

async function state() {
  return evaluate(stateExpression);
}

async function waitState(re, timeoutMs, label) {
  return waitFor(async () => {
    const text = await state();
    return re.test(text) ? text : null;
  }, timeoutMs, label);
}

const backupIdExpression = `(function(){
  const kw = cljs.core.keyword;
  const app = hc.client.workbench.app.current_app();
  const db = com.fulcrologic.fulcro.application.current_state(app);
  const backup = cljs.core.get(db, kw("ui", "backup"));
  const server = cljs.core.get(backup, kw("server"));
  const rows = cljs.core.get(server, kw("backups"));
  const first = cljs.core.first(rows);
  return first ? cljs.core.get(first, kw("id")) : null;
})()`;

const maintenanceIdExpression = `(function(){
  const kw = cljs.core.keyword;
  const app = hc.client.workbench.app.current_app();
  const db = com.fulcrologic.fulcro.application.current_state(app);
  const backup = cljs.core.get(db, kw("ui", "backup"));
  const ui = cljs.core.get(backup, kw("ui"));
  const request = cljs.core.get(ui, kw("maintenance-request"));
  return request
    ? (cljs.core.get(request, kw("maintenance-id"))
       || cljs.core.get(request, kw("id")))
    : null;
})()`;

async function main() {
  const started = startApp();
  app = started.child;
  await waitFor(() => started.url(), 180000, "full app startup");
  const base = started.url();
  log("app", base);

  chrome = spawn(
    "google-chrome",
    ["--headless=new", "--no-sandbox", "--disable-gpu", "--disable-dev-shm-usage",
     "--hide-scrollbars", "--window-size=1280,900", "--remote-allow-origins=*",
     `--remote-debugging-port=${chromePort}`, `--user-data-dir=${chromeProfile}`,
     "--no-first-run", "about:blank"],
    { stdio: "ignore" }
  );
  await waitFor(async () => {
    try {
      const response = await fetch(`http://127.0.0.1:${chromePort}/json/version`);
      return response.ok;
    } catch (_) {
      return false;
    }
  }, 30000, "chrome debugging port");

  const target = await fetch(
    `http://127.0.0.1:${chromePort}/json/new?${encodeURIComponent("about:blank")}`,
    { method: "PUT" }
  ).then((response) => response.json());
  cdp = await connectCdp(target.webSocketDebuggerUrl);
  await cdp.send("Runtime.enable");
  await cdp.send("Page.enable");
  await cdp.send("Page.navigate", { url: base + "#/settings" });
  await waitFor(async () => evaluate("document.readyState === 'complete'"),
                30000, "document ready");
  await waitFor(async () => evaluate("!!document.querySelector('.wb-shell')"),
                60000, "workbench shell");
  await waitFor(async () => evaluate(`(function(){
    const el = document.querySelector('.wb-topbar [aria-label^="连接状态"]');
    return !!(el && el.getAttribute("aria-label").includes("已同步"));
  })()`), 120000, "topbar synced");

  try {
    await waitFor(async () => evaluate(`(function(){
      try {
        return !!(window.hc && hc.client && hc.client.workbench &&
                  hc.client.workbench.sync_bridge &&
                  hc.client.workbench.sync_bridge.command_allowed_QMARK_());
      } catch (e) { return false; }
    })()`), 120000, "Sente sync");
  } catch (error) {
    const debug = await evaluate(`(function(){
      try {
        const info = {
          cljs: typeof cljs,
          hc: typeof hc,
          body: document.body.innerText.slice(0, 500)
        };
        if (typeof cljs === "undefined" || typeof hc === "undefined") {
          return JSON.stringify(info);
        }
        const kw = cljs.core.keyword;
        const sb = hc.client.workbench.sync_bridge;
        return cljs.core.pr_str(cljs.core.array_map(
          kw("synced?"), sb.synced_QMARK_(),
          kw("allowed?"), sb.command_allowed_QMARK_(),
          kw("connection"), sb.connection(),
          kw("body"), info.body));
      } catch (e) { return "sync-debug-failed: " + e; }
    })()`);
    log("sync debug", debug);
    throw error;
  }
  log("sync allows commands");
  await selectDataCenter();

  try {
    await waitState(/:backup\/status-loaded/, 20000, "initial status");
  } catch (_) {
    // Automatic mount/refresh is silent now; only an explicit operator
    // refresh writes the status-loaded notice this marker waits for.
    await dispatch("backup/refresh", [["origin", kw("manual")]]);
    await waitState(/:backup\/status-loaded/, 20000, "status after refresh");
  }
  const initial = await state();
  if (!initial.includes(":available? true") || !initial.includes(":supervised")) {
    fail("server did not declare supervised maintenance: " + initial.slice(0, 800));
  }
  log("supervised maintenance available");

  await dispatch("backup/select-section", [["section", kw("restore")]]);
  await dispatch("backup/set-field", [["field", JSON.stringify("password")],
                                      ["value", JSON.stringify("correct horse battery staple")]]);
  await dispatch("backup/set-field", [["field", JSON.stringify("password-confirm")],
                                      ["value", JSON.stringify("correct horse battery staple")]]);
  await dispatch("backup/create");
  await waitState(/:BACKUP_CREATED|:BACKUP_NEEDS_VERIFICATION/, 30000, "create receipt");
  const id = await waitFor(async () => {
    const value = await evaluate(backupIdExpression);
    return value || null;
  }, 20000, "backup id");
  log("created backup", id);

  await dispatch("backup/select-backup", [["backup-id", JSON.stringify(id)]]);
  await dispatch("backup/set-field", [["field", JSON.stringify("password")],
                                      ["value", JSON.stringify("correct horse battery staple")]]);
  await dispatch("backup/preview");
  await waitState(/:preview \{|:BACKUP_PREVIEW_READY|:BACKUP_NO_CHANGES/,
                  30000, "preview receipt");
  const preview = await state();
  if (!preview.includes(":fingerprint")) fail("preview receipt lacks fingerprint");
  log("preview includes fingerprint");

  await dispatch("backup/toggle-restore-confirm");
  await dispatch("backup/request-maintenance-restore");
  await sleep(1000);
  const requestedState = await evaluate(`(function(){
    const kw = cljs.core.keyword;
    const app = hc.client.workbench.app.current_app();
    const db = com.fulcrologic.fulcro.application.current_state(app);
    const backup = cljs.core.get(db, kw("ui", "backup"));
    const ui = cljs.core.get(backup, kw("ui"));
    const server = cljs.core.get(backup, kw("server"));
    return cljs.core.pr_str(cljs.core.array_map(
      kw("maintenance-request"), cljs.core.get(ui, kw("maintenance-request")),
      kw("message"), cljs.core.get(ui, kw("message")),
      kw("preview?"), cljs.core.contains_QMARK_(ui, kw("preview")),
      kw("restore-confirm?"), cljs.core.get(ui, kw("restore-confirm?")),
      kw("selected-backup"), cljs.core.get(ui, kw("selected-backup-id")),
      kw("server-maintenance"), cljs.core.get(server, kw("maintenance")),
      kw("commands"), cljs.core.get(backup, kw("commands")),
      kw("sync"), cljs.core.get(backup, kw("sync"))));
  })()`);
  log("state after maintenance request", requestedState);
  const maintenanceId = await waitFor(async () => {
    const value = await evaluate(maintenanceIdExpression);
    return value || null;
  }, 20000, "maintenance id");
  log("maintenance requested", maintenanceId);

  // The server intentionally drops HTTP/Sente.  Poll through the drop, then
  // use maintenance-status after reconnect as the only terminal authority.
  const deadline = Date.now() + 180000;
  let terminal = null;
  let lastStatus = "";
  let lastLog = 0;
  while (Date.now() < deadline) {
    const text = await state();
    lastStatus = text;
    const terminalStatus = text.includes(":status :complete")
        || text.includes(":status :restart-failed")
        || text.includes(":status :failed-needs-attention")
        || text.includes(":status :failed")
        || text.includes(":status :stop-failed");
    const synced = await evaluate(`(function(){
      try { return hc.client.workbench.sync_bridge.command_allowed_QMARK_(); }
      catch (e) { return false; }
    })()`);
    if (terminalStatus && text.includes(":observed-by-client? true")) {
      terminal = text;
      break;
    }
    if (Date.now() - lastLog > 10000) {
      lastLog = Date.now();
      log("poll", JSON.stringify({
        synced: synced,
        terminalStatus: terminalStatus,
        observed: text.includes(":observed-by-client? true"),
        status: (text.match(/:status :[a-z-]+/) || ["?"])[0]
      }));
    }
    if (synced) {
      await dispatch("backup/refresh-maintenance",
                     [["maintenance-id", JSON.stringify(maintenanceId)]]);
    }
    await sleep(1000);
  }
  if (!terminal) fail("maintenance terminal status timed out: " + lastStatus.slice(0, 1200));
  log("terminal status", terminal.slice(0, 2000));
  if (!terminal.includes(":status :complete")) {
    fail("supervised maintenance did not complete: " + terminal.slice(0, 1200));
  }
  if (!terminal.includes(":observed-by-client? true")) {
    fail("terminal status was not observed by the client");
  }
  log("supervised maintenance complete and observed");

  // The page must still show the backup panel after reconnect.
  await selectDataCenter();
  const text = await evaluate("document.body.innerText");
  if (!text.includes("加密备份与恢复")) fail("backup panel missing after restart");
  console.log("BACKUP SUPERVISED ROUNDTRIP OK");
}

async function cleanup() {
  if (cdp) { try { cdp.close(); } catch (_) {} }
  if (chrome) { try { chrome.kill("SIGKILL"); } catch (_) {} }
  if (app) { try { app.kill("SIGTERM"); } catch (_) {} }
  try { fs.rmSync(root, { recursive: true, force: true }); } catch (_) {}
  try { fs.rmSync(chromeProfile, { recursive: true, force: true }); } catch (_) {}
}

main()
  .then(async () => { await cleanup(); process.exit(0); })
  .catch(async (error) => {
    console.error("BACKUP SUPERVISED ROUNDTRIP FAILED");
    console.error(error && (error.stack || error.message || error));
    await cleanup();
    process.exit(1);
  });
