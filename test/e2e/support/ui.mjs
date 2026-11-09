// Shared Playwright helpers and evidence writers for the E2E suite.
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
export const REPO_ROOT = path.resolve(HERE, '../../..');
export const EVIDENCE_ROOT = path.join(REPO_ROOT, 'plans/evidence/e2e-auto');

export function clientBundleInfo() {
  const file = path.join(REPO_ROOT, 'resources/public/js/main.js');
  return {
    path: 'resources/public/js/main.js',
    mtime: fs.statSync(file).mtime.toISOString(),
    sha256: crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex'),
  };
}

export function evidenceDir(name) {
  const dir = path.join(EVIDENCE_ROOT, name);
  fs.mkdirSync(dir, { recursive: true });
  return dir;
}

export function writeJson(dir, name, value) {
  fs.mkdirSync(dir, { recursive: true });
  const file = path.join(dir, `${name}.json`);
  fs.writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`);
  return file;
}

export async function shot(page, dir, name, fullPage = true) {
  fs.mkdirSync(dir, { recursive: true });
  const file = path.join(dir, `${name}.png`);
  await page.screenshot({ path: file, fullPage });
  return file;
}

export async function goto(page, baseUrl, route) {
  await page.goto(`${baseUrl}/#${route}`, { waitUntil: 'load' });
  await waitForShell(page);
}

export async function waitForShell(page, timeout = 30000) {
  await page.locator('.wb-shell').first().waitFor({ state: 'visible', timeout });
}

export async function waitForSync(page, timeout = 30000) {
  await page.locator('.wb-badge[aria-label^="连接状态"]')
    .filter({ hasText: '已同步' })
    .first()
    .waitFor({ state: 'visible', timeout });
}

export async function waitForSettingsSync(page, timeout = 30000) {
  await page.locator('.hc-sync').filter({ hasText: '已同步' }).first()
    .waitFor({ state: 'visible', timeout });
}

export async function waitForSettings(page, timeout = 30000) {
  await page.locator('.hc-settings').first().waitFor({ state: 'visible', timeout });
  await waitForSettingsSync(page, timeout);
}

export function composer(page) {
  return page.locator('textarea[aria-label="消息输入"]').first();
}

export async function newSession(page) {
  const button = page.locator('button[aria-label="新建会话"]').first();
  const list = page.locator('.wb-session-list').first();
  if ((await list.count()) === 0) {
    // Some callers may create a session from a route without the session
    // list.  Keep the legacy behavior there instead of waiting on a list
    // that cannot appear.
    await button.click();
    await composer(page).waitFor({ state: 'visible', timeout: 15000 });
    return;
  }
  const beforeCount = await page.locator('.wb-session-list .wb-list-button').count();
  const beforeSelected = await page.evaluate(() => {
    const row = document.querySelector(
      '.wb-session-list .wb-list-button[aria-current="true"]');
    return row ? (row.innerText || row.textContent || '').trim() : null;
  });
  const beforeMessages = await page.locator('.hc-conversation .hc-message').count();
  await button.click();
  if (beforeMessages === 0) {
    // The current session is already blank; reuse of that blank session is a
    // valid outcome of New here, so there is nothing to switch away from.
    await composer(page).waitFor({ state: 'visible', timeout: 15000 });
    return;
  }
  // New must wait for the :session/create accepted receipt to select the
  // created/reused session.  Waiting only for the composer (always visible)
  // let E2E 02 send session B's message into session A before the receipt
  // selected the empty session.
  await page.waitForFunction(({ beforeCount, beforeSelected, beforeMessages }) => {
    const rows = [...document.querySelectorAll('.wb-session-list .wb-list-button')];
    if (rows.length > beforeCount) return true;
    const selected = rows.find((row) => row.getAttribute('aria-current') === 'true');
    const selectedText = selected
      ? (selected.innerText || selected.textContent || '').trim()
      : null;
    if (selected && selectedText !== beforeSelected) return true;
    const messages = document.querySelectorAll('.hc-conversation .hc-message').length;
    return Boolean(selected) && messages === 0 && beforeMessages > 0;
  }, { beforeCount, beforeSelected, beforeMessages }, { timeout: 30000 });
  await composer(page).waitFor({ state: 'visible', timeout: 15000 });
}

export async function selectFirstSession(page) {
  const row = page.locator('.wb-session-list .wb-list-button').first();
  await row.waitFor({ state: 'visible', timeout: 15000 });
  await row.click();
  await composer(page).waitFor({ state: 'visible', timeout: 15000 });
}

export async function send(page, text) {
  const input = composer(page);
  await input.fill(text);
  await page.locator('button[aria-label="发送消息"]').first().click();
}

export function assistantBodies(page) {
  // `:has(*)` keeps structurally empty bodies out: a `:tool-call` conversation
  // record renders as an incoming bubble whose `.hc-message-body` holds no
  // segment elements, and it must not shadow the real assistant reply.
  return page.locator('.hc-message-in:not(.hc-message-process) .hc-message-body:has(*)');
}

export function userBodies(page) {
  return page.locator('.hc-message-out .hc-message-body');
}

export async function assistantTexts(page) {
  return assistantBodies(page).evaluateAll((nodes) =>
    nodes.map((node) => node.innerText.trim()));
}

export async function userTexts(page) {
  return userBodies(page).evaluateAll((nodes) =>
    nodes.map((node) => node.innerText.trim()));
}

export async function lastAssistantText(page) {
  // `innerText` on a zero-match locator waits for the element to appear;
  // inside a Playwright test that wait is bounded only by the test timeout
  // (a fresh session has no `.hc-message-body` yet), so check the count
  // first and treat "no assistant bubble" as an empty string.
  const last = assistantBodies(page).last();
  if ((await last.count()) === 0) return '';
  return last.innerText().catch(() => '');
}

export function stopButton(page) {
  return page.locator('button[aria-label="停止当前生成"], button[aria-label="停止无人值守运行"]').first();
}

export async function turnLive(page) {
  // One atomic in-page read.  A zero-match locator.innerText() waits for the
  // element to appear and ignores .catch until the whole test timeout; the
  // same applies to hidden/duplicate stop buttons.  Do not reintroduce any
  // Playwright locator call in this function.
  return page.evaluate(() => {
    const incoming = document.querySelectorAll(
      '.hc-message-in:not(.hc-message-process)');
    const last = incoming.length ? incoming[incoming.length - 1] : null;
    const lastText = last ? (last.innerText || last.textContent || '') : '';
    if (lastText.includes('已停止')) return false;
    if ((document.body.innerText || document.body.textContent || '')
        .includes('当前轮仍在生成')) return true;
    const buttons = document.querySelectorAll(
      'button[aria-label="停止当前生成"], button[aria-label="停止无人值守运行"]');
    for (const button of buttons) {
      const visible = button.offsetParent !== null
        || button.getClientRects().length > 0;
      if (!visible) continue;
      const aria = button.getAttribute('aria-label') || '';
      const text = (button.innerText || button.textContent || '').trim();
      if (aria === '停止无人值守运行'
          || /正在生成|正在停止|停止无人值守运行/.test(text)) {
        return true;
      }
    }
    if (last) {
      const status = last.querySelector('.hc-message-status');
      const statusText = status ? (status.innerText || status.textContent || '') : '';
      if (/正在生成|生成中/.test(statusText)) return true;
    }
    return false;
  });
}

export async function waitForAssistant(page, options = {}) {
  const {
    timeout = 120000,
    minChars = 1,
    quietMs = 3000,
    onTick = null,
  } = options;
  const started = Date.now();
  let last = '';
  let stableSince = null;
  while (Date.now() - started < timeout) {
    await page.waitForTimeout(250);
    const text = (await lastAssistantText(page)).trim();
    if (onTick) await onTick(text);
    if (text.length >= minChars) {
      if (text === last) {
        if (stableSince === null) stableSince = Date.now();
        if (Date.now() - stableSince >= quietMs) {
          return { text, elapsedMs: Date.now() - started, live: await turnLive(page) };
        }
      } else {
        last = text;
        stableSince = null;
      }
    } else {
      last = text;
      stableSince = null;
    }
  }
  throw new Error(`assistant reply did not settle within ${timeout} ms (last: ${last.slice(0, 120)})`);
}

export async function ensureIdle(page, timeout = 30000) {
  const started = Date.now();
  while (Date.now() - started < timeout) {
    if (!(await turnLive(page))) return true;
    const stop = stopButton(page);
    if (await stop.count()) {
      const visible = await stop.isVisible().catch(() => false);
      if (visible) await stop.click({ timeout: 1500 }).catch(() => {});
    }
    await page.waitForTimeout(1000);
  }
  return !(await turnLive(page));
}

export function assert(condition, message) {
  if (!condition) throw new Error(`ASSERTION FAILED: ${message}`);
}

export function assertEqual(actual, expected, message) {
  if (actual !== expected) {
    throw new Error(`ASSERTION FAILED: ${message}\n  expected: ${JSON.stringify(expected)}\n  actual:   ${JSON.stringify(actual)}`);
  }
}

export async function settingsAddLocalConnection(page, options) {
  const {
    name,
    baseUrl,
    modelId,
    location = 'instance',
  } = options;
  await page.getByRole('button', { name: '供应商与连接', exact: true }).first().click();
  await page.getByRole('button', { name: '新建本地模型服务连接', exact: true }).first().click();
  const nameField = page.locator('.hc-field', { hasText: '显示名称' }).locator('input').first();
  await nameField.fill(name);
  const urlField = page.locator('.hc-field', { hasText: '基础地址' }).locator('input').first();
  await urlField.fill(baseUrl);
  const locationSelect = page.locator('.hc-local select').first();
  await locationSelect.selectOption(location);
  const modelInput = page.locator('.hc-manual-model input').first();
  await modelInput.fill(modelId);
  await page.waitForTimeout(250);
  assertEqual(await modelInput.inputValue(), modelId, 'manual model input did not accept the id');
  await page.getByRole('button', { name: '加入模型', exact: true }).first().click();
  await page.getByRole('button', { name: '测试当前配置', exact: true }).first().click();
  await page.locator('.hc-test-layers').first().waitFor({ state: 'visible', timeout: 30000 });
  await page.locator('.hc-test-layers tr', { hasText: '地址可达' })
    .filter({ hasText: '通过' }).first().waitFor({ state: 'visible', timeout: 30000 });
  await page.locator('.hc-test-layers tr', { hasText: '模型可列出' })
    .filter({ hasText: '通过' }).first().waitFor({ state: 'visible', timeout: 30000 });
  await page.getByRole('button', { name: '保存连接', exact: true }).first().click();
  await page.getByText('连接已保存', { exact: false }).first().waitFor({ state: 'visible', timeout: 30000 });
  await page.locator('.hc-connection-card').filter({ hasText: name }).first()
    .waitFor({ state: 'visible', timeout: 30000 });
}

export async function settingsEnableConfiguredConnection(page, options) {
  const { name } = options;
  await page.getByRole('button', { name: '供应商与连接', exact: true }).first().click();
  const card = page.locator('.hc-connection-card').filter({ hasText: name }).first();
  await card.waitFor({ state: 'visible', timeout: 30000 });
  await card.getByRole('button', { name: '编辑', exact: true }).first().click();
  const editor = page.locator('section.hc-panel').filter({ hasText: '编辑连接：' }).first();
  await editor.waitFor({ state: 'visible', timeout: 30000 });
  const enable = editor.getByRole('button', { name: '启用此连接', exact: true }).first();
  if (await enable.count()) await enable.click();
  const location = editor.locator('.hc-local select').first();
  if (await location.count()) await location.selectOption('instance');
  await editor.getByRole('button', { name: '测试当前配置', exact: true }).first().click();
  await page.locator('.hc-test-layers').first().waitFor({ state: 'visible', timeout: 30000 });
  for (const layer of ['地址可达', '模型可列出', '最小请求完成']) {
    await page.locator('.hc-test-layers tr', { hasText: layer })
      .filter({ hasText: '通过' }).first().waitFor({ state: 'visible', timeout: 30000 });
  }
  const conclusion = await page.locator('.hc-test-conclusion').first().innerText();
  assert(conclusion.includes('可以生成'), `connection test did not prove generation: ${conclusion}`);
  await editor.getByRole('button', { name: '保存连接', exact: true }).first().click();
  await page.getByText('连接已保存', { exact: false }).first()
    .waitFor({ state: 'visible', timeout: 30000 });
  await card.waitFor({ state: 'visible', timeout: 30000 });
}

export async function dismissSettingsMessages(page) {
  const closeButtons = page.locator('.hc-message')
    .getByRole('button', { name: '关闭', exact: true });
  const count = await closeButtons.count();
  for (let index = 0; index < count; index++) {
    const button = closeButtons.nth(index);
    if (await button.isVisible().catch(() => false)) {
      await button.click({ timeout: 1500 }).catch(() => {});
    }
  }
}

function isConnectionSaveReceipt(text) {
  return text.includes('连接已保存')
    && (text.includes('草稿已清理')
        || text.includes('服务器已保存提交版本'));
}

async function waitForNewConnectionSaveReceipt(page, beforeCount,
                                               timeout = 45000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    const texts = await page.locator('.hc-message').allInnerTexts();
    const receipts = texts.filter(isConnectionSaveReceipt);
    if (receipts.length > beforeCount) return receipts[receipts.length - 1];
    await page.waitForTimeout(250);
  }
  throw new Error('connection save did not produce a new receipt');
}

export async function settingsEnableModelAndBindMain(page, options) {
  const { modelId, connectionName, declareTools = false } = options;
  await page.getByRole('button', { name: '模型与角色', exact: true }).first().click();
  const group = page.locator('.hc-model-group').filter({ hasText: connectionName }).first();
  await group.waitFor({ state: 'visible', timeout: 30000 });
  await group.getByRole('button', { name: '编辑连接', exact: true }).first().click();
  await page.locator('section.hc-panel').filter({ hasText: '编辑连接：' }).first()
    .waitFor({ state: 'visible', timeout: 15000 });
  await page.getByRole('button', { name: '模型与角色', exact: true }).first().click();
  await page.getByRole('heading', { name: '模型选择' }).first()
    .waitFor({ state: 'visible', timeout: 15000 });
  await page.waitForTimeout(500);
  const row = page.locator('.hc-model-row').filter({ hasText: modelId }).first();
  await row.waitFor({ state: 'visible', timeout: 30000 });

  // Enable the model first.  Capability declarations are changes to the same
  // connection draft, so one save below commits both instead of racing two
  // separate saves and reading the first save's stale banner.
  let needsSave = false;
  const checkbox = row.locator('input[type="checkbox"]').first();
  if (await checkbox.count()) {
    if (!(await checkbox.isChecked())) {
      await checkbox.check();
      needsSave = true;
    }
  }

  let toolsDeclared = false;
  if (declareTools) {
    // A typed model has unknown capabilities.  Declaring tool support is an
    // explicit operator choice; the Plan role needs it for its program tool.
    // The capability editor is already visible when the connection draft is
    // selected; only fall back to the "编辑能力" button when it is not.
    let editor = row.locator('.hc-capability-editor').first();
    if ((await editor.count()) === 0) {
      const editCapabilities = row.getByRole('button', { name: '编辑能力', exact: true }).first();
      if (await editCapabilities.count()) {
        await editCapabilities.click();
      }
      editor = row.locator('.hc-capability-editor').first();
    }
    await editor.waitFor({ state: 'visible', timeout: 10000 });
    const toolSelect = editor.getByLabel('工具能力');
    await toolSelect.selectOption('known');
    let toolValue = await toolSelect.inputValue();
    // React applies the controlled <select> value on the next render; read
    // it back with a short bounded poll instead of trusting the immediate
    // pre-render DOM value.
    for (let attempt = 0;
         toolValue !== 'known' && attempt < 20;
         attempt++) {
      await page.waitForTimeout(100);
      toolValue = await toolSelect.inputValue();
    }
    if (toolValue !== 'known') {
      throw new Error(`工具能力 did not switch to known: ${toolValue}`);
    }
    toolsDeclared = true;
    needsSave = true;
  }

  if (needsSave) {
    // Close the old banner first; otherwise this spec's receipt matcher sees
    // the first connection save and proves nothing about this save.
    await dismissSettingsMessages(page);
    const beforeCount = (await page.locator('.hc-message').allInnerTexts())
      .filter(isConnectionSaveReceipt).length;
    const saveButton = row.getByRole('button', { name: '保存连接', exact: true }).first();
    await saveButton.waitFor({ state: 'visible', timeout: 15000 });
    if (await saveButton.isDisabled()) {
      throw new Error('保存连接 is disabled; refusing to treat the draft badge as saved');
    }
    await saveButton.click();
    await waitForNewConnectionSaveReceipt(page, beforeCount);
    if (declareTools) {
      // The receipt clears the draft and the capability editor goes away.
      await row.locator('.hc-capability-editor').first()
        .waitFor({ state: 'hidden', timeout: 15000 });
    }
  }

  await row.getByRole('button', { name: '选为当前角色', exact: true }).first().click();
  const saveRole = page.getByRole('button', { name: '保存角色绑定', exact: true }).first();
  await saveRole.waitFor({ state: 'visible', timeout: 15000 });
  await saveRole.click();
  await page.getByText('角色绑定已保存', { exact: false }).first().waitFor({ state: 'visible', timeout: 30000 });
  // Saving the role can re-render the settings pane; the old row locator is
  // stale.  Re-locate the manual model before switching the current session.
  const currentRow = page.locator('.hc-model-row').filter({ hasText: modelId }).first();
  await currentRow.waitFor({ state: 'visible', timeout: 30000 });
  const switchCurrent = currentRow.getByRole('button', { name: '用于下一轮', exact: true }).first();
  let switchStatus = 'absent';
  if (await switchCurrent.count()) {
    if (await switchCurrent.isDisabled()) {
      switchStatus = 'disabled';
    } else {
      await switchCurrent.click();
      await page.getByText('模型切换已接受', { exact: false }).first()
        .waitFor({ state: 'visible', timeout: 30000 });
      switchStatus = 'clicked';
    }
  }
  const body = await page.locator('body').innerText();
  assert(body.includes(connectionName), `settings lost connection identity ${connectionName}`);
  const rowText = await currentRow.innerText();
  return {
    switchStatus,
    toolsDeclared: toolsDeclared || rowText.includes('工具：已声明支持'),
    rowText,
  };
}

export async function sessionModelLabel(page) {
  return (await page.locator('button[aria-label="打开设置选择模型"]').first()
    .innerText().catch(() => '')).trim();
}
