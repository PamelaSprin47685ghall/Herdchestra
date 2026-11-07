// Real browser/Sente roundtrip for the backup workspace.
//
// Starts the real gateway + backup service + storage + static client with
// temporary storage, opens the real client in headless Chrome through
// Playwright, waits for the page's actual synced badge (not an internal
// advanced-build global), and drives the real backup UI.  Every command
// crosses Sente; the assertions come from the rendered DOM after receipts.
//
// Usage:
//   NODE_PATH=/tmp/hc-pw/node_modules node test/hc/client/backup/run_sente_roundtrip.cjs

const { spawn } = require("child_process");
const fs = require("fs");
const os = require("os");
const path = require("path");

let chromium;
try {
  ({ chromium } = require("playwright"));
} catch (error) {
  console.error(
    "Playwright is required for the real-browser backup roundtrip; set " +
      "NODE_PATH to a directory containing playwright and playwright-core."
  );
  console.error(String(error));
  process.exit(2);
}

const repo = path.resolve(__dirname, "../../../..");
const chromeProfile = fs.mkdtempSync(path.join(os.tmpdir(), "hc-backup-chrome-"));
let server = null;
let browser = null;

function log(...args) {
  console.log("[backup-roundtrip]", ...args);
}

function fail(message) {
  throw new Error(message);
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function waitFor(fn, timeoutMs, label, intervalMs = 200) {
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

function startServer() {
  const child = spawn(
    "clojure",
    ["-Sdeps", '{:paths ["src" "resources" "test"]}',
     "-M:server", "-m",
     process.env.BACKUP_ROUNDTRIP_SERVER_NS ||
       "hc.client.backup.sente-roundtrip-server"],
    { cwd: repo, stdio: ["ignore", "pipe", "pipe"] }
  );
  const info = {};
  let buffer = "";
  child.stdout.on("data", (chunk) => {
    buffer += chunk.toString();
    const lines = buffer.split("\n");
    buffer = lines.pop();
    for (const line of lines) {
      const i = line.indexOf("=");
      if (i > 0 && line.startsWith("SERVER_")) {
        info[line.slice(0, i)] = line.slice(i + 1).trim();
      }
    }
  });
  child.stderr.on("data", (chunk) => {
    const text = chunk.toString();
    if (/OWNER_COMMAND|ERROR|Exception|Syntax error|Unable to resolve/.test(text)) {
      process.stderr.write(text);
    }
  });
  return { child, info };
}

async function bodyText(page) {
  return page.locator("body").innerText();
}

async function clickButton(page, name) {
  const button = page.getByRole("button", { name, exact: true }).first();
  await button.waitFor({ state: "visible", timeout: 30000 });
  await button.click();
  return button;
}

async function waitForText(page, pattern, timeoutMs, label) {
  return waitFor(async () => {
    const text = await bodyText(page);
    return pattern.test(text) ? text : null;
  }, timeoutMs, label);
}

async function fillPassword(page, ariaLabel, value) {
  const input = page.locator(`input[type="password"][aria-label="${ariaLabel}"]`).first();
  await input.waitFor({ state: "visible", timeout: 30000 });
  await input.fill(value);
  return input;
}

async function setCategory(page, fieldset, label, desired) {
  const row = fieldset.locator("label.hc-backup-category")
    .filter({ hasText: label }).first();
  const input = row.locator('input[type="checkbox"]').first();
  await input.waitFor({ state: "visible", timeout: 30000 });
  for (let attempt = 0; attempt < 4; attempt += 1) {
    if ((await input.isChecked()) === desired) return;
    // A controlled checkbox can swallow a direct input click when React
    // re-renders the fieldset.  Click the visible label text instead.
    await row.locator("strong").click();
    await sleep(250);
  }
  if ((await input.isChecked()) !== desired) {
    fail(`category ${label} did not change to ${desired}`);
  }
}

async function main() {
  const started = startServer();
  server = started.child;
  await waitFor(() => started.info.SERVER_URL, 120000, "server startup");
  const url = started.info.SERVER_URL;
  log("server", url);

  browser = await chromium.launch({
    executablePath: process.env.HC_CHROME || "/usr/bin/google-chrome",
    headless: true,
    args: ["--no-sandbox", "--disable-gpu", "--disable-dev-shm-usage"],
  });
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const page = await context.newPage();
  page.setDefaultTimeout(30000);
  page.on("pageerror", (error) => log("pageerror", String(error).slice(0, 500)));
  page.on("console", (message) => {
    if (message.type() === "error") log("console-error", message.text().slice(0, 300));
  });

  await page.goto(url + "#/settings", { waitUntil: "load" });

  // The production advanced build does not expose CLJS namespace globals.
  // Wait for the actual connection badge instead.
  await page.waitForFunction(() => {
    const badge = document.querySelector(".wb-badge");
    const boot = document.querySelector("#boot-error");
    return (badge && (badge.textContent || "").includes("已同步")) ||
           (boot && (boot.textContent || "").trim().length > 0);
  }, null, { timeout: 120000 });
  const bootText = await page.locator("#boot-error").innerText().catch(() => "");
  if (bootText && bootText.trim()) fail(`client boot failed: ${bootText.trim().slice(0, 800)}`);
  log("page shows synced badge");

  await clickButton(page, "数据中心");
  await page.waitForSelector(".hc-backup", { timeout: 30000 });
  log("backup panel mounted");

  // Wait for the target radiogroup; refresh once if the first status read was
  // still in flight.
  try {
    await page.waitForSelector('input[name="hc-backup-target"]', { timeout: 15000 });
  } catch (_) {
    const refresh = page.getByRole("button", { name: "刷新状态", exact: true });
    if (await refresh.count()) {
      await refresh.first().click();
      await page.waitForSelector('input[name="hc-backup-target"]', { timeout: 30000 });
    }
  }
  const target = page.locator('input[name="hc-backup-target"]').first();
  await target.waitFor({ state: "visible", timeout: 30000 });
  if (!(await target.isChecked())) await target.check();
  log("target selected");

  // Create only assistants + worldbooks, like the original harness.
  const createCategories = page.locator("fieldset.hc-backup-categories").first();
  const none = createCategories.getByRole("button", { name: "全不选", exact: true });
  if (await none.count()) {
    await none.first().click();
    await sleep(300);
  }
  for (const label of ["助手配置", "世界书"]) {
    await setCategory(page, createCategories, label, true);
  }
  await fillPassword(page, "备份密码", "correct horse battery staple");
  await fillPassword(page, "再次输入备份密码", "correct horse battery staple");

  const create = page.getByRole("button", { name: "生成加密备份", exact: true }).first();
  await page.waitForFunction(() => {
    const b = Array.from(document.querySelectorAll("button"))
      .find((candidate) => candidate.textContent.trim() === "生成加密备份");
    return b && !b.disabled;
  }, null, { timeout: 30000 });
  await create.click();
  await waitForText(page, /备份已生成|备份已写入|备份已创建/, 60000, "backup create result");
  log("backup created");

  // Restore preview: wrong password must be a visible error, not a silent
  // success, and the backup list must stay intact.
  await clickButton(page, "恢复与预览");
  await page.waitForSelector(".hc-backup", { timeout: 30000 });
  const choose = page.getByRole("button", { name: "选择这份备份", exact: true }).first();
  if (!(await choose.count())) {
    const refresh = page.getByRole("button", { name: "刷新列表", exact: true });
    if (await refresh.count()) await refresh.first().click();
  }
  await page.getByRole("button", { name: "选择这份备份", exact: true }).first()
    .waitFor({ state: "visible", timeout: 30000 });
  await page.getByRole("button", { name: "选择这份备份", exact: true }).first().click();
  await fillPassword(page, "恢复用备份密码", "wrong password");
  await clickButton(page, "校验并生成预览");
  await waitForText(page, /无法解锁|密码错误|无法读取/, 30000, "wrong password result");
  log("wrong password is visible");
  if (!(await bodyText(page)).includes("选择要恢复的备份")) {
    fail("wrong password removed the backup list");
  }

  // Correct preview.  The first preview may report categories that the
  // backup does not contain; narrow to assistants + worldbooks and retry.
  await fillPassword(page, "恢复用备份密码", "correct horse battery staple");
  await clickButton(page, "校验并生成预览");
  await sleep(500);
  let previewText = await bodyText(page);
  if (/类别.*(不存在|缺失)|没有可恢复|类别未选中/.test(previewText)) {
    const restoreCategories = page.locator("fieldset.hc-backup-categories").last();
    const noneRestore = restoreCategories.getByRole("button", { name: "全不选", exact: true });
    if (await noneRestore.count()) {
      await noneRestore.first().click();
      await sleep(300);
    }
    for (const label of ["助手配置", "世界书"]) {
      await setCategory(page, restoreCategories, label, true);
    }
    await clickButton(page, "校验并生成预览");
  }
  await waitForText(page, /恢复预览|合并选中类别|恢复点要求/, 30000, "restore preview");
  previewText = await bodyText(page);
  if (!/恢复点/.test(previewText)) fail("preview omitted the recovery point");
  log("preview includes merge plan and recovery point");

  // The workbench must not offer an enabled live restore; it must show the
  // offline CLI instead.
  const liveRestore = page.getByRole("button", { name: "停机并恢复", exact: true });
  if (await liveRestore.count()) {
    if (!(await liveRestore.first().isDisabled())) {
      fail("a live restore button is enabled");
    }
  }
  if (!previewText.includes("工作台内恢复不可用")) {
    fail("DOM lacks the offline restore notice");
  }
  if (!previewText.includes("hc.server.backup.cli restore")) {
    fail("DOM lacks the offline CLI command");
  }
  log("restore UX stays offline and shows the real CLI");

  const a11y = await page.evaluate(() => {
    const buttons = Array.from(document.querySelectorAll("button"));
    const password = Array.from(document.querySelectorAll('input[type="password"]'));
    const region = document.querySelector('[aria-label="加密备份与恢复"]');
    const radiogroup = document.querySelector('[role="radiogroup"][aria-label="备份目标"]');
    const unnamed = buttons.filter((button) =>
      !(button.textContent.trim() || button.getAttribute("aria-label")));
    const live = buttons.some((button) =>
      button.textContent.trim() === "停机并恢复" && !button.disabled);
    const first = buttons[0];
    const style = first ? getComputedStyle(first) : null;
    return {
      region: !!region,
      passwordInputs: password.length,
      passwordLabels: password.every((input) => !!input.getAttribute("aria-label")),
      radiogroup: !!radiogroup,
      unnamedButtons: unnamed.length,
      liveRestoreEnabled: live,
      minButtonHeight: style ? parseFloat(style.minHeight) : null,
      horizontalOverflow: document.documentElement.scrollWidth > window.innerWidth + 1,
    };
  });
  log("a11y", JSON.stringify(a11y));
  if (!a11y.region) fail("backup region lacks an accessible label");
  if (a11y.passwordInputs < 1 || !a11y.passwordLabels) {
    fail("password inputs are missing accessible labels: " + JSON.stringify(a11y));
  }
  if (!a11y.radiogroup) fail("target radiogroup lacks an accessible label");
  if (a11y.unnamedButtons > 0) fail("a button has no accessible name");
  if (a11y.liveRestoreEnabled) fail("a live restore button is enabled");
  if (!(a11y.minButtonHeight >= 44)) fail("touch target is below 44px");
  if (a11y.horizontalOverflow) fail("backup panel overflows the viewport horizontally");

  console.log(JSON.stringify({ ok: true, url, preview: previewText.slice(0, 200) }));
  console.log("BACKUP SENTE ROUNDTRIP OK");
}

async function cleanup() {
  if (browser) {
    try { await browser.close(); } catch (_) {}
  }
  if (server) {
    try { server.kill("SIGTERM"); } catch (_) {}
  }
  try { fs.rmSync(chromeProfile, { recursive: true, force: true }); } catch (_) {}
}

main()
  .then(async () => {
    await cleanup();
    process.exit(0);
  })
  .catch(async (error) => {
    console.error("BACKUP SENTE ROUNDTRIP FAILED");
    console.error(error && (error.stack || error.message || error));
    await cleanup();
    process.exit(1);
  });
