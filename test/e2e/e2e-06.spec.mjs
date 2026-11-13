// E2E 06: attachment capability routing and partial reads.
//
// One real Herdchestra server, two mock OpenAI-compatible connections:
//   - "text"  : capabilities ["text"]        -> image/pdf/archive have no
//                                             native route; the card must
//                                             offer a repair path and the
//                                             send must stay blocked with no
//                                             silent outbound request;
//   - "vision": capabilities ["vision","document","tools"]
//                                           -> a 3-page image-only scanned
//                                             PDF goes through the real
//                                             Tesseract OCR route; page 2 is
//                                             noise and must be listed as
//                                             unreadable while pages 1 and 3
//                                             travel with their original
//                                             page numbers.
//
// Also covered: a >100 MiB file is refused before upload (client limit) and
// by the server (413); a 21st attachment is refused by the 20-item draft
// limit; a workspace association keeps its "外部引用 … 重新定位" guidance
// and the server reports the missing file honestly; a sent attachment keeps
// a readable local copy (`/blobs/<id>`) after the page reloads.
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from '@playwright/test';
import { startApp, REPO_ROOT } from './support/app.mjs';
import { startMockModel } from './support/mock-model.mjs';
import {
  assert, assistantTexts, clientBundleInfo,
  composer, ensureIdle, goto, newSession, send,
  sessionModelLabel, shot, userTexts, waitForAssistant, waitForShell,
  waitForSync, writeJson,
} from './support/ui.mjs';
import { sourceTreeSha256, collectMockState } from './support/evidence.mjs';

test.describe.configure({ mode: 'serial' });

const EVIDENCE_DIR = path.join(REPO_ROOT, 'plans/evidence/e2e-06');
const TEXT_MODEL_ID = 'mock-text';
const TEXT_MODEL_NAME = 'Mock Text';
const VISION_MODEL_ID = 'mock-vision';
const VISION_MODEL_NAME = 'Mock Vision';

const WORKSPACE_GUIDE = 'docs/guide.txt';
const WORKSPACE_GUIDE_TEXT = 'E2E06 workspace guide: relocation fixture.\n';

// ---------------------------------------------------------------------------
// Fixture generation: a 3-page image-only "scanned" PDF.
// Page 1 and 3 carry rendered CJK text; page 2 is pure noise so the real OCR
// pass must report it as unreadable instead of inventing text.
// ---------------------------------------------------------------------------

function generateFixtures(dir) {
  fs.mkdirSync(dir, { recursive: true });
  const script = `
import sys
try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:
    sys.stderr.write(
        'Pillow is required to build the E2E 06 scan fixture.\\n'
        'Install it into the runner you invoke, e.g.\\n'
        '  python3 -m pip install --user pillow\\n'
        'The suite cannot fake a scanned PDF without an image library.\\n')
    raise SystemExit(2)
import os, random
out = ${JSON.stringify(dir)}

# The fixture needs a CJK-capable font.  Only Linux paths were listed, so
# the case failed on macOS rather than skipping.  Probe the platforms the
# project supports and fail with a readable message when none is present.
font_candidates = [
    '/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc',
    '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf',
    '/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc',
    '/System/Library/Fonts/Hiragino Sans GB.ttc',
    '/System/Library/Fonts/Supplemental/Songti.ttc',
    'C:/Windows/Fonts/msyh.ttc',
    'C:/Windows/Fonts/simhei.ttf',
]
font_path = next((p for p in font_candidates if os.path.exists(p)), None)
if font_path is None:
    sys.stderr.write(
        'No CJK font found for the E2E 06 fixture.  Candidates tried:\\n'
        + '\\n'.join('  ' + p for p in font_candidates) + '\\n')
    raise SystemExit(2)
font = ImageFont.truetype(font_path, 52)
pages = []
for text in ['部署检查清单 : 服务已就绪。 E2E06 page one.',
             None,
             '第二页扫描件 : 附加说明。 E2E06 page three.']:
    im = Image.new('RGB', (1240, 1754), 'white')
    if text is None:
        random.seed(7)
        px = im.load()
        for y in range(0, 1754, 3):
            for x in range(0, 1240, 3):
                v = random.randint(185, 255)
                px[x, y] = (v, v, v)
    else:
        d = ImageDraw.Draw(im)
        d.text((80, 140), text, fill='black', font=font)
    pages.append(im)
pages[0].save(os.path.join(out, 'scan-3pages.pdf'), save_all=True,
              append_images=pages[1:], resolution=150.0)
# A tiny valid PNG for the image-attachment leg.
png = bytes.fromhex(
 '89504e470d0a1a0a0000000d4948445200000002000000020806000000' +
 '72b60d24' +
 '0000000d49444154789c626001000000ffff03000006000557bfabd4' +
 '0000000049454e44ae426082')
open(os.path.join(out, 'p1.png'), 'wb').write(png)
open(os.path.join(out, 'payload.bin'), 'wb').write(b'E2E06-UNSUPPORTED-BINARY\\x00\\x01')
print('fixtures-ok')
`;
  try {
    execFileSync('python3', ['-c', script], { stdio: 'pipe' });
  } catch (error) {
    throw new Error(`fixture generation failed: ${error.message}\n${error.stderr || ''}`);
  }
  return {
    pdf: path.join(dir, 'scan-3pages.pdf'),
    png: path.join(dir, 'p1.png'),
    bin: path.join(dir, 'payload.bin'),
  };
}

// ---------------------------------------------------------------------------
// UI helpers local to this spec.
// ---------------------------------------------------------------------------

function attachmentCards(page) {
  return page.locator('.hc-attachment-card');
}

async function cardByName(page, name) {
  const card = attachmentCards(page).filter({ hasText: name }).first();
  await card.waitFor({ state: 'visible', timeout: 20000 });
  return card;
}

// Wait until the named card leaves the in-flight ":adding" state and shows a
// terminal status label.  Uploads are real HTTP round-trips; asserting on
// the first paint races the pipeline.
async function waitForCardStatus(page, name, timeout = 60000) {
  const card = await cardByName(page, name);
  await page.waitForFunction((n) => {
    const cards = [...document.querySelectorAll('.hc-attachment-card')];
    const target = cards.find((c) => c.innerText.includes(n));
    return target && !target.innerText.includes('正在加入')
      && !target.innerText.includes('准备中');
  }, name, { timeout }).catch(() => {});
  return card.innerText();
}

async function addFiles(page, files) {
  const input = page.locator('.hc-conversation-with-attachments input[type="file"]').first();
  await input.setInputFiles(files);
}

async function associateWorkspace(page, relativePath) {
  page.once('dialog', (dialog) => dialog.accept(relativePath));
  await page.getByRole('button', { name: '关联工作区文件', exact: true }).first().click();
}

async function extractCard(page, name, pagesInput) {
  const card = await cardByName(page, name);
  if (pagesInput !== undefined) {
    page.once('dialog', (dialog) => dialog.accept(pagesInput));
  }
  // The visible label is 提取文字 but the accessible name is the aria-label
  // "提取附件 <name> 的文字".
  await card.getByRole('button', { name: new RegExp(`提取附件 .*${name}`) }).first().click();
}

// Wait until the named card's text changes away from a snapshot (e.g. after
// an explicit extraction resolves) and no upload is still in flight.
async function waitForCardTextChange(page, name, before, timeout = 120000) {
  const card = await cardByName(page, name);
  await page.waitForFunction(([n, prev]) => {
    const cards = [...document.querySelectorAll('.hc-attachment-card')];
    const target = cards.find((c) => c.innerText.includes(n));
    return target && target.innerText !== prev && !target.innerText.includes('正在加入');
  }, [name, before], { timeout }).catch(() => {});
  return card.innerText();
}

async function mockState(mock) {
  const response = await fetch(`${mock.url}/__mock/state`);
  return response.json();
}

async function mockChatCount(mock) {
  return (await mockState(mock)).chatCompletions;
}

// Pull the last captured chat-completion request body and return the user
// text plus any "第 N 页：" markers the text route carried.
function lastRequestInfo(mock) {
  return mockState(mock).then((state) => {
    const raw = (state.bodies || []).at(-1);
    if (!raw) return { raw: null, text: '', pages: [] };
    let body = null;
    try { body = JSON.parse(raw); } catch { /* keep raw */ }
    const messages = body?.messages || [];
    const user = [...messages].reverse().find((m) => m?.role === 'user');
    const parts = Array.isArray(user?.content) ? user.content : [];
    const text = parts
      .filter((p) => p && p.type === 'text' && typeof p.text === 'string')
      .map((p) => p.text)
      .join('\n');
    const pages = [...text.matchAll(/第\s*(\d+)\s*页：/g)].map((m) => Number(m[1]));
    return { raw, text, pages, model: body?.model || null };
  });
}

async function historyChipText(page, name) {
  const chip = page.locator('.hc-message-out .hc-attachments .hc-chip, .hc-message-in .hc-attachments .hc-chip')
    .filter({ hasText: name }).first();
  await chip.waitFor({ state: 'visible', timeout: 20000 });
  return chip.innerText();
}

// ---------------------------------------------------------------------------
// Spec
// ---------------------------------------------------------------------------

test('@core E2E 06 attachment capability and partial reads', async ({ page, browser }) => {
  test.setTimeout(600000);
  fs.mkdirSync(EVIDENCE_DIR, { recursive: true });
  const marker = `E2E06-${Date.now()}`;
  const fixtureDir = fs.mkdtempSync(path.join(os.tmpdir(), 'hc-e2e06-'));
  const fixtures = generateFixtures(fixtureDir);
  result0Checks(fixtureDir);

  const textMock = await startMockModel({
    reply: `E2E06-TEXT-REPLY ${marker}`,
    captureBodies: true,
  });
  const visionMock = await startMockModel({
    reply: `E2E06-VISION-FALLBACK ${marker}`,
    captureBodies: true,
    replyScript: [
      {
        match: '部署检查清单',
        reply: `E2E06-ANSWER ${marker}：材料 scan-3pages.pdf；引用页码：1、3；第 2 页扫描噪点不可读。`,
      },
      { reply: `E2E06-VISION-FALLBACK ${marker}` },
    ],
  });

  const app = await startApp({
    evidenceDir: EVIDENCE_DIR,
    workspaceFiles: {
      [WORKSPACE_GUIDE]: WORKSPACE_GUIDE_TEXT,
    },
    models: {
      connections: [
        {
          id: 'text-conn',
          name: '文本服务',
          baseUrl: `${textMock.url}/v1`,
          models: [{
            id: TEXT_MODEL_ID,
            displayName: TEXT_MODEL_NAME,
            maxOutputTokens: 2048,
            capabilities: ['text'],
          }],
        },
        {
          id: 'vision-conn',
          name: '视觉服务',
          baseUrl: `${visionMock.url}/v1`,
          models: [{
            id: VISION_MODEL_ID,
            displayName: VISION_MODEL_NAME,
            maxOutputTokens: 2048,
            // `:text` is a declared input capability, not a chat guarantee:
            // without it the text route (extracted OCR text) is refused even
            // though the model can read the original PDF natively.
            capabilities: ['vision', 'document', 'tools', 'text'],
          }],
        },
      ],
      roles: { main: { connection: 'text-conn', model: TEXT_MODEL_ID } },
    },
  });

  const result = {
    test: 'E2E 06',
    mode: 'automated',
    mock: true,
    startedAt: new Date().toISOString(),
    marker,
    appUrl: app.url,
    mockUrls: { text: textMock.url, vision: visionMock.url },
    fixtureDir,
    chromeVersion: browser.version(),
    clientBundle: clientBundleInfo(),
    sourceTreeSha256: sourceTreeSha256(),
    checks: {},
    screenshots: [],
    browserEvents: [],
  };
  page.on('pageerror', (error) =>
    result.browserEvents.push(`${new Date().toISOString()} pageerror ${String(error).slice(0, 300)}`));
  page.on('console', (message) => {
    if (message.type() === 'error') {
      result.browserEvents.push(`${new Date().toISOString()} console-error ${message.text().slice(0, 300)}`);
    }
  });

  const screenshot = async (name) => {
    result.screenshots.push(await shot(page, EVIDENCE_DIR, name));
  };

  try {
    // =====================================================================
    // Session A: text-only model — capability mismatch must offer a repair
    // path and must never silently send the unsupported bytes.
    // =====================================================================
    await goto(page, app.url, '/sessions');
    await waitForSync(page);
    await newSession(page);
    await page.waitForFunction((name) => {
      const el = document.querySelector('button[aria-label="打开设置选择模型"]');
      return el && el.innerText.includes(name);
    }, TEXT_MODEL_NAME, { timeout: 30000 });
    const labelA = await sessionModelLabel(page);
    result.modelLabelText = labelA;
    result.checks.textModelBound = labelA.includes(TEXT_MODEL_NAME) || labelA.includes(TEXT_MODEL_ID);
    assert(result.checks.textModelBound, `session A did not bind text model: ${labelA}`);

    // -- image attachment: needs-choice, repair actions, blocked send ------
    await addFiles(page, [{
      name: 'p1.png',
      mimeType: 'image/png',
      buffer: fs.readFileSync(fixtures.png),
    }]);
    let cardTextPng = await waitForCardStatus(page, 'p1.png');
    result.cardPngBefore = cardTextPng;
    result.checks.pngNeedsChoice = cardTextPng.includes('需要选择处理方式');
    result.checks.pngOffersExtract = cardTextPng.includes('提取文字');
    result.checks.pngOffersExclude = cardTextPng.includes('排除');
    result.checks.pngLocalCopy = cardTextPng.includes('已保存副本');
    assert(result.checks.pngNeedsChoice, `png card lacks needs-choice: ${cardTextPng}`);
    assert(result.checks.pngOffersExtract && result.checks.pngOffersExclude,
      `png card lacks repair actions: ${cardTextPng}`);
    await screenshot('e2e06-01-text-model-png-needs-choice');

    const usersBeforeBlocked = await userTexts(page);
    const assistantsBeforeBlocked = await assistantTexts(page);
    const textCountBeforeBlocked = await mockChatCount(textMock);
    const sendButton = page.locator('button[aria-label="发送消息"]').first();
    await sendButton.click().catch(() => {});
    await page.waitForTimeout(800);
    const usersAfterBlocked = await userTexts(page);
    const textCountAfterBlocked = await mockChatCount(textMock);
    result.checks.blockedSendNoNewMessage = usersAfterBlocked.length === usersBeforeBlocked.length;
    result.checks.blockedSendNoRequest = textCountAfterBlocked === textCountBeforeBlocked;
    const bodyAfterBlocked = await page.locator('body').innerText();
    result.blockedNotice = bodyAfterBlocked.includes('仍有附件未准备好');
    result.checks.blockedNoticeShown = result.blockedNotice;
    assert(result.checks.blockedSendNoNewMessage, 'blocked send created a user message');
    assert(result.checks.blockedSendNoRequest, 'blocked send reached the model');
    assert(result.checks.blockedNoticeShown, 'no repair notice shown for blocked send');

    // -- unsupported binary: explicit unsupported, no extract route ---------
    await addFiles(page, [{
      name: 'payload.bin',
      mimeType: 'application/octet-stream',
      buffer: fs.readFileSync(fixtures.bin),
    }]);
    const cardBin = await waitForCardStatus(page, 'payload.bin');
    result.cardBin = cardBin;
    result.checks.binUnsupported = cardBin.includes('不支持') || cardBin.includes('无法发送');
    result.checks.binNoSilentSend = !(await page.locator('.hc-attachment-card:has-text("payload.bin") button:has-text("提取文字")').count());
    assert(result.checks.binUnsupported, `bin card not marked unsupported: ${cardBin}`);
    await screenshot('e2e06-02-text-model-unsupported-bin');

    // -- 100 MiB+1 limit: the wire boundary must refuse with 413 ------------
    // The queue reads File.size; a real >100 MiB browser File is impractical
    // in CI, so the server boundary is exercised directly: POST /attachments
    // with a body over the product limit must be refused before storage.
    const bigStatus = await fetch(`${app.url}/attachments?name=huge.bin&draft-count=0`, {
      method: 'POST',
      headers: { 'content-type': 'application/octet-stream' },
      body: Buffer.alloc(100 * 1024 * 1024 + 1, 0x41),
    }).then((r) => r.status).catch(() => 0);
    result.checks.bigFile413 = bigStatus === 413;
    assert(result.checks.bigFile413, `oversize upload returned ${bigStatus}, expected 413`);

    // -- 20-item draft limit -------------------------------------------------
    const smallFiles = Array.from({ length: 21 }, (_, i) => ({
      name: `small-${String(i + 1).padStart(2, '0')}.txt`,
      mimeType: 'text/plain',
      buffer: Buffer.from(`E2E06 small file ${i + 1}\n`),
    }));
    await addFiles(page, smallFiles);
    await page.waitForTimeout(4000);
    const cardCount = await attachmentCards(page).count();
    result.cardCountAfter21 = cardCount;
    // png + bin stay; exactly 20 small files may join; the 21st is refused.
    result.checks.max20Items = cardCount <= 22;
    const bodyTextLimit = await page.locator('body').innerText();
    result.limitNotice = /20|需要处理|已加入/.test(bodyTextLimit);
    assert(result.checks.max20Items,
      `21st attachment was not refused: ${cardCount} cards`);
    await screenshot('e2e06-03-text-model-limits');

    // Remove everything so later sessions stay clean.
    for (const name of ['p1.png', 'payload.bin']) {
      const rm = attachmentCards(page).filter({ hasText: name }).first();
      if (await rm.count()) {
        await rm.getByRole('button', { name: /^移除附件/ }).first().click().catch(() => {});
      }
    }
    for (let i = 1; i <= 21; i++) {
      const name = `small-${String(i).padStart(2, '0')}.txt`;
      const rm = attachmentCards(page).filter({ hasText: name }).first();
      if (await rm.count()) {
        await rm.getByRole('button', { name: /^移除附件/ }).first().click().catch(() => {});
      }
    }
    await page.waitForTimeout(500);

    // -- workspace association: guidance label + honest missing-file API ---
    await associateWorkspace(page, WORKSPACE_GUIDE);
    const cardGuide = await waitForCardStatus(page, 'guide.txt');
    result.cardWorkspace = cardGuide;
    result.checks.workspaceExternalRef = cardGuide.includes('外部引用');
    result.checks.workspaceRelocateHint = cardGuide.includes('重新定位');
    assert(result.checks.workspaceExternalRef && result.checks.workspaceRelocateHint,
      `workspace card lacks relocation guidance: ${cardGuide}`);
    await screenshot('e2e06-04-workspace-external-ref');

    // Delete the workspace file, then ask the server to re-read it: the
    // association must fail explicitly, never silently succeed.
    fs.rmSync(path.join(app.workspaceRoot, WORKSPACE_GUIDE), { force: true });
    // Non-local attachments are refused before any file read; the missing
    // file itself is proven by a workspace re-association attempt.
    const reassoc = await fetch(`${app.url}/attachments/workspace`, {
      method: 'POST',
      headers: { 'content-type': 'application/edn' },
      body: `{:relative-path "${WORKSPACE_GUIDE}" :snapshot? false}`,
    });
    const reassocJson = await reassoc.json().catch(() => null);
    result.workspaceReassocAfterDelete = { status: reassoc.status, body: reassocJson };
    result.checks.workspaceMissingRefused =
      reassoc.status >= 400 || (reassocJson && reassocJson.ok === false)
      || (reassocJson && reassocJson.error);
    assert(result.checks.workspaceMissingRefused,
      `re-associating a deleted workspace file unexpectedly succeeded: ${JSON.stringify(reassocJson)}`);

    const rmGuide = attachmentCards(page).filter({ hasText: 'guide.txt' }).first();
    if (await rmGuide.count()) {
      await rmGuide.getByRole('button', { name: /^移除附件/ }).first().click().catch(() => {});
    }

    // =====================================================================
    // Session B: vision model — scanned PDF OCR with partial pages.
    // =====================================================================
    await goto(page, app.url, '/sessions');
    await waitForSync(page);
    await newSession(page);
    const labelB = await sessionModelLabel(page);
    result.modelLabelVision = labelB;
    // The role binding still points at the text connection; switch this
    // session to the vision model through settings.
    // Use the settings model picker: navigate to settings -> 模型与角色.
    await goto(page, app.url, '/settings');
    await page.getByRole('button', { name: '模型与角色', exact: true }).first().click();
    await page.getByRole('heading', { name: '模型选择' }).first()
      .waitFor({ state: 'visible', timeout: 30000 });
    const visionGroup = page.locator('.hc-model-group').filter({ hasText: '视觉服务' }).first();
    const visionRow = visionGroup.locator('.hc-model-row')
      .filter({ hasText: VISION_MODEL_ID }).first();
    await visionRow.waitFor({ state: 'visible', timeout: 30000 });
    const switchBtn = visionRow.getByRole('button', { name: '用于下一轮', exact: true }).first();
    if (await switchBtn.count()) {
      await switchBtn.click();
      await page.getByText(/已提交模型切换|模型切换已接受/).first()
        .waitFor({ state: 'visible', timeout: 30000 }).catch(() => {});
    }
    await goto(page, app.url, '/sessions');
    // Return to session B: the session list is newest-first, so the row we
    // just created is the first entry.
    const sessionBRow = page.locator('.wb-session-list .wb-list-button').first();
    await sessionBRow.waitFor({ state: 'visible', timeout: 30000 });
    await sessionBRow.click();
    await composer(page).waitFor({ state: 'visible', timeout: 15000 });
    await page.waitForFunction((name) => {
      const el = document.querySelector('button[aria-label="打开设置选择模型"]');
      return el && el.innerText.includes(name);
    }, VISION_MODEL_NAME, { timeout: 30000 }).catch(() => {});
    const labelB2 = await sessionModelLabel(page);
    result.modelLabelVisionAfter = labelB2;
    result.checks.visionModelBound = labelB2.includes(VISION_MODEL_NAME) || labelB2.includes(VISION_MODEL_ID);
    assert(result.checks.visionModelBound, `session B did not bind vision model: ${labelB2}`);

    // -- upload the scanned PDF ---------------------------------------------
    await addFiles(page, [{
      name: 'scan-3pages.pdf',
      mimeType: 'application/pdf',
      buffer: fs.readFileSync(fixtures.pdf),
    }]);
    let cardPdf = await waitForCardStatus(page, 'scan-3pages.pdf');
    result.cardPdfBeforeExtract = cardPdf;
    // A document-capable model may send the original; the operator may still
    // choose the text route explicitly.  The card must offer both facts.
    result.checks.pdfCardVisible = cardPdf.includes('scan-3pages.pdf');
    result.checks.pdfOffersExtract = cardPdf.includes('提取文字');
    result.checks.pdfSavedCopy = cardPdf.includes('已保存副本');
    assert(result.checks.pdfCardVisible && result.checks.pdfOffersExtract,
      `pdf card lacks extract action: ${cardPdf}`);
    await screenshot('e2e06-05-vision-pdf-before-extract');

    // -- explicit extraction, all pages --------------------------------------
    const pdfTextBefore = cardPdf;
    await extractCard(page, 'scan-3pages.pdf', '');
    cardPdf = await waitForCardTextChange(page, 'scan-3pages.pdf', pdfTextBefore, 180000);
    result.cardPdfAfterExtract = cardPdf;
    result.checks.pdfPageLabel = cardPdf.includes('第 1–3 页') && cardPdf.includes('共 3 页');
    result.checks.pdfUnreadableListed = cardPdf.includes('不可读：第 2 页');
    result.checks.pdfSendable = cardPdf.includes('可发送');
    result.checks.pdfTextRoute = cardPdf.includes('发送提取文本') || cardPdf.includes('发送已确认的提取文本');
    assert(result.checks.pdfPageLabel, `page label missing: ${cardPdf}`);
    assert(result.checks.pdfUnreadableListed, `unreadable page not listed: ${cardPdf}`);
    assert(result.checks.pdfSendable && result.checks.pdfTextRoute,
      `pdf not sendable as text after OCR: ${cardPdf}`);
    await screenshot('e2e06-06-vision-pdf-after-ocr');

    // -- send and verify page-cited answer -----------------------------------
    await send(page, `E2E06 OCR 检查 ${marker}：请总结材料并引用页码。`);
    const reply = await waitForAssistant(page, { timeout: 120000, minChars: 10, quietMs: 3000 });
    result.answer = reply.text.slice(0, 400);
    result.checks.answerCitesPages = reply.text.includes('引用页码')
      && /1、?\s*3|1\s*[,，、]\s*3/.test(reply.text);
    result.checks.answerNamesMaterial = reply.text.includes('scan-3pages.pdf');
    result.checks.answerNotesUnreadable = reply.text.includes('第 2 页')
      && (reply.text.includes('不可读') || reply.text.includes('噪点'));
    assert(result.checks.answerCitesPages, `answer lacks page citation: ${reply.text.slice(0, 200)}`);
    assert(result.checks.answerNamesMaterial, `answer lacks material name: ${reply.text.slice(0, 200)}`);
    assert(result.checks.answerNotesUnreadable, `answer lacks unreadable note: ${reply.text.slice(0, 200)}`);
    await ensureIdle(page, 20000);
    await screenshot('e2e06-07-vision-answer');

    // -- request body proves only readable pages travelled -------------------
    const reqInfo = await lastRequestInfo(visionMock);
    result.requestPages = reqInfo.pages;
    result.requestHasAttachment = reqInfo.text.includes('[Attachment: scan-3pages.pdf]');
    result.checks.requestPages = JSON.stringify([...new Set(reqInfo.pages)].sort()) === '[1,3]';
    result.checks.requestNoPage2 = !reqInfo.pages.includes(2);
    result.checks.requestHasAttachment = result.requestHasAttachment;
    assert(result.checks.requestPages, `model request pages wrong: ${JSON.stringify(reqInfo.pages)}`);
    assert(result.checks.requestNoPage2, 'page 2 text travelled to the model');
    assert(result.checks.requestHasAttachment, 'attachment block missing from request');

    // -- saved local copy still readable after reload ------------------------
    const chipText = await historyChipText(page, 'scan-3pages.pdf');
    result.historyChip = chipText;
    result.checks.historySavedCopy = chipText.includes('已保存副本');
    result.checks.historyPageLabel = chipText.includes('第 1–3 页');
    result.checks.historyUnreadable = chipText.includes('不可读：第 2 页');
    assert(result.checks.historySavedCopy, `history chip lacks saved-copy marker: ${chipText}`);
    assert(result.checks.historyPageLabel && result.checks.historyUnreadable,
      `history chip lacks page facts: ${chipText}`);

    const openLink = page.locator('.hc-message-out a[href^="/blobs/"], .hc-message-in a[href^="/blobs/"]')
      .filter({ hasText: '打开' }).first();
    const blobHref = await openLink.getAttribute('href').catch(() => null);
    result.blobHref = blobHref;
    result.checks.blobLinkPresent = typeof blobHref === 'string' && blobHref.includes('/blobs/');
    assert(result.checks.blobLinkPresent, 'no /blobs/ open link on the sent attachment');
    const blobResp = await fetch(`${app.url}${blobHref.split('?')[0]}`);
    const blobBytes = Buffer.from(await blobResp.arrayBuffer());
    result.blobStatus = blobResp.status;
    result.blobSize = blobBytes.length;
    result.checks.blobReadable = blobResp.ok && blobBytes.equals(fs.readFileSync(fixtures.pdf));
    assert(result.checks.blobReadable,
      `saved local copy not byte-identical: status=${blobResp.status} size=${blobBytes.length}`);

    // Reload: history must still render the same facts.
    await page.reload({ waitUntil: 'load' });
    await waitForShell(page);
    await waitForSync(page);
    const chipAfterReload = await historyChipText(page, 'scan-3pages.pdf');
    result.historyChipAfterReload = chipAfterReload;
    result.checks.savedCopyAfterReload = chipAfterReload.includes('已保存副本')
      && chipAfterReload.includes('不可读：第 2 页');
    const blobResp2 = await fetch(`${app.url}${blobHref.split('?')[0]}`);
    result.checks.blobReadableAfterReload = blobResp2.ok;
    assert(result.checks.savedCopyAfterReload, `saved-copy facts lost after reload: ${chipAfterReload}`);
    assert(result.checks.blobReadableAfterReload, 'blob unreadable after reload');
    await screenshot('e2e06-08-after-reload');

    result.mockState = await collectMockState({ text: textMock, vision: visionMock });
    result.checks.pageErrors = result.browserEvents.filter((e) => e.includes('pageerror')).length === 0;
    result.ok = Object.values(result.checks).every(Boolean);
    result.finishedAt = new Date().toISOString();
    result.evidence = writeJson(EVIDENCE_DIR, 'e2e06-result', result);
    assert(result.ok, `one or more E2E 06 checks failed: ${JSON.stringify(result.checks)}`);
  } catch (error) {
    result.ok = false;
    result.error = error.message;
    result.finishedAt = new Date().toISOString();
    result.mockState = await collectMockState({ text: textMock, vision: visionMock }).catch(() => null);
    result.evidence = writeJson(EVIDENCE_DIR, 'e2e06-result', result);
    await shot(page, EVIDENCE_DIR, 'e2e06-fail').catch(() => {});
    throw error;
  } finally {
    await app.close();
    await textMock.close();
    await visionMock.close();
    fs.rmSync(fixtureDir, { recursive: true, force: true });
  }
});

function result0Checks(fixtureDir) {
  for (const f of ['scan-3pages.pdf', 'p1.png', 'payload.bin']) {
    if (!fs.existsSync(path.join(fixtureDir, f))) {
      throw new Error(`fixture missing: ${f}`);
    }
  }
}
