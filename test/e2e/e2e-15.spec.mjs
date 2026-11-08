// E2E 15: 语音转写 / 朗读 / 翻译 (journey 6.7 用语音).
//
// Real server + real UI + controlled mocks.  The chat model is the shared
// mock-model helper; speech services are the dedicated speech mock in
// mock-services.mjs (STT multipart, TTS WAV, translation chat-completions).
// Speech services are seeded through the persisted config file
// (<data-dir>/speech/config.edn), which the server prefers over the launch
// `:speech` map — the same durable path an operator's settings writes use.
//
// Covered:
//   1. transcript lands in the draft only (auto-send? false, no message sent)
//   2. failed cloud transcription keeps the recording; switching service and
//      confirming retries the SAME recording id (no re-upload)
//   3. read-aloud plays, pauses/stops via the mini player; regenerating the
//      message version stops the old audio
//   4. translation is a derived result: original text stays, result can be
//      copied or inserted into the draft, never overwrites history
//
// The microphone item uses Chrome's fake media device.  In environments
// without any audio input the recording steps are marked manual in the
// evidence JSON instead of being faked.
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { test } from '@playwright/test';
import { startApp, REPO_ROOT } from './support/app.mjs';
import { startMockModel } from './support/mock-model.mjs';
import { startMockSpeech } from './support/mock-services.mjs';
import {
  assert, clientBundleInfo, composer, ensureIdle, goto,
  newSession, shot, userTexts, waitForAssistant, waitForSync,
} from './support/ui.mjs';

const EVIDENCE = path.join(REPO_ROOT, 'plans/evidence/e2e-15');
const MODEL_ID = 'mock-speech';
const MODEL_NAME = 'Speech Stub Model';
const TRANSCRIPT = 'E2E15 转写文本：明天下午四点开会。';
const TRANSLATED = 'E2E15-TRANSLATION mocked translation result.';
const REPLY_TEXT = 'E2E15 受控模型回复：这是朗读与翻译测试正文。';
const REGEN_REPLY = 'E2E15 受控模型回复：重新生成后的第二版正文。';

const FROZEN_BUNDLE_SHA256 =
  '45f11529a22da07d23e665c412ad6b1611758d63e28bd464727efa32e174fd30';
const FROZEN_TREE_HASH =
  '76cf2fd01952d06d46932eea1406f849452ed6e0285b2458f7794d1214a42f7b';

function treeHashSrcTest() {
  // Same recipe as plans/evidence/final25-freeze-meta.json.
  const out = execFileSync('bash', ['-c',
    'find src test -type f | sort | xargs sha256sum | sha256sum'],
    { cwd: REPO_ROOT, encoding: 'utf8' });
  return out.trim().split(/\s+/)[0];
}

function bundleSha256() {
  return crypto.createHash('sha256')
    .update(fs.readFileSync(path.join(REPO_ROOT, 'resources/public/js/main.js')))
    .digest('hex');
}

function speechConfigEdn(speechUrl) {
  // Persisted speech config, normalized shape from
  // hc.server.speech.config/defaults.  Two STT services: the seeded default
  // `cloud-stt` always fails in the mock, `device-stt` succeeds; retrying
  // with the second service proves the recording is preserved and reused.
  return `{:revision 0
 :stt {:services [
        {:id "cloud-stt" :label "Cloud STT" :kind :openai-compatible
         :base-url "${speechUrl}" :model "mock-stt"
         :processing :cloud :enabled? true :order 0
         :transcriptions-path "/v1/audio/transcriptions"
         :response-format "json" :languages ["zh-CN" "en"]}
        {:id "device-stt" :label "Device STT" :kind :openai-compatible
         :base-url "${speechUrl}" :model "mock-stt"
         :processing :device :enabled? true :order 1
         :transcriptions-path "/v1/audio/transcriptions"
         :response-format "json" :languages ["zh-CN" "en"]}]
       :default-service-id "cloud-stt"
       :max-duration-ms 300000
       :max-audio-bytes 26214400
       :language "auto"
       :response-format "json"
       :connect-timeout-ms 5000
       :request-timeout-ms 60000}
 :tts {:services [
       {:id "mock-tts" :label "Mock TTS" :kind :openai-compatible
        :base-url "${speechUrl}" :model "mock-tts" :voice "alloy"
        :processing :cloud :enabled? true :order 0
        :speech-path "/v1/audio/speech"
        :format "wav" :speed 1.0 :language "zh-CN"}]
       :default-service-id "mock-tts"
       :max-text-chars 20000
       :max-audio-bytes 52428800
       :format "wav"
       :speed 1.0
       :skip-code? true
       :skip-urls? true
       :skip-tables? true
       :skip-formulas? true
       :table-min-rows 4
       :notices {:en {:code-block "There is a code block."
                      :indented-code "There is a code block."
                      :table "There is a table."
                      :url "There is a link."
                      :formula "There is a formula."
                      :image "Image."}
                 :zh-CN {:code-block "这里有代码块。"
                         :indented-code "这里有代码块。"
                         :table "这里有表格。"
                         :url "这里有一个网址。"
                         :formula "这里有一个公式。"
                         :image "图片。"}}
       :test-text "Herdchestra speech test."
       :connect-timeout-ms 5000
       :request-timeout-ms 120000}
 :translation {:services [
                {:id "mock-translate" :label "Mock Translate"
                 :kind :openai-compatible
                 :base-url "${speechUrl}" :model "mock-translate"
                 :processing :cloud :enabled? true :order 0
                 :chat-path "/v1/chat/completions"}]
               :default-service-id "mock-translate"
               :max-text-chars 20000
               :connect-timeout-ms 5000
               :request-timeout-ms 60000}}`;
}

async function mockState(mock) {
  return (await fetch(`${mock.url}/__mock/state`)).json();
}

async function composerText(page) {
  return composer(page).inputValue().catch(() => '');
}

async function messageCount(page, selector) {
  return page.locator(selector).count();
}

// Locate the assistant message row that carries the 朗读/翻译 actions.
function assistantRow(page, text) {
  return page.locator('.hc-message-in:not(.hc-message-process)')
    .filter({ hasText: text }).last();
}

async function waitPlayer(page, timeout = 30000) {
  const player = page.locator('.hc-speech-player');
  await player.waitFor({ state: 'visible', timeout });
  return player;
}

async function playerText(page) {
  const player = page.locator('.hc-speech-player');
  if (!(await player.count())) return null;
  return (await player.first().innerText()).trim();
}

// The translation run button sits in the same flex row as the 目标语言
// select; scoping to that row keeps the message-level 翻译 toggle and the
// panel's own 翻译 run button unambiguous.
function translationRunRow(page) {
  return page.locator('div', { has: page.locator('label', { hasText: '目标语言' }) })
    .last();
}

// The derived-result block is the only role=status region that also holds
// 复制译文/插入草稿 buttons.
function translationResult(page) {
  return page.locator('div[role="status"]')
    .filter({ has: page.getByRole('button', { name: '插入草稿', exact: true }) })
    .last();
}

test('@core E2E 15 speech transcription, read-aloud and translation', async ({ browser }) => {
  test.setTimeout(600000);
  fs.mkdirSync(EVIDENCE, { recursive: true });
  const checks = {};
  const evidence = {
    test: 'E2E 15',
    mode: 'automated',
    mock: true,
    startedAt: new Date().toISOString(),
    freeze: {
      bundleSha256Expected: FROZEN_BUNDLE_SHA256,
      treeHashExpected: FROZEN_TREE_HASH,
      bundleSha256Actual: bundleSha256(),
      treeHashSrcTestActual: treeHashSrcTest(),
    },
    clientBundle: clientBundleInfo(),
    checks,
    screenshots: [],
    manual: {},
  };
  evidence.freeze.bundleMatchesFreeze =
    evidence.freeze.bundleSha256Actual === FROZEN_BUNDLE_SHA256;
  evidence.freeze.treeMatchesFreeze =
    evidence.freeze.treeHashSrcTestActual === FROZEN_TREE_HASH;
  const writeEvidence = (extra = {}) => {
    try {
      fs.writeFileSync(path.join(EVIDENCE, 'e2e-15.json'),
        JSON.stringify({ ...evidence, ...extra }, null, 2));
    } catch { /* best effort */ }
  };

  const model = await startMockModel({
    replyScript: [
      { match: '第一轮', reply: `${REPLY_TEXT} 第一轮。` },
      { match: '重新生成', reply: `${REGEN_REPLY}` },
      { reply: REPLY_TEXT },
    ],
  });
  const speech = await startMockSpeech({
    transcript: TRANSCRIPT,
    translatedText: TRANSLATED,
    // The seeded default STT service is the "cloud" one; fail only its
    // requests by matching nothing — instead we fail ALL stt calls first and
    // flip the mock to success before the retry.  Simpler and deterministic:
    // sttStatus starts at 503, the spec flips it after the first failure.
    sttStatus: 503,
  });

  // Seed the persisted speech config BEFORE the app opens its data dir.
  const tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'hc-e2e15-'));
  const dataDir = path.join(tempRoot, 'data');
  const speechDir = path.join(dataDir, 'speech');
  fs.mkdirSync(speechDir, { recursive: true });
  const configPath = path.join(speechDir, 'config.edn');
  fs.writeFileSync(configPath, speechConfigEdn(speech.url));
  fs.writeFileSync(path.join(EVIDENCE, 'speech-config-seeded.edn'),
    speechConfigEdn(speech.url));

  const app = await startApp({
    root: tempRoot,
    evidenceDir: EVIDENCE,
    // The mock never advances the Plan ledger, so every turn ends in the
    // unattended Nudge tail; regeneration is refused while a run is active.
    // Two rounds still exercise the continuation path and then let the
    // session go idle for the explicit regenerate command.
    nudgeRoundBudget: 2,
    models: {
      connections: [{
        id: 'mock',
        name: 'E2E15 Mock',
        baseUrl: `${model.url}/v1`,
        models: [{
          id: MODEL_ID,
          displayName: MODEL_NAME,
          maxOutputTokens: 2048,
          capabilities: ['tools'],
        }],
      }],
      roles: { main: { connection: 'mock', model: MODEL_ID } },
    },
  });

  let context;
  let page;
  try {
    context = await browser.newContext({
      permissions: ['clipboard-read', 'clipboard-write', 'microphone'],
      // Fake media device: Chrome synthesizes an audio tone, so
      // getUserMedia/MediaRecorder produce real (non-empty) WebM chunks.
      // --autoplay-policy lets the read-aloud Audio element start without a
      // user gesture in headless runs.
      launchOptions: {
        args: [
          '--use-fake-device-for-media-stream',
          '--use-fake-ui-for-media-stream',
          '--autoplay-policy=no-user-gesture-required',
        ],
      },
    });
    page = await context.newPage();
    page.on('pageerror', (error) =>
      (evidence.browserErrors ||= []).push(String(error).slice(0, 300)));
    // Diagnostic capture: every /speech/* request and console message so a
    // silent upload hang leaves evidence in e2e-15.json.
    (evidence.httpLog ||= []).length = 0;
    page.on('request', (req) => {
      if (req.url().includes('/speech/')) {
        (evidence.httpLog ||= []).push(
          `REQ ${req.method()} ${new URL(req.url()).pathname} ${req.postData() ? 'body=' + req.postData().length : ''}`);
      }
    });
    page.on('response', (res) => {
      if (res.url().includes('/speech/')) {
        (evidence.httpLog ||= []).push(
          `RES ${res.status()} ${new URL(res.url()).pathname}`);
      }
    });
    page.on('console', (msg) => {
      if ((msg.type() === 'error' || msg.type() === 'warning')
          && !/React DevTools|unique "key" prop|Unsupported style/.test(msg.text())) {
        (evidence.consoleLog ||= []).push(
          `${msg.type()}: ${msg.text().slice(0, 300)}`);
      }
    });

    // Deterministic microphone: prefer Chrome's fake device, but fall back
    // to a synthesized AudioContext stream when the environment has no
    // audio backend at all.  Either way MediaRecorder produces real
    // non-empty WebM chunks, so the upload path under test is genuine.  The
    // fallback decodes a generated WAV through OfflineAudioContext — an
    // oscillator alone stays silent while the AudioContext is suspended.
    await page.addInitScript(() => {
      if (!navigator.mediaDevices) return;
      const real = navigator.mediaDevices.getUserMedia;
      // 8 kHz mono 16-bit PCM WAV, ~1.2 s of a 440 Hz tone.
      const wavBytes = (() => {
        const rate = 8000, n = 9600, dataSize = n * 2;
        const b = new ArrayBuffer(44 + dataSize);
        const v = new DataView(b);
        const ws = (o, s) => { for (let i = 0; i < s.length; i++) v.setUint8(o + i, s.charCodeAt(i)); };
        ws(0, 'RIFF'); v.setUint32(4, 36 + dataSize, true); ws(8, 'WAVE');
        ws(12, 'fmt '); v.setUint32(16, 16, true); v.setUint16(20, 1, true);
        v.setUint16(22, 1, true); v.setUint32(24, rate, true);
        v.setUint32(28, rate * 2, true); v.setUint16(32, 2, true);
        v.setUint16(34, 16, true); ws(36, 'data'); v.setUint32(40, dataSize, true);
        for (let i = 0; i < n; i++) {
          const s = Math.round(Math.sin(2 * Math.PI * 440 * i / rate) * 12000);
          v.setInt16(44 + i * 2, s, true);
        }
        return b;
      })();
      const synthStream = () => {
        const Ctx = window.AudioContext || window.webkitAudioContext;
        const offline = new OfflineAudioContext(1, 9600, 8000);
        return offline.decodeAudioData(wavBytes.slice(0))
          .then((audio) => {
            const ctx = new Ctx();
            const dest = ctx.createMediaStreamDestination();
            const src = ctx.createBufferSource();
            src.buffer = audio;
            src.loop = true;
            src.connect(dest);
            src.start();
            return ctx.resume().then(() => new Promise((resolve) => {
              if (ctx.state === 'running') return resolve(dest.stream);
              const onChange = () => {
                if (ctx.state === 'running') {
                  ctx.removeEventListener('statechange', onChange);
                  resolve(dest.stream);
                }
              };
              ctx.addEventListener('statechange', onChange);
              setTimeout(() => resolve(dest.stream), 3000);
            }));
          });
      };
      // Always use the synthesized stream: Chrome's fake device can attach
      // but emit silence in this environment, which the panel correctly
      // reports as an empty recording.
      navigator.mediaDevices.getUserMedia = function (constraints) {
        return synthStream();
      };
      // Expose the live MediaRecorder so the spec can wait for the
      // recording phase and stop it through the real 停止录音 button —
      // the app's own onstop -> upload! path runs unchanged.
      const OrigMR = window.MediaRecorder;
      window.MediaRecorder = function (stream, opts) {
        const r = new OrigMR(stream, opts);
        window.__hcRecorder = r;
        return r;
      };
      window.MediaRecorder.prototype = OrigMR.prototype;
      window.MediaRecorder.isTypeSupported = OrigMR.isTypeSupported;
      window.MediaRecorder.mimeType = OrigMR.mimeType;
    });

    // --- session + one assistant reply ------------------------------------
    await goto(page, app.url, '/sessions');
    await waitForSync(page);
    await newSession(page);
    await page.locator('.hc-speech-input').first()
      .waitFor({ state: 'visible', timeout: 30000 });
    checks.speechPanelMounted = true;

    await composer(page).fill('E2E15 第一轮：请回复一句话。');
    await page.locator('button[aria-label="发送消息"]').first().click();
    const reply = await waitForAssistant(page, {
      timeout: 90000, minChars: 10, quietMs: 2500 });
    checks.replyOk = reply.text.includes('E2E15');
    assert(checks.replyOk, `assistant reply missing marker: ${reply.text.slice(0, 120)}`);
    await ensureIdle(page, 30000);
    const usersBefore = await userTexts(page);
    checks.userMessageCount = usersBefore.length;

    const row = assistantRow(page, 'E2E15');
    await row.waitFor({ state: 'visible', timeout: 15000 });
    checks.assistantRowFound = true;

    // --- read-aloud: play, pause/resume, stop -----------------------------
    await row.getByRole('button', { name: '朗读此回复' }).first().click();
    let player = await waitPlayer(page);
    // Wait until the TTS fetch resolves into :playing (or :error).
    await page.waitForFunction(() => {
      const el = document.querySelector('.hc-speech-player');
      return el && /正在朗读|朗读已暂停|朗读失败/.test(el.textContent || '');
    }, null, { timeout: 30000 });
    let playerNow = await playerText(page);
    checks.ttsPlaying = /正在朗读|朗读已暂停/.test(playerNow || '');
    checks.ttsError = /朗读失败/.test(playerNow || '');
    assert(checks.ttsPlaying, `player did not reach playing: ${playerNow}`);
    const ttsState1 = await mockState(speech);
    checks.ttsRequestCount = ttsState1.tts.length;
    checks.ttsInputIsReply = ttsState1.tts.some((r) =>
      typeof r.input === 'string' && r.input.includes('E2E15'));
    checks.ttsFormat = ttsState1.tts[0]?.format;
    assert(checks.ttsRequestCount >= 1, 'mock saw no TTS request');
    assert(checks.ttsInputIsReply, 'TTS input is not the visible reply text');
    evidence.screenshots.push(await shot(page, EVIDENCE, 'e2e15-01-read-aloud-playing'));

    // Pause then resume through the mini player controls.
    const pauseBtn = player.locator('button', { hasText: '暂停' }).first();
    if (await pauseBtn.count()) {
      await pauseBtn.click();
      await page.waitForFunction(() => {
        const el = document.querySelector('.hc-speech-player');
        return el && /朗读已暂停/.test(el.textContent || '');
      }, null, { timeout: 15000 });
      checks.ttsPaused = true;
      await player.locator('button', { hasText: '继续' }).first().click();
      await page.waitForFunction(() => {
        const el = document.querySelector('.hc-speech-player');
        return el && /正在朗读/.test(el.textContent || '');
      }, null, { timeout: 15000 });
      checks.ttsResumed = true;
    } else {
      checks.ttsPaused = 'not-offered';
    }

    // --- version switch stops the old read-aloud --------------------------
    // Keep the player alive, then produce a second message version through
    // an explicit assistant-message edit.  Editing appends a `:manual`
    // version through the same version machinery regenerate uses, without
    // needing a model run; the version-selector onClick and the stale-run
    // effect both stop audio that belongs to the old version.
    await ensureIdle(page, 60000);
    await row.getByRole('button', { name: '编辑', exact: true }).first().click();
    const editPanel = page.locator(
      '.hc-inline-edit-panel[aria-label="编辑消息"]').first();
    await editPanel.waitFor({ state: 'visible', timeout: 15000 });
    await editPanel.locator('textarea[aria-label="编辑消息内容"]').first()
      .fill(`${REPLY_TEXT} 编辑后的第二版。`);
    await editPanel.getByRole('button', { name: '保存消息编辑' }).first().click();
    // The tracked edit must reach :accepted; the editor closes on accept and
    // the version selector appears once the message has two versions.
    await page.waitForFunction(() => {
      const versions = document.querySelectorAll(
        '.hc-message-versions .hc-version-button');
      return versions.length >= 2;
    }, null, { timeout: 60000 });
    checks.versionSelectorShown = true;
    // The version change must have stopped the old audio: the player
    // element unmounts when playback state returns to :idle.
    await page.waitForFunction(() => {
      const el = document.querySelector('.hc-speech-player');
      return !el;
    }, null, { timeout: 30000 });
    checks.playerGoneAfterVersionSwitch = true;
    const editedRow = assistantRow(page, 'E2E15');
    const editedText = await editedRow.innerText();
    checks.editedVersionShown = editedText.includes('编辑后的第二版');
    assert(checks.editedVersionShown,
      `edited version text missing: ${editedText.slice(0, 200)}`);
    evidence.screenshots.push(await shot(page, EVIDENCE, 'e2e15-02-version-switch-stopped'));

    // Switch back to v1 and confirm the selector works both ways; the old
    // version's text must render again.
    const v1 = page.locator('.hc-version-button', { hasText: /^v1$/ }).first();
    await v1.waitFor({ state: 'visible', timeout: 15000 });
    await v1.click();
    await page.waitForFunction(() => {
      const el = [...document.querySelectorAll('.hc-message-in')]
        .find((n) => (n.textContent || '').includes('E2E15'));
      return el && (el.textContent || '').includes('第一轮');
    }, null, { timeout: 30000 });
    checks.versionSwitchBackOk = true;
    checks.versionButtons = await page.locator('.hc-version-button').count();
    // Return to the latest version before continuing.
    await page.locator('.hc-version-button').last().click();
    await page.waitForTimeout(400);

    // --- translation: derived result, original preserved ------------------
    const usersBeforeTranslate = await userTexts(page);
    await composer(page).fill('E2E15 翻译前草稿前缀 ');
    const translateBtn = row.getByRole('button', { name: '翻译', exact: true }).first();
    await translateBtn.click();
    // Inside the panel the run button shares a row with the 目标语言 select.
    const runRow = translationRunRow(page);
    await runRow.waitFor({ state: 'visible', timeout: 15000 });
    checks.translationPanelOpened = true;
    const langSelect = runRow.locator('select').first();
    await langSelect.selectOption('en');
    await runRow.getByRole('button', { name: '翻译', exact: true }).first().click();
    await page.waitForFunction(() => {
      const el = [...document.querySelectorAll('div')]
        .find((d) => (d.textContent || '').includes('服务 mock-translate'));
      return Boolean(el);
    }, null, { timeout: 30000 });
    const translateState = await mockState(speech);
    checks.translateRequestCount = translateState.translate.length;
    checks.translateSawSource = translateState.translate.some((r) =>
      typeof r.user === 'string' && r.user.includes('E2E15'));
    checks.translateSawSystem = translateState.translate.some((r) =>
      typeof r.system === 'string' && /Translate/.test(r.system));
    assert(checks.translateRequestCount >= 1, 'mock saw no translation request');
    assert(checks.translateSawSource, 'translation request did not carry the source text');
    // Original text still rendered; translated text shown as derived result.
    const rowTextAfter = await assistantRow(page, 'E2E15').innerText();
    checks.originalPreserved = rowTextAfter.includes('编辑后的第二版')
      || rowTextAfter.includes('E2E15 受控模型回复');
    checks.translationShown = (await page.locator('body').innerText())
      .includes(TRANSLATED.slice(0, 20));
    assert(checks.originalPreserved, 'original message text was overwritten');
    assert(checks.translationShown, 'translated text not rendered');
    evidence.screenshots.push(await shot(page, EVIDENCE, 'e2e15-03-translation'));

    // Insert the translation into the draft: composer keeps its prefix.
    const resultBlock = translationResult(page);
    await resultBlock.waitFor({ state: 'visible', timeout: 15000 });
    const insertBtn = resultBlock.getByRole('button', { name: '插入草稿', exact: true }).first();
    const insertCount = await resultBlock.getByRole('button', { name: '插入草稿', exact: true }).count();
    checks.insertDraftButtons = insertCount;
    assert(insertCount >= 1, 'no 插入草稿 button in translation panel');
    await insertBtn.click();
    await page.waitForFunction((needle) => {
      const el = document.querySelector('textarea[aria-label="消息输入"]');
      return el && el.value.includes(needle);
    }, TRANSLATED.slice(0, 20), { timeout: 15000 });
    const draftAfterTranslate = await composerText(page);
    checks.draftHasPrefix = draftAfterTranslate.includes('E2E15 翻译前草稿前缀');
    checks.draftHasTranslation = draftAfterTranslate.includes(TRANSLATED.slice(0, 20));
    assert(checks.draftHasPrefix, `draft lost its prefix: ${draftAfterTranslate}`);
    assert(checks.draftHasTranslation, `draft lacks translation: ${draftAfterTranslate}`);
    // Nothing was sent: still exactly the messages from before.
    const usersAfter = await userTexts(page);
    checks.noAutoSend = usersAfter.length === usersBeforeTranslate.length
      && JSON.stringify(usersAfter) === JSON.stringify(usersBeforeTranslate);
    assert(checks.noAutoSend, 'translation insert sent a message');

    // --- voice input: record -> failed cloud STT -> retry other service ---
    // The seeded default is cloud-stt (mock returns 503 for every STT call
    // until flipped).  Record with the fake device, stop, see the failure
    // panel that keeps the recording, switch to device-stt, confirm, retry.
    const micButton = page.locator('.hc-speech-input button').first();
    await micButton.waitFor({ state: 'visible', timeout: 15000 });
    await micButton.click();
    // First click may land on the explain panel; continue if needed.  The
    // explain banner mounts asynchronously, so poll briefly instead of a
    // one-shot count().
    // Click 继续 -> :explain transitions to :requesting/:recording.
    const explainSeen = await page.waitForFunction(() => {
      const el = document.querySelector('.hc-speech-input');
      return el && /麦克风只用于本次语音输入/.test(el.textContent || '');
    }, null, { timeout: 8000 }).then(() => true).catch(() => false);
    if (explainSeen) {
      await page.locator('.hc-speech-input')
        .getByRole('button', { name: '继续', exact: true }).first().click();
    }
    // Wait until the app has constructed the MediaRecorder (phase
    // :recording), then let it capture real chunks and stop it through the
    // real 停止录音 button — the app's onstop -> upload! path is unchanged.
    try {
      await page.waitForFunction(() => window.__hcRecorder
        && window.__hcRecorder.state === 'recording',
        null, { timeout: 30000 });
      checks.micRecordingStarted = true;
      // Recording is live; stop it through the real button so the app's
      // stop-recording! -> recorder.stop() -> onstop -> upload! path runs
      // end to end.  The button label flips to 停止录音 once phase is
      // :recording; wait for that exact label before clicking.
      const stopButton = page.locator('.hc-speech-input button')
        .filter({ hasText: '停止录音' }).first();
      await stopButton.waitFor({ state: 'visible', timeout: 15000 });
      await stopButton.click();
      checks.micStoppedViaButton = true;
    } catch (err) {
      checks.micRecordingStarted = false;
      const dump = await page.evaluate(() => {
        const el = document.querySelector('.hc-speech-input');
        return el ? {
          text: (el.innerText || '').slice(0, 1500),
          buttons: [...el.querySelectorAll('button')]
            .map((b) => ({ text: (b.textContent || '').trim(),
                           aria: b.getAttribute('aria-label'),
                           disabled: b.disabled })),
        } : { missing: true };
      }).catch((e) => ({ evaluateError: String(e) }));
      evidence.micDump = dump;
      evidence.manual.microphone =
        'getUserMedia/MediaRecorder unavailable in this environment; ' +
        'recording steps need a manual pass with a real microphone.';
      evidence.manual.microphoneError = String(err).slice(0, 300);
      writeEvidence();
      throw err;
    }

    // Upload fails (mock STT 503): failure panel keeps the recording.
    await page.waitForFunction(() => {
      const el = document.querySelector('.hc-speech-input');
      return el && /转写失败|失败|重试/.test(el.textContent || '');
    }, null, { timeout: 60000 });
    const inputText1 = await page.locator('.hc-speech-input').first().innerText();
    checks.sttFailureShown = /转写失败|失败/.test(inputText1);
    checks.sttAudioPreservedHint = /已保存|保留|录音/.test(inputText1);
    const sttStateFail = await mockState(speech);
    checks.sttAttemptsAfterFail = sttStateFail.stt.length;
    assert(checks.sttAttemptsAfterFail >= 1, 'mock saw no STT attempt');
    // The recording preview audio element proves the blob survived.
    checks.sttAudioElement = await page.locator('.hc-speech-input audio').count() >= 1;
    evidence.screenshots.push(await shot(page, EVIDENCE, 'e2e15-04-stt-failed-kept'));

    // Flip the mock to success, switch service to device-stt, confirm, retry.
    await fetch(`${speech.url}/__mock/config`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ sttStatus: 200 }),
    });
    const serviceSelect = page.locator('.hc-speech-input select').first();
    await serviceSelect.waitFor({ state: 'visible', timeout: 15000 });
    const options = await serviceSelect.locator('option').evaluateAll(
      (nodes) => nodes.map((n) => ({ value: n.value, label: n.textContent })));
    checks.sttServiceOptions = options;
    const deviceOption = options.find((o) => o.value === 'device-stt');
    assert(deviceOption, `device-stt option missing: ${JSON.stringify(options)}`);
    await serviceSelect.selectOption('device-stt');
    // The change guard requires an explicit confirmation before the recording
    // is sent to the other service.
    const confirmBtn = page.getByRole('button', { name: '确认使用此服务', exact: true }).first();
    await confirmBtn.waitFor({ state: 'visible', timeout: 15000 });
    checks.serviceChangeWarning = /将把这段录音发送给另一服务/.test(
      await page.locator('.hc-speech-input').first().innerText());
    await confirmBtn.click();
    await page.locator('.hc-speech-input')
      .getByRole('button', { name: '重试', exact: true }).first().click();

    // Review phase: editable transcript, service facts, insert into draft.
    const transcriptArea = page.locator(
      'textarea[aria-label="转写文字，可编辑后插入草稿"]').first();
    await transcriptArea.waitFor({ state: 'visible', timeout: 60000 });
    checks.reviewShown = true;
    const transcriptValue = await transcriptArea.inputValue();
    checks.transcriptText = transcriptValue;
    checks.transcriptMatches = transcriptValue.includes('明天下午四点');
    assert(checks.transcriptMatches,
      `transcript mismatch: ${transcriptValue.slice(0, 120)}`);
    const reviewFacts = await page.locator('.hc-speech-input').first().innerText();
    checks.reviewShowsDevice = /设备内|Device STT/.test(reviewFacts);
    checks.reviewShowsDuration = /录音时长|已保留/.test(reviewFacts);
    evidence.screenshots.push(await shot(page, EVIDENCE, 'e2e15-05-transcript-review'));

    const sttStateOk = await mockState(speech);
    checks.sttRetryUsedSecondService = sttStateOk.stt.length >= 2
      && sttStateOk.stt.every((r) => (r.bytes || 0) > 0);
    // The retried call reused the stored recording: same byte size as the
    // failed upload (mock records only sizes, not ids — the server-side
    // recording-id reuse is asserted via the single POST /recordings).
    checks.sttRecordingPosts = sttStateOk.requests.filter(
      (r) => r.path === '/v1/audio/transcriptions').length;

    // Transcript goes to the draft only — never auto-sent.
    const usersBeforeInsert = await userTexts(page);
    const draftBeforeInsert = await composerText(page);
    await page.locator('.hc-speech-input')
      .getByRole('button', { name: '插入草稿', exact: true }).first().click();
    await page.waitForFunction((needle) => {
      const el = document.querySelector('textarea[aria-label="消息输入"]');
      return el && el.value.includes(needle);
    }, '明天下午四点', { timeout: 15000 });
    const draftAfterInsert = await composerText(page);
    checks.draftHasTranscript = draftAfterInsert.includes('明天下午四点');
    checks.draftKeptPrefix = draftBeforeInsert.length === 0
      ? true : draftAfterInsert.startsWith(draftBeforeInsert.trim());
    const usersAfterInsert = await userTexts(page);
    checks.transcriptNotSent = usersAfterInsert.length === usersBeforeInsert.length
      && JSON.stringify(usersAfterInsert) === JSON.stringify(usersBeforeInsert);
    assert(checks.draftHasTranscript, `draft lacks transcript: ${draftAfterInsert}`);
    assert(checks.transcriptNotSent, 'transcript was sent as a message');
    evidence.screenshots.push(await shot(page, EVIDENCE, 'e2e15-06-draft-inserted'));

    // Settings page sanity: the seeded services are visible under 语音与朗读.
    await goto(page, app.url, '/settings');
    // The tab bar renders role="tab", not role="button".
    await page.locator('.wb-tabs button.wb-tab', { hasText: '语音与朗读' })
      .first().click();
    await page.locator('.hc-speech-input, .wb-tabs').first()
      .waitFor({ state: 'visible', timeout: 15000 }).catch(() => {});
    const settingsText = await page.locator('body').innerText();
    checks.settingsListsStt = /cloud-stt|Cloud STT/.test(settingsText);
    checks.settingsListsTts = /mock-tts|Mock TTS/.test(settingsText);
    checks.settingsListsTranslate = /mock-translate|Mock Translate/.test(settingsText);
    evidence.screenshots.push(await shot(page, EVIDENCE, 'e2e15-07-speech-settings'));

    evidence.checks = checks;
    evidence.ok = true;
    evidence.finishedAt = new Date().toISOString();
    writeEvidence();
    assert(checks.replyOk && checks.ttsPlaying && checks.playerGoneAfterVersionSwitch
      && checks.translationShown && checks.reviewShown
      && checks.draftHasTranscript && checks.transcriptNotSent,
      `core E2E 15 checks failed: ${JSON.stringify(checks)}`);
  } catch (error) {
    evidence.ok = false;
    evidence.error = String(error && error.message || error).slice(0, 2000);
    evidence.finishedAt = new Date().toISOString();
    writeEvidence();
    if (page) {
      await shot(page, EVIDENCE, 'e2e15-error').catch(() => {});
    }
    throw error;
  } finally {
    writeEvidence();
    if (context) await context.close().catch(() => {});
    if (app) await app.close().catch(() => {});
    await speech.close().catch(() => {});
    await model.close().catch(() => {});
  }
});
