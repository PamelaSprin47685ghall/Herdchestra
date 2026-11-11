// E2E 19: keyboard navigation, 200% font scale, IME composition, focus return,
// the Escape hierarchy, the accessibility tree and light/dark contrast.
//
// Real screen readers, real touch hardware and a real input method are not
// available to this harness; they are recorded as `manualResidual` instead of
// being claimed as automated coverage.
import { test } from '@playwright/test';
import { startApp } from './support/app.mjs';
import { LONG_TEXT, startMockModel } from './support/mock-model.mjs';
import { checksOk, recordEvidence } from './support/evidence.mjs';
import {
  assert, composer, evidenceDir, goto, newSession, selectFirstSession,
  shot, turnLive, userTexts, waitForSettings, waitForSync,
} from './support/ui.mjs';
import {
  ariaSnapshot, axButtonNames, axLandmarkNames, axRoleCount, axTree,
  collectContrast, collectFocusSequence, imeCompose, imeCommit,
  imePressEnterSynthetic,
  measureTargets, tabOrderMatchesVisual, targetsOk, waitForSettledTarget,
} from './support/a11y.mjs';

const EVIDENCE_NAME = 'e2e19';
const MARKER = `E2E19-${Date.now()}`;

const CONTRAST_SPECS = [
  { name: 'userBody', selector: '.hc-message-out .hc-message-body', kind: 'text' },
  { name: 'assistantBody', selector: '.hc-message-in:not(.hc-message-process) .hc-message-body', kind: 'text' },
  { name: 'composerHint', selector: '.hc-composer-hint', kind: 'text' },
  { name: 'messageMeta', selector: '.hc-message-meta', kind: 'text' },
  { name: 'sendLabel', selector: 'button[aria-label="发送消息"]', kind: 'text' },
  { name: 'primaryButtonGraphic', selector: 'button[aria-label="发送消息"]', kind: 'graphic' },
];

const SEND_SELECTOR = 'button[aria-label="发送消息"]';
const STOP_SELECTOR = 'button[aria-label="停止当前生成"], button[aria-label="停止无人值守运行"]';
const MENU_SELECTOR = '#wb-menu';
const AUX_CLOSE_SELECTOR = '#wb-aux-close';

function contrastMeasured(rows) {
  return rows.filter((row) => row.found && !row.skipped);
}

const REQUIRED_CONTRAST = ['userBody', 'assistantBody', 'sendLabel', 'primaryButtonGraphic'];

function contrastPass(rows, requiredNames = []) {
  const byName = Object.fromEntries(rows.map((row) => [row.name, row]));
  const requiredOk = requiredNames.every((name) => {
    const row = byName[name];
    return Boolean(row && row.found === true && !row.skipped && row.ok === true);
  });
  const measured = contrastMeasured(rows);
  return requiredOk && measured.length > 0 && measured.every((row) => row.ok);
}

function skippedContrast(rows) {
  return rows
    .filter((row) => row.skipped)
    .map((row) => ({ name: row.name, selector: row.selector, reason: row.skipped }));
}

async function openAppearance(page, baseUrl) {
  await goto(page, baseUrl, '/settings');
  await waitForSettings(page);
  const tab = page.getByRole('button', { name: '外观', exact: true }).first();
  await tab.waitFor({ state: 'visible', timeout: 20000 });
  await tab.click();
  await page.locator('#hc-appearance-font-scale').first()
    .waitFor({ state: 'visible', timeout: 20000 });
}

async function setFontScale(page, value) {
  await page.evaluate((next) => {
    const input = document.querySelector('#hc-appearance-font-scale');
    if (!input) throw new Error('#hc-appearance-font-scale is missing');
    // React tracks the last value it wrote; going through the native setter
    // makes the synthetic `input` event look like a user slider drag.
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
    setter.call(input, String(next));
    input.dispatchEvent(new Event('input', { bubbles: true }));
  }, value);
  await page.waitForFunction((expected) =>
    document.documentElement.style.getPropertyValue('--wb-font-scale') === expected,
  String(value / 100), { timeout: 5000 });
  await page.waitForFunction((expected) => {
    const input = document.querySelector('#hc-appearance-font-scale');
    return Boolean(input && input.getAttribute('aria-valuenow') === String(expected));
  }, value, { timeout: 5000 });
}

async function setTheme(page, label) {
  const button = page.getByRole('button', { name: label, exact: true }).first();
  await button.waitFor({ state: 'visible', timeout: 20000 });
  await button.click();
  const theme = label === '浅色' ? 'light' : 'dark';
  await page.waitForFunction((expected) =>
    document.documentElement.dataset.theme === expected,
  theme, { timeout: 5000 });
}

// Route inside the live SPA.  A full reload would drop an appearance that
// the settings page has previewed but not persisted while its server-save
// gate is closed; the a11y checks only need the in-memory appearance.
async function showSessions(page) {
  await page.evaluate(() => { window.location.hash = '#/sessions'; });
  await page.locator('.wb-shell').first().waitFor({ state: 'visible', timeout: 30000 });
  await selectFirstSession(page);
  await composer(page).waitFor({ state: 'visible', timeout: 20000 });
  await page.waitForFunction(() =>
    document.querySelectorAll('.hc-message-out .hc-message-body').length > 0
    && document.querySelectorAll('.hc-message-in:not(.hc-message-process) .hc-message-body').length > 0,
  { timeout: 30000 });
}

// Wait for the responsive band to actually settle.  The menu button is
// hidden at >=1024px, and the wide layout auto-opens the details column;
// those two facts are stronger than a fixed sleep after setViewportSize.
async function waitForLayout(page, overlay) {
  await page.waitForFunction((expectedOverlay) => {
    const menu = document.getElementById('wb-menu');
    const aux = document.getElementById('wb-aux');
    if (!menu || !aux) return false;
    const menuVisible = getComputedStyle(menu).display !== 'none';
    return expectedOverlay
      ? menuVisible && aux.dataset.open === 'false'
      : !menuVisible && aux.dataset.open === 'true';
  }, overlay, { timeout: 10000 });
}

async function activeElement(page) {
  return page.evaluate(() => {
    const element = document.activeElement;
    if (!element) return { id: null, tag: 'NONE', insideNav: false, insideAux: false };
    const drawer = document.getElementById('wb-nav-drawer');
    const aux = document.getElementById('wb-aux');
    return {
      id: element.id || null,
      tag: element.tagName,
      insideNav: Boolean(drawer && (drawer === element || drawer.contains(element))),
      insideAux: Boolean(aux && (aux === element || aux.contains(element))),
    };
  });
}

async function waitForAux(page, open) {
  await page.waitForFunction((expected) => {
    const aux = document.getElementById('wb-aux');
    return Boolean(aux && aux.dataset.open === String(expected));
  }, open, { timeout: 10000 });
}

async function waitForNav(page, open) {
  await page.waitForFunction((expected) => {
    const nav = document.getElementById('wb-nav-drawer');
    return Boolean(nav && nav.dataset.open === String(expected));
  }, open, { timeout: 10000 });
}

async function sendLongReply(page, text) {
  await composer(page).fill(text);
  const sendButton = page.locator(SEND_SELECTOR).first();
  const enabledSince = Date.now();
  let enabled = false;
  while (Date.now() - enabledSince < 30000) {
    enabled = await sendButton.isEnabled()
      && (await sendButton.getAttribute('aria-disabled')) !== 'true';
    if (enabled) break;
    await page.waitForTimeout(200);
  }
  if (!enabled) return false;
  await sendButton.click();
  const started = Date.now();
  while (Date.now() - started < 20000) {
    if (await turnLive(page)) return true;
    await page.waitForTimeout(200);
  }
  return false;
}

async function waitUntilIdle(page, timeout = 30000) {
  const started = Date.now();
  while (Date.now() - started < timeout) {
    if (!(await turnLive(page))) return true;
    await page.waitForTimeout(300);
  }
  return false;
}

// The send button reflects the rendered send intent.  Waiting for it to
// enable is the cheapest way to know the composer state has caught up with
// the DOM, without depending on any particular frame timing.
async function waitForSendReady(page, timeout = 20000) {
  const ok = await page.waitForFunction(() => {
    const button = document.querySelector('button[aria-label="发送消息"]');
    return Boolean(button && !button.disabled
      && button.getAttribute('aria-disabled') !== 'true');
  }, null, { timeout }).then(() => true, () => false);
  assert(ok, 'send button never became enabled');
}

// Wait for THIS turn's assistant reply, not whichever assistant body happens
// to be stable.  The user bubble must carry the turn's unique text, and an
// assistant bubble after that user bubble must carry the mock reply marker.
async function waitForTurnReply(page, userText, replyMarker = 'E2E19-MOCK',
                                timeout = 120000) {
  return page.waitForFunction(({ userNeedle, replyNeedle }) => {
    const log = document.querySelector('[aria-label="会话消息"]');
    if (!log) return false;
    const nodes = [...log.querySelectorAll(
      '.hc-message-out, .hc-message-in:not(.hc-message-process)')];
    let lastUser = -1;
    nodes.forEach((node, index) => {
      if (node.matches('.hc-message-out')
          && (node.innerText || '').includes(userNeedle)) {
        lastUser = index;
      }
    });
    if (lastUser < 0) return false;
    return nodes.slice(lastUser + 1).some((node) =>
      node.matches('.hc-message-in:not(.hc-message-process)')
      && (node.innerText || '').includes(replyNeedle));
  }, { userNeedle: userText, replyNeedle: replyMarker }, { timeout })
    .then(() => true)
    .catch(() => false);
}

// Snapshot used only after a turn-scoped wait fails: connection badge,
// composer, sync adapter state, and the visible bubble tails.
async function collectTurnDiagnostics(page) {
  const sync = await page.evaluate(() =>
    (typeof window.__HC_SYNC__ === 'function' ? window.__HC_SYNC__() : null))
    .catch(() => null);
  return page.evaluate((syncState) => {
    const badge = document.querySelector('.wb-badge');
    const syncButton = document.querySelector('button[aria-label^="连接状态"]');
    const input = document.querySelector('textarea[aria-label="消息输入"]');
    const users = [...document.querySelectorAll(
      '.hc-message-out .hc-message-body')]
      .map((el) => (el.innerText || '').slice(0, 300));
    const assistants = [...document.querySelectorAll(
      '.hc-message-in:not(.hc-message-process) .hc-message-body:has(*)')]
      .map((el) => (el.innerText || '').slice(0, 300));
    return {
      topbarSyncText: (badge?.innerText || '').trim(),
      topbarAria: syncButton?.getAttribute('aria-label') || '',
      composerValue: input?.value || '',
      pendingCommandStatus: syncState,
      userBubbles: users.slice(-6),
      assistantBubbles: assistants.slice(-6),
    };
  }, sync).catch((error) => ({
    diagnosticsError: String(error).slice(0, 300),
  }));
}

test('@core @a11y E2E 19 keyboard, 200% text, IME, focus return and AX tree', async ({ page, browser }) => {
  test.setTimeout(600000);
  const dir = evidenceDir(EVIDENCE_NAME);
  const startedAt = new Date().toISOString();
  const checks = {};
  const diagnostics = {};
  const evidence = {
    test: 'E2E 19',
    mode: 'automated',
    marker: MARKER,
    checks,
    diagnostics,
    contrast: { light: [], dark: [] },
    manualResidual: {
      screenReaderReadingOrder: 'not automated',
      realTouchHardware: 'not automated',
      realImeHardware: 'not automated',
    },
    screenshots: [],
  };

  const mock = await startMockModel({
    reply: `E2E19-MOCK ${MARKER}`,
    // The script's last rule is the fallback: without it, a non-matching
    // message would pick the long rule by call index.
    replyScript: [
      { match: '长回复', reply: LONG_TEXT },
      { reply: `E2E19-MOCK ${MARKER}` },
    ],
    // A slow chunk stream keeps the stop button and the layered Escape test
    // deterministic without waiting minutes for the full reply.
    chunkSize: 8,
    chunkDelayMs: 50,
  });
  let app = null;
  let cdp = null;
  let result = null;

  try {
    app = await startApp({
      evidenceDir: dir,
      models: {
        connections: [{
          id: 'mock',
          name: 'Mock E2E19',
          baseUrl: `${mock.url}/v1`,
          // The Plan/Reviewer roles require the model to declare its program
          // tool; without this capability the send is refused before a user
          // message is created (observed in the first real run).
          models: [{ id: 'mock-fast', displayName: 'Mock Fast', capabilities: ['tools'] }],
        }],
        roles: { main: { connection: 'mock', model: 'mock-fast' } },
      },
    });
    await goto(page, app.url, '/sessions');
    await waitForSync(page);
    await newSession(page);

    // --- Keyboard baseline -------------------------------------------------
    // Enter must be tested as a *keyboard* affordance, not as a race against
    // the connection gate.  A fresh data directory reuses a pre-created blank
    // session, so `newSession` returns before any `:session/create` receipt
    // can arrive; pressing Enter in that window is refused locally as
    // `:connection-not-ready` and the message is silently dropped (draft kept,
    // no bubble).  E2E 08 gates on the send button for the same reason.  Wait
    // for the gate first, then prove Enter — and not the button — sends.
    const first = `键盘发送 ${MARKER}`;
    await composer(page).fill(first);
    await waitForSendReady(page, 40000);
    const usersBeforeEnter = (await userTexts(page)).length;
    await composer(page).press('Enter');
    try {
      await page.waitForFunction((count) =>
        document.querySelectorAll('.hc-message-out .hc-message-body').length > count,
      usersBeforeEnter, { timeout: 20000 });
    } catch (error) {
      // The old code only collected diagnostics after the count assertion, so
      // a timeout here produced an empty `diagnostics` object and left no clue
      // which gate refused the send.  Collect first, then rethrow.
      diagnostics.enterTimeout = await collectTurnDiagnostics(page).catch(() => null);
      throw error;
    }
    const enterReply = await waitForTurnReply(page, first);
    const enterUsers = await userTexts(page);
    checks.enterSends = enterReply
      && enterUsers.length === usersBeforeEnter + 1
      && enterUsers.filter((text) => text.includes(first)).length === 1;
    if (!checks.enterSends) diagnostics.enter = await collectTurnDiagnostics(page);
    assert(checks.enterSends, 'Enter did not send exactly one message');
    await waitUntilIdle(page, 30000);

    await composer(page).fill('第一行');
    const usersBeforeShift = (await userTexts(page)).length;
    await composer(page).press('Shift+Enter');
    await page.waitForFunction(() => {
      const input = document.querySelector('textarea[aria-label="消息输入"]');
      return Boolean(input && input.value.endsWith('\n'));
    }, { timeout: 5000 }).catch(() => {});
    await page.keyboard.insertText('第二行');
    const shifted = await composer(page).inputValue();
    checks.shiftEnterNewlineOnly = shifted === '第一行\n第二行'
      && (await userTexts(page)).length === usersBeforeShift;
    assert(checks.shiftEnterNewlineOnly, `Shift+Enter did not stay a newline: ${JSON.stringify(shifted)}`);
    await composer(page).fill('');

    const repeatText = `连按只发一条 ${MARKER}`;
    await composer(page).fill(repeatText);
    // The textarea is uncontrolled: `fill` updates the DOM at once but the
    // rendered send intent lags one frame.  Pressing Enter inside that window
    // used to be dropped silently.  The product now falls back to the DOM
    // value, and this wait keeps the spec from depending on either behaviour.
    await waitForSendReady(page);
    const usersBeforeRepeat = (await userTexts(page)).length;
    await composer(page).press('Enter');
    await composer(page).press('Enter');
    const repeatReply = await waitForTurnReply(page, repeatText);
    const repeatUsers = await userTexts(page);
    checks.keyRepeatSendsOnce = repeatReply
      && repeatUsers.length === usersBeforeRepeat + 1
      && repeatUsers.filter((text) => text.includes(repeatText)).length === 1;
    if (!checks.keyRepeatSendsOnce) {
      diagnostics.keyRepeat = await collectTurnDiagnostics(page);
    }
    assert(checks.keyRepeatSendsOnce, `rapid Enter produced ${repeatUsers.length - usersBeforeRepeat} messages`);
    await waitUntilIdle(page, 30000);

    const doubleText = `双击只发一条 ${MARKER}`;
    await composer(page).fill(doubleText);
    await waitForSendReady(page);
    const usersBeforeDouble = (await userTexts(page)).length;
    await page.locator(SEND_SELECTOR).first().dblclick();
    const doubleReply = await waitForTurnReply(page, doubleText);
    const doubleUsers = await userTexts(page);
    checks.doubleClickSendsOnce = doubleReply
      && doubleUsers.length === usersBeforeDouble + 1
      && doubleUsers.filter((text) => text.includes(doubleText)).length === 1;
    if (!checks.doubleClickSendsOnce) {
      diagnostics.doubleClick = await collectTurnDiagnostics(page);
    }
    assert(checks.doubleClickSendsOnce, `double click produced ${doubleUsers.length - usersBeforeDouble} messages`);
    await waitUntilIdle(page, 30000);

    const actionNames = await page.locator('.hc-message-actions button').evaluateAll((nodes) =>
      nodes
        .filter((node) => node.offsetParent !== null)
        .map((node) => ({
          text: (node.innerText || '').trim(),
          label: node.getAttribute('aria-label') || '',
        })));
    evidence.messageActionNames = actionNames;
    checks.messageActionsHaveNames = actionNames.length > 0
      && actionNames.every((row) => row.text.length > 0 || row.label.length > 0);
    assert(checks.messageActionsHaveNames, `unnamed message actions: ${JSON.stringify(actionNames)}`);

    // The send button is disabled for an empty draft, and a disabled button
    // is not a Tab stop; seed the composer so the focus-ring probe lands on
    // the real send control.
    await composer(page).fill('焦点环');
    await composer(page).focus();
    await page.keyboard.press('Tab');
    const focusRing = await page.evaluate(() => {
      const element = document.activeElement;
      const style = getComputedStyle(element);
      return {
        id: element.id || null,
        label: element.getAttribute('aria-label') || '',
        tag: element.tagName,
        outlineWidth: parseFloat(style.outlineWidth) || 0,
        outlineStyle: style.outlineStyle,
        focusVisible: element.matches(':focus-visible'),
      };
    });
    evidence.focusRing = focusRing;
    checks.focusRingAtLeast2px = focusRing.focusVisible
      && focusRing.outlineStyle !== 'none'
      && focusRing.outlineWidth >= 2;
    assert(checks.focusRingAtLeast2px, `focus ring is too thin: ${JSON.stringify(focusRing)}`);

    const focusStops = await collectFocusSequence(page, { limit: 14 });
    const order = tabOrderMatchesVisual(focusStops);
    const usableFocusStops = focusStops.filter((stop) => stop.rect && stop.tag !== 'BODY');
    evidence.focusSequence = focusStops;
    evidence.focusOrderViolations = order.violations;
    evidence.focusStopCount = usableFocusStops.length;
    checks.tabOrderHasStops = usableFocusStops.length >= 6;
    checks.tabOrderMatchesVisual = order.ok && checks.tabOrderHasStops;
    assert(checks.tabOrderHasStops,
      `the focus sequence collected only ${usableFocusStops.length} usable stops`);
    assert(checks.tabOrderMatchesVisual, `tab order moved backwards: ${JSON.stringify(order.violations)}`);
    evidence.screenshots.push(await shot(page, dir, 'e2e19-01-keyboard'));

    // --- Contrast at 100% in both themes --------------------------------
    await openAppearance(page, app.url);
    await setTheme(page, '浅色');
    await showSessions(page);
    evidence.contrast.light = await collectContrast(page, CONTRAST_SPECS);
    evidence.contrast.lightSkipped = skippedContrast(evidence.contrast.light);
    checks.contrastLight = contrastPass(evidence.contrast.light, REQUIRED_CONTRAST);
    assert(checks.contrastLight,
      `light contrast failed: ${JSON.stringify(evidence.contrast.light.filter((row) => row.found && !row.skipped && !row.ok))}`);

    await openAppearance(page, app.url);
    await setTheme(page, '深色');
    await showSessions(page);
    evidence.contrast.dark = await collectContrast(page, CONTRAST_SPECS);
    evidence.contrast.darkSkipped = skippedContrast(evidence.contrast.dark);
    checks.contrastDark = contrastPass(evidence.contrast.dark, REQUIRED_CONTRAST);
    assert(checks.contrastDark,
      `dark contrast failed: ${JSON.stringify(evidence.contrast.dark.filter((row) => row.found && !row.skipped && !row.ok))}`);
    evidence.screenshots.push(await shot(page, dir, 'e2e19-02-dark-contrast'));

    // --- 200% font scale -------------------------------------------------
    await openAppearance(page, app.url);
    await setTheme(page, '浅色');
    await setFontScale(page, 200);
    const scaleInfo = await page.evaluate(() => {
      const input = document.querySelector('#hc-appearance-font-scale');
      return {
        variable: document.documentElement.style.getPropertyValue('--wb-font-scale'),
        htmlFontSize: getComputedStyle(document.documentElement).fontSize,
        ariaValueNow: input ? input.getAttribute('aria-valuenow') : null,
      };
    });
    evidence.fontScale = scaleInfo;
    checks.fontScaleApplies = scaleInfo.variable === '2' && scaleInfo.htmlFontSize === '32px';
    checks.fontScaleAria = scaleInfo.ariaValueNow === '200';
    assert(checks.fontScaleApplies, `200% scale did not apply: ${JSON.stringify(scaleInfo)}`);
    assert(checks.fontScaleAria, `aria-valuenow is not 200: ${JSON.stringify(scaleInfo)}`);
    evidence.screenshots.push(await shot(page, dir, 'e2e19-03-font-200-settings'));

    await showSessions(page);
    const persistedScale = await page.evaluate(() => ({
      variable: document.documentElement.style.getPropertyValue('--wb-font-scale'),
      htmlFontSize: getComputedStyle(document.documentElement).fontSize,
    }));
    checks.fontScaleSurvivesRoute = persistedScale.variable === '2'
      && persistedScale.htmlFontSize === '32px';
    assert(checks.fontScaleSurvivesRoute,
      `200% scale did not survive the in-app route: ${JSON.stringify(persistedScale)}`);

    // --- 200% at 360: message height, hot zones, clipping -----------------
    await page.setViewportSize({ width: 360, height: 800 });
    await waitForLayout(page, true);
    const longStarted = await sendLongReply(page, `长回复 手机 200% ${MARKER}`);
    checks.longReplyStartedMobile = longStarted;
    assert(longStarted, 'the long reply did not start at 360px');
    const messagesHeight = await page.locator('.hc-conversation-messages').first()
      .evaluate((node) => node.getBoundingClientRect().height);
    evidence.mobileMessagesHeight = Number(messagesHeight.toFixed(1));
    checks.messageAreaAtLeast150 = messagesHeight >= 150;
    assert(checks.messageAreaAtLeast150, `message area collapsed to ${messagesHeight}px at 200%`);

    const mobileTargets = await measureTargets(page, [
      { name: 'send', selector: SEND_SELECTOR },
      { name: 'stop', selector: STOP_SELECTOR },
      { name: 'menu', selector: MENU_SELECTOR },
    ]);
    // The topbar toggle is the stable entry at both drawer widths; the
    // session-header opener is exercised by the focus-return phase below.
    await page.locator('#wb-aux-toggle').first().click();
    evidence.mobileAuxOpenedBy = 'wb-aux-toggle';
    await waitForAux(page, true);
    await page.locator(AUX_CLOSE_SELECTOR).first().waitFor({ state: 'visible', timeout: 5000 });
    await waitForSettledTarget(page, AUX_CLOSE_SELECTOR);
    const closeTarget = await measureTargets(page, [{ name: 'auxClose', selector: AUX_CLOSE_SELECTOR }]);
    const mobileAll = [...mobileTargets, ...closeTarget];
    evidence.mobileTargets = mobileAll;
    const mobileByName = Object.fromEntries(mobileAll.map((row) => [row.name, row]));
    checks.mobileKeyTargetsVisible = ['send', 'stop', 'menu', 'auxClose']
      .every((name) => mobileByName[name] && mobileByName[name].visible === true);
    checks.mobileTargetsNoClip = targetsOk(mobileTargets);
    checks.mobileHotZones44 = targetsOk(mobileAll, { minSize: 44 });
    assert(checks.mobileKeyTargetsVisible,
      `a required mobile target is missing: ${JSON.stringify(mobileAll.map((row) => [row.name, row.found, row.visible, row.skipped]))}`);
    assert(checks.mobileTargetsNoClip,
      `a mobile target is clipped or outside the viewport: ${JSON.stringify(mobileTargets.filter((row) => row.visible && !row.ok))}`);
    assert(checks.mobileHotZones44,
      `a mobile target is under 44x44: ${JSON.stringify(mobileAll.filter((row) => row.visible && (row.width < 44 || row.height < 44)))}`);
    evidence.screenshots.push(await shot(page, dir, 'e2e19-04-mobile-200'));

    await page.locator(AUX_CLOSE_SELECTOR).first().click();
    await waitForAux(page, false);
    await waitUntilIdle(page, 30000);

    // --- 200% at 820: tablet drawer band --------------------------------
    await page.setViewportSize({ width: 820, height: 900 });
    await waitForLayout(page, true);
    const tabletStarted = await sendLongReply(page, `长回复 平板 200% ${MARKER}`);
    checks.longReplyStartedTablet = tabletStarted;
    assert(tabletStarted, 'the long reply did not start at 820px');
    const tabletMessagesHeight = await page.locator('.hc-conversation-messages').first()
      .evaluate((node) => node.getBoundingClientRect().height);
    evidence.tabletMessagesHeight = Number(tabletMessagesHeight.toFixed(1));
    checks.tabletMessageAreaAtLeast150 = tabletMessagesHeight >= 150;
    assert(checks.tabletMessageAreaAtLeast150,
      `message area collapsed to ${tabletMessagesHeight}px at 820px / 200%`);
    const tabletTargets = await measureTargets(page, [
      { name: 'send', selector: SEND_SELECTOR },
      { name: 'stop', selector: STOP_SELECTOR },
      { name: 'menu', selector: MENU_SELECTOR },
    ]);
    await page.locator('#wb-aux-toggle').first().click();
    await waitForAux(page, true);
    await page.locator(AUX_CLOSE_SELECTOR).first().waitFor({ state: 'visible', timeout: 5000 });
    await waitForSettledTarget(page, AUX_CLOSE_SELECTOR);
    const tabletClose = await measureTargets(page, [{ name: 'auxClose', selector: AUX_CLOSE_SELECTOR }]);
    const tabletAll = [...tabletTargets, ...tabletClose];
    evidence.tabletTargets = tabletAll;
    const tabletByName = Object.fromEntries(tabletAll.map((row) => [row.name, row]));
    checks.tabletKeyTargetsVisible = ['send', 'stop', 'menu', 'auxClose']
      .every((name) => tabletByName[name] && tabletByName[name].visible === true);
    checks.tabletTargetsNoClip = targetsOk(tabletTargets);
    checks.tabletHotZones44 = targetsOk(tabletAll, { minSize: 44 });
    assert(checks.tabletKeyTargetsVisible,
      `a required tablet target is missing: ${JSON.stringify(tabletAll.map((row) => [row.name, row.found, row.visible, row.skipped]))}`);
    assert(checks.tabletTargetsNoClip,
      `a tablet target is clipped: ${JSON.stringify(tabletTargets.filter((row) => row.visible && !row.ok))}`);
    assert(checks.tabletHotZones44,
      `a tablet target is under 44x44: ${JSON.stringify(tabletAll.filter((row) => row.visible && (row.width < 44 || row.height < 44)))}`);
    evidence.screenshots.push(await shot(page, dir, 'e2e19-05a-tablet-200'));
    await page.locator(AUX_CLOSE_SELECTOR).first().click();
    await waitForAux(page, false);
    await waitUntilIdle(page, 30000);

    // --- Desktop 1440: compact targets and stop/send/close ----------------
    await page.setViewportSize({ width: 1440, height: 1000 });
    await waitForLayout(page, false);
    await waitForSettledTarget(page, AUX_CLOSE_SELECTOR);
    const desktopStarted = await sendLongReply(page, `长回复 桌面 ${MARKER}`);
    checks.longReplyStartedDesktop = desktopStarted;
    assert(desktopStarted, 'the long reply did not start at 1440px');
    const desktopMessagesHeight = await page.locator('.hc-conversation-messages').first()
      .evaluate((node) => node.getBoundingClientRect().height);
    evidence.desktopMessagesHeight = Number(desktopMessagesHeight.toFixed(1));
    checks.desktopMessageAreaAtLeast150 = desktopMessagesHeight >= 150;
    assert(checks.desktopMessageAreaAtLeast150,
      `message area collapsed to ${desktopMessagesHeight}px at 1440px / 200%`);
    const desktopTargets = await measureTargets(page, [
      { name: 'send', selector: SEND_SELECTOR },
      { name: 'stop', selector: STOP_SELECTOR },
      { name: 'auxClose', selector: AUX_CLOSE_SELECTOR },
      { name: 'menu', selector: MENU_SELECTOR },
    ]);
    const compactTargets = await page.locator('.hc-message-actions .hc-button').evaluateAll((nodes) =>
      nodes
        .filter((node) => node.offsetParent !== null)
        .map((node) => {
          const rect = node.getBoundingClientRect();
          return {
            text: (node.innerText || '').trim(),
            width: Number(rect.width.toFixed(1)),
            height: Number(rect.height.toFixed(1)),
          };
        }));
    evidence.desktopTargets = desktopTargets;
    evidence.compactTargets = compactTargets;
    const desktopByName = Object.fromEntries(desktopTargets.map((row) => [row.name, row]));
    checks.desktopKeyTargetsVisible = ['send', 'stop', 'auxClose']
      .every((name) => desktopByName[name] && desktopByName[name].visible === true);
    checks.desktopTargetsInViewport = desktopTargets
      .filter((row) => row.visible && row.name !== 'menu')
      .every((row) => row.ok);
    checks.desktopCompactAtLeast32 = compactTargets.length > 0
      && compactTargets.every((row) => row.width >= 32 && row.height >= 32);
    const desktopMenuRows = desktopTargets.filter((row) => row.name === 'menu');
    checks.desktopMenuHiddenByDesign = desktopMenuRows.length === 1
      && desktopMenuRows[0].found === true
      && desktopMenuRows[0].visible === false;
    assert(checks.desktopKeyTargetsVisible,
      `a required desktop target is missing: ${JSON.stringify(desktopTargets.map((row) => [row.name, row.found, row.visible, row.skipped]))}`);
    assert(checks.desktopTargetsInViewport,
      `a desktop target is clipped: ${JSON.stringify(desktopTargets.filter((row) => row.visible && !row.ok))}`);
    assert(checks.desktopCompactAtLeast32,
      `a compact button is under 32x32: ${JSON.stringify(compactTargets.filter((row) => row.width < 32 || row.height < 32))}`);
    assert(checks.desktopMenuHiddenByDesign, 'the desktop menu trigger is still shown');
    evidence.screenshots.push(await shot(page, dir, 'e2e19-05-desktop-200'));
    await waitUntilIdle(page, 30000);

    // --- IME: composing Enter never sends --------------------------------
    await waitUntilIdle(page, 30000);
    await composer(page).fill('');
    await composer(page).focus();
    await page.bringToFront();
    cdp = await page.context().newCDPSession(page);
    const beforeImeUsers = await userTexts(page);
    const beforeImeState = await (await fetch(`${mock.url}/__mock/state`)).json();
    await imeCompose(cdp, '组合');
    await page.waitForFunction(() => {
      const input = document.querySelector('textarea[aria-label="消息输入"]');
      return Boolean(input && input.value.includes('组合'));
    }, { timeout: 5000 }).catch(() => {});
    // The negative invariant must use an event that actually carries
    // isComposing=true.  CDP's dispatchKeyEvent cannot set that flag, so the
    // synthetic KeyboardEvent is the deterministic path; the CDP compose
    // above remains the positive check that composition text enters the box.
    const imeEnter = await imePressEnterSynthetic(page);
    // A negative assertion ("no send") needs a bounded settling window; the
    // positive evidence is the unchanged user/request counts below.
    await page.waitForTimeout(500);
    const afterImeUsers = await userTexts(page);
    const afterImeState = await (await fetch(`${mock.url}/__mock/state`)).json();
    const afterImeValue = await composer(page).inputValue();
    evidence.ime = {
      beforeRequests: beforeImeState.chatCompletions,
      afterRequests: afterImeState.chatCompletions,
      value: afterImeValue,
      userMessagesBefore: beforeImeUsers.length,
      userMessagesAfter: afterImeUsers.length,
      defaultPrevented: imeEnter.defaultPrevented,
    };
    checks.imeEnterDoesNotSend = afterImeUsers.length === beforeImeUsers.length
      && afterImeState.chatCompletions === beforeImeState.chatCompletions;
    checks.imeTextKept = afterImeValue.includes('组合');
    checks.imeDefaultNotPrevented = imeEnter.defaultPrevented === false;
    checks.imeNoComposedRequest = !(afterImeState.log || [])
      .some((entry) => String(entry.user || '').includes('组合'));
    assert(checks.imeEnterDoesNotSend,
      `composing Enter sent a message: ${JSON.stringify(evidence.ime)}`);
    assert(checks.imeTextKept, `composing Enter truncated the text: ${afterImeValue}`);
    assert(checks.imeDefaultNotPrevented,
      'composition Enter must not preventDefault; the IME owns the key');
    assert(checks.imeNoComposedRequest, 'a model request carried the uncommitted composition text');

    await imeCommit(cdp);
    await page.evaluate(() => { if (document.activeElement) document.activeElement.blur(); });
    const normalText = `正常发送 ${MARKER}`;
    await composer(page).fill(normalText);
    const usersBeforeNormal = (await userTexts(page)).length;
    await composer(page).press('Enter');
    const normalReply = await waitForTurnReply(page, normalText);
    const composerCleared = await page.waitForFunction(() => {
      const el = document.querySelector('textarea[aria-label="消息输入"]');
      return Boolean(el && el.value === '');
    }, null, { timeout: 10000 }).then(() => true).catch(() => false);
    const normalUsers = await userTexts(page);
    checks.normalEnterStillSends = normalReply
      && composerCleared
      && normalUsers.length === usersBeforeNormal + 1
      && normalUsers.filter((text) => text.includes(normalText)).length === 1;
    if (!checks.normalEnterStillSends) {
      Object.assign(diagnostics, await collectTurnDiagnostics(page));
    }
    assert(checks.normalEnterStillSends, 'a normal Enter no longer sends after composition');
    await waitUntilIdle(page, 30000);
    evidence.screenshots.push(await shot(page, dir, 'e2e19-06-ime'));

    // --- Focus return and the three-level Escape hierarchy ----------------
    await page.setViewportSize({ width: 800, height: 700 });
    await waitForLayout(page, true);
    if (await page.locator('#wb-aux[data-open="true"]').count()) {
      await page.locator(AUX_CLOSE_SELECTOR).first().click();
      await waitForAux(page, false);
    }

    await page.locator(MENU_SELECTOR).first().click();
    await waitForNav(page, true);
    await page.waitForFunction(() => {
      const nav = document.getElementById('wb-nav-drawer');
      return Boolean(nav && (nav === document.activeElement || nav.contains(document.activeElement)));
    }, { timeout: 5000 }).catch(() => {});
    const navFocus = await activeElement(page);
    evidence.navFocus = navFocus;
    checks.drawerFocusEntersNav = navFocus.insideNav;
    assert(checks.drawerFocusEntersNav, `focus did not enter the nav drawer: ${JSON.stringify(navFocus)}`);
    await page.keyboard.press('Escape');
    await waitForNav(page, false);
    await page.waitForFunction(() =>
      Boolean(document.activeElement && document.activeElement.id === 'wb-menu'),
    { timeout: 5000 }).catch(() => {});
    const navReturn = await activeElement(page);
    checks.drawerFocusReturnsToMenu = navReturn.id === 'wb-menu';
    assert(checks.drawerFocusReturnsToMenu, `focus did not return to #wb-menu: ${JSON.stringify(navReturn)}`);

    const auxOpener = page.locator('#hc-header-action-open-aux').first();
    await auxOpener.click();
    await waitForAux(page, true);
    await page.waitForFunction(() =>
      Boolean(document.activeElement && document.activeElement.id === 'wb-aux-close'),
    { timeout: 5000 }).catch(() => {});
    const auxFocus = await activeElement(page);
    checks.auxFocusEntersClose = auxFocus.id === 'wb-aux-close';
    assert(checks.auxFocusEntersClose, `focus did not enter #wb-aux-close: ${JSON.stringify(auxFocus)}`);
    await page.keyboard.press('Escape');
    await waitForAux(page, false);
    await page.waitForFunction(() =>
      Boolean(document.activeElement && document.activeElement.id === 'hc-header-action-open-aux'),
    { timeout: 5000 }).catch(() => {});
    const auxReturn = await activeElement(page);
    checks.auxFocusReturnsToOpener = auxReturn.id === 'hc-header-action-open-aux';
    assert(checks.auxFocusReturnsToOpener, `focus did not return to the aux opener: ${JSON.stringify(auxReturn)}`);

    const layeredStarted = await sendLongReply(page, `长回复 焦点层级 ${MARKER}`);
    checks.layeredReplyStarted = layeredStarted;
    assert(layeredStarted, 'the layered Escape reply did not start');
    await auxOpener.click();
    await waitForAux(page, true);
    await page.keyboard.press('Escape');
    await waitForAux(page, false);
    const stillLiveAfterFirstEscape = await turnLive(page);
    checks.firstEscapeClosesOnlyOverlay = stillLiveAfterFirstEscape;
    assert(checks.firstEscapeClosesOnlyOverlay, 'the first Escape stopped generation instead of closing the panel');
    const liveBeforeSecondEscape = await turnLive(page);
    checks.secondEscapeHadLiveStream = liveBeforeSecondEscape;
    assert(checks.secondEscapeHadLiveStream, 'the long reply ended before the second Escape');
    await page.keyboard.press('Escape');
    const stopped = await waitUntilIdle(page, 30000);
    checks.secondEscapeStopsGeneration = liveBeforeSecondEscape && stopped;
    assert(checks.secondEscapeStopsGeneration, 'the second Escape did not stop generation');
    evidence.screenshots.push(await shot(page, dir, 'e2e19-07-escape-levels'));

    // --- Accessibility tree ----------------------------------------------
    await page.setViewportSize({ width: 1440, height: 1000 });
    await waitForLayout(page, false);
    if (await page.locator('#wb-aux[data-open="true"]').count()) {
      await page.locator(AUX_CLOSE_SELECTOR).first().click();
      await waitForAux(page, false);
      await page.waitForFunction(() => {
        const aux = document.getElementById('wb-aux');
        return Boolean(aux && getComputedStyle(aux).display === 'none');
      }, { timeout: 5000 }).catch(() => {});
    }
    cdp = cdp || await page.context().newCDPSession(page);
    const tree = await axTree(cdp);
    const navCount = axRoleCount(tree, 'navigation');
    const mainCount = axRoleCount(tree, 'main');
    const names = axButtonNames(tree);
    evidence.ax = {
      navigation: navCount,
      main: mainCount,
      landmarks: {
        navigation: axLandmarkNames(tree, 'navigation'),
        main: axLandmarkNames(tree, 'main'),
      },
      buttonNames: names,
    };
    checks.axExactlyOneNavigation = navCount === 1;
    checks.axExactlyOneMain = mainCount === 1;
    checks.axKeyButtonsNamed = ['发送消息', '新建会话', '添加附件'].every((name) => names.includes(name));
    assert(checks.axExactlyOneNavigation, `navigation landmarks: ${navCount}`);
    assert(checks.axExactlyOneMain, `main landmarks: ${mainCount}`);
    assert(checks.axKeyButtonsNamed, `missing readable button names in ${JSON.stringify(names)}`);

    const snapshot = await ariaSnapshot(page.locator('.wb-shell').first());
    evidence.ariaSnapshotChars = snapshot.length;
    checks.ariaSnapshotAvailable = snapshot.length > 0;
    assert(checks.ariaSnapshotAvailable, 'ariaSnapshot returned nothing');
    evidence.screenshots.push(await shot(page, dir, 'e2e19-08-ax-tree'));

    evidence.ok = checksOk(checks);
    assert(evidence.ok, `failed checks: ${JSON.stringify(checks)}`);
    result = recordEvidence({
      dir,
      name: EVIDENCE_NAME,
      test: evidence.test,
      mode: evidence.mode,
      startedAt,
      extra: {
        marker: evidence.marker,
        appUrl: app ? app.url : null,
        mockUrl: mock.url,
        chromeVersion: browser.version(),
        fontScale: evidence.fontScale,
        mobileMessagesHeight: evidence.mobileMessagesHeight,
        tabletMessagesHeight: evidence.tabletMessagesHeight,
        desktopMessagesHeight: evidence.desktopMessagesHeight,
        mobileTargets: evidence.mobileTargets,
        tabletTargets: evidence.tabletTargets,
        desktopTargets: evidence.desktopTargets,
        compactTargets: evidence.compactTargets,
        contrast: evidence.contrast,
        ime: evidence.ime,
        diagnostics,
        focusSequence: evidence.focusSequence,
        focusOrderViolations: evidence.focusOrderViolations,
        focusStopCount: evidence.focusStopCount,
        focusRing: evidence.focusRing,
        messageActionNames: evidence.messageActionNames,
        ax: evidence.ax,
        ariaSnapshotChars: evidence.ariaSnapshotChars,
        manualResidual: evidence.manualResidual,
        screenshots: evidence.screenshots,
      },
      checks,
      ok: evidence.ok,
    });
    evidence.evidence = result.evidence;
  } catch (error) {
    evidence.error = error.message;
    const failed = recordEvidence({
      dir,
      name: EVIDENCE_NAME,
      test: evidence.test,
      mode: evidence.mode,
      startedAt,
      extra: {
        marker: evidence.marker,
        appUrl: app ? app.url : null,
        mockUrl: mock.url,
        chromeVersion: browser.version(),
        fontScale: evidence.fontScale,
        mobileMessagesHeight: evidence.mobileMessagesHeight,
        tabletMessagesHeight: evidence.tabletMessagesHeight,
        desktopMessagesHeight: evidence.desktopMessagesHeight,
        mobileTargets: evidence.mobileTargets,
        tabletTargets: evidence.tabletTargets,
        desktopTargets: evidence.desktopTargets,
        compactTargets: evidence.compactTargets,
        contrast: evidence.contrast,
        ime: evidence.ime,
        diagnostics,
        focusSequence: evidence.focusSequence,
        focusOrderViolations: evidence.focusOrderViolations,
        focusStopCount: evidence.focusStopCount,
        focusRing: evidence.focusRing,
        messageActionNames: evidence.messageActionNames,
        ax: evidence.ax,
        ariaSnapshotChars: evidence.ariaSnapshotChars,
        manualResidual: evidence.manualResidual,
        screenshots: evidence.screenshots,
        error: error.message,
      },
      checks,
      ok: false,
    });
    evidence.evidence = failed.evidence;
    throw error;
  } finally {
    if (app) {
      try { await app.close(); } catch { /* shutdown noise must not hide the result */ }
    }
    await mock.close().catch(() => {});
  }
});
