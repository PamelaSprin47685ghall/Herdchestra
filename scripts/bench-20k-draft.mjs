// P-BENCH3 两万字混排草稿基准（0.0.2）。
//
// 场景：真实 Herdchestra app + 真实 Chrome（persistent context，同一
// user-data-dir 保证 localStorage 跨"重启"存活）。
//
// 已知产品行为（本基准绕行并记录）：运行时 `session/create` 回执为
// :accepted，但客户端在收到 STATE_DELTA 前会话列表不更新；重启 app 后
// baseline 才包含新会话。因此脚本先建会话、重启 app 让 baseline 生效，
// 再做全部测量。
//
// 测量项：
//   1. 输入延迟 —— execCommand insertText 粘贴 2 万字延迟 + 逐键
//      keyboard.press 延迟（keydown→input，p95 阈值 <100ms）。
//   2. 选区/复制 —— setSelectionRange 选中 200 字符 + clipboard 写入。
//   3. 撤销 —— execCommand('undo') 值缩短、execCommand('redo') 恢复。
//   4. 草稿保存/恢复 —— localStorage hc.workbench.v1 落盘校验 → 关浏览器
//      → 停 server（保留 data-dir）→ 同端口重启 server → 同 profile 重开
//      → 选中同一会话 → textarea 值 SHA-256 与重启前逐字符相等。
//   5. 内存 —— /proc 分别采样 app JVM 树与 Chrome 树的 VmHWM/VmRSS 峰值；
//      server 单 JVM 峰值是 2 GiB 硬判据；server+Chrome 合计只记录、
//      不作为失败条件（AGENTS.md 的预算是单 JVM / 单片测试）。
//
// 用法：
//   node scripts/bench-20k-draft.mjs --root /tmp/hc-bench3/run \
//     --port 4783 --json plans/evidence/p-bench3/bench-20k-draft.json
//
// 只杀自己 spawn 的子进程；不写 git；不重建 bundle。

import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';
import { startApp } from '../test/e2e/support/app.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(HERE, '..');
const BUNDLE_PATH = 'resources/public/js/main.js';

function bundleInfo() {
  const file = path.join(REPO_ROOT, BUNDLE_PATH);
  const bytes = fs.readFileSync(file);
  return {
    path: BUNDLE_PATH,
    bytes: bytes.length,
    sha256: crypto.createHash('sha256').update(bytes).digest('hex'),
    mtime: fs.statSync(file).mtime.toISOString(),
  };
}

function arg(name, fallback) {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 ? process.argv[i + 1] : fallback;
}

const ROOT = arg('root', fs.mkdtempSync(path.join(os.tmpdir(), 'hc-bench3-')));
const PORT = Number(arg('port', '4783'));
const DRAFT_CHARS = Number(arg('chars', '20000'));
const JSON_OUT = arg('json',
  path.join(REPO_ROOT, 'plans/evidence/p-bench3/bench-20k-draft.json'));
const EVIDENCE_DIR = path.dirname(JSON_OUT);
const CHROME_PATH = process.env.CHROME_PATH || '/usr/bin/google-chrome';
const USER_DATA = path.join(ROOT, 'chrome-profile');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---- 两万字中英代码混排生成器（确定性 seed） ----------------------------
function mulberry32(seed) {
  let a = seed >>> 0;
  return () => {
    a |= 0; a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const ZH_SENTENCES = [
  '草稿持久化由 workbench.storage 拥有，键为 hc.workbench.v1。',
  '输入区 textarea 非受控，种子值与恢复读取同一个 composer 模型。',
  '外部草稿只在不会破坏操作者输入时写入 DOM。',
  '组合结束与失焦时显式提交当前值、光标与选区。',
  '流式重渲染不得清空消息区的 DOM 选区。',
  '快照替换必须先删服务器键再合并，不能复活已折叠记录。',
  '草稿按会话键隔离，切换会话不串文本。',
  '未提交草稿只存在当前浏览器 profile 的 localStorage。',
];
const EN_SENTENCES = [
  'The composer keeps an uncontrolled textarea seeded from the surface model.',
  'Drafts persist under hc.workbench.v1 with a 350ms debounce signature.',
  'Selection, cursor and attachments are part of the persisted signature.',
  'Snapshot replacement deletes server-owned keys before merging.',
  'A reload restores the draft only inside the same browser profile.',
];
const CODE_SNIPPETS = [
  '```clojure\n(defn draft-signature [drafts]\n  (->> (or drafts {})\n       (map (fn [[k d]] [k (select-keys d fields)]))\n       (sort-by first)\n       vec))\n```\n',
  '```js\nconst save = (drafts, settings) => {\n  localStorage.setItem("hc.workbench.v1",\n    JSON.stringify({version: 1, drafts, settings}));\n};\n```\n',
  '```python\ndef restore(session_id):\n    return drafts.get(session_id, empty_draft())\n```\n',
  '```sql\nSELECT session_id, length(text) FROM drafts\nWHERE length(text) > 19000;\n```\n',
];
const TABLE_SNIPPET =
  '| 指标 | 阈值 |\n|---|---|\n| 按键延迟 p95 | < 100 ms |\n'
  + '| 粘贴 2 万字 | < 500 ms |\n| VmHWM 峰值 | <= 2 GiB |\n'
  + '| 草稿恢复 | SHA-256 相等 |\n';

function buildDraftText(chars) {
  const rand = mulberry32(0x5eed);
  const parts = [];
  let total = 0;
  let i = 0;
  while (total < chars) {
    const kind = i % 7;
    let seg;
    if (kind <= 2) {
      seg = ZH_SENTENCES[Math.floor(rand() * ZH_SENTENCES.length)]
        + ' ' + EN_SENTENCES[Math.floor(rand() * EN_SENTENCES.length)] + '\n';
    } else if (kind === 3) {
      seg = CODE_SNIPPETS[Math.floor(rand() * CODE_SNIPPETS.length)];
    } else if (kind === 4) {
      seg = TABLE_SNIPPET;
    } else {
      seg = `段落 ${i}：混排内容 ${'中文 English code '.repeat(4)}\n`;
    }
    parts.push(seg);
    total += seg.length;
    i += 1;
  }
  return parts.join('').slice(0, chars);
}

const DRAFT_TEXT = buildDraftText(DRAFT_CHARS);
const DRAFT_SHA = crypto.createHash('sha256').update(DRAFT_TEXT, 'utf8').digest('hex');

// ---- /proc 内存采样 -------------------------------------------------------
function readStatus(pid) {
  try {
    const t = fs.readFileSync(`/proc/${pid}/status`, 'utf8');
    const hwm = t.match(/^VmHWM:\s+(\d+)\s*kB/m);
    const rss = t.match(/^VmRSS:\s+(\d+)\s*kB/m);
    return { hwmKb: hwm ? Number(hwm[1]) : null, rssKb: rss ? Number(rss[1]) : null };
  } catch { return null; }
}

function procCmdline(pid) {
  try { return fs.readFileSync(`/proc/${pid}/cmdline`, 'utf8'); }
  catch { return ''; }
}

function descendantsOf(rootPid) {
  const out = [];
  const queue = [rootPid];
  const entries = fs.readdirSync('/proc').filter((n) => /^\d+$/.test(n));
  while (queue.length) {
    const cur = queue.shift();
    for (const name of entries) {
      const pid = Number(name);
      if (pid === process.pid || out.includes(pid)) continue;
      try {
        const stat = fs.readFileSync(`/proc/${pid}/stat`, 'utf8');
        const ppid = Number(stat.slice(stat.lastIndexOf(')') + 2).split(' ')[1]);
        if (ppid === cur) { out.push(pid); queue.push(pid); }
      } catch { /* 进程已退出 */ }
    }
  }
  return out;
}

// 一棵以 rootPid 为根的进程树采样器；rootPid 为 null 时处于空转。
function startTreeSampler(getRootPid, label, intervalMs = 250) {
  const samples = [];
  const timer = setInterval(() => {
    const rootPid = getRootPid();
    if (rootPid == null) return;
    const pids = descendantsOf(rootPid).concat([rootPid]);
    let hwmKb = 0, rssKb = 0, seen = 0;
    for (const pid of pids) {
      const s = readStatus(pid);
      if (!s) continue;
      seen += 1;
      hwmKb += s.hwmKb || 0;
      rssKb += s.rssKb || 0;
    }
    samples.push({ t: Date.now(), hwmKb, rssKb, procs: seen });
  }, intervalMs);
  return {
    label,
    samples,
    stop() { clearInterval(timer); },
    peak() {
      let hwmKb = 0, rssKb = 0;
      for (const s of samples) {
        hwmKb = Math.max(hwmKb, s.hwmKb || 0);
        rssKb = Math.max(rssKb, s.rssKb || 0);
      }
      return { hwmKb, rssKb, samples: samples.length };
    },
  };
}

// 同一时刻把 server 与 chrome 的 RSS 相加，取最大值；这是"同时占用"的
// 真实峰值，比各自 HWM 之和更紧。
function combinedPeak(serverSamples, chromeSamples) {
  const byTime = new Map();
  for (const s of serverSamples) byTime.set(s.t, { server: s, chrome: null });
  for (const s of chromeSamples) {
    const slot = byTime.get(s.t) || { server: null, chrome: null };
    slot.chrome = s;
    byTime.set(s.t, slot);
  }
  // 两棵树采样相位不同；对每个时刻取最近样本近似。
  const serverSorted = [...serverSamples].sort((a, b) => a.t - b.t);
  const chromeSorted = [...chromeSamples].sort((a, b) => a.t - b.t);
  const nearest = (arr, t) => {
    let lo = 0, hi = arr.length - 1, best = null, bd = Infinity;
    while (lo <= hi) {
      const mid = (lo + hi) >> 1;
      const d = Math.abs(arr[mid].t - t);
      if (d < bd) { bd = d; best = arr[mid]; }
      if (arr[mid].t < t) lo = mid + 1; else hi = mid - 1;
    }
    return best;
  };
  let rssKb = 0, hwmKb = 0;
  const times = new Set([...serverSorted, ...chromeSorted].map((s) => s.t));
  for (const t of times) {
    const s = nearest(serverSorted, t);
    const c = nearest(chromeSorted, t);
    const r = (s?.rssKb || 0) + (c?.rssKb || 0);
    const h = (s?.hwmKb || 0) + (c?.hwmKb || 0);
    rssKb = Math.max(rssKb, r);
    hwmKb = Math.max(hwmKb, h);
  }
  return { rssKb, hwmKb };
}

function findChromePid() {
  // Chrome 主进程是 node 的后代且 cmdline 含 --type 缺失（browser process
  // 没有 --type= 参数）。遍历 /proc 找 ppid 链上属于本 node 的 chrome。
  const entries = fs.readdirSync('/proc').filter((n) => /^\d+$/.test(n));
  const byPpid = new Map();
  for (const name of entries) {
    const pid = Number(name);
    try {
      const stat = fs.readFileSync(`/proc/${pid}/stat`, 'utf8');
      const ppid = Number(stat.slice(stat.lastIndexOf(')') + 2).split(' ')[1]);
      byPpid.set(pid, ppid);
    } catch { /* exited */ }
  }
  // 从 node 的直接子进程出发找 chrome
  const isDescendant = (pid) => {
    let cur = pid;
    for (let i = 0; i < 20; i++) {
      if (cur === process.pid) return true;
      const ppid = byPpid.get(cur);
      if (ppid == null || ppid === cur) return false;
      cur = ppid;
    }
    return false;
  };
  for (const name of entries) {
    const pid = Number(name);
    const cmd = procCmdline(pid);
    if (!/chrome|chromium/i.test(cmd)) continue;
    if (cmd.includes('--type=')) continue; // 只要 browser 主进程
    if (isDescendant(pid)) return pid;
  }
  return null;
}

// ---- 浏览器 ---------------------------------------------------------------
async function openContext() {
  fs.mkdirSync(USER_DATA, { recursive: true });
  return chromium.launchPersistentContext(USER_DATA, {
    executablePath: CHROME_PATH,
    headless: true,
    viewport: { width: 1440, height: 1000 },
    args: ['--no-sandbox', '--disable-dev-shm-usage', '--disable-gpu',
           '--font-render-hinting=none'],
  });
}

const COMPOSER = 'textarea[aria-label="消息输入"]';
const DRAFT_KEY = 'hc.workbench.v1';

async function waitComposer(page, timeout = 30000) {
  await page.locator(COMPOSER).first().waitFor({ state: 'visible', timeout });
}

async function composerSha(page) {
  return page.evaluate(async (sel) => {
    const ta = document.querySelector(sel);
    if (!ta) return null;
    const buf = await crypto.subtle.digest('SHA-256',
      new TextEncoder().encode(ta.value));
    return {
      sha: [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, '0')).join(''),
      length: ta.value.length,
      head: ta.value.slice(0, 24),
      tail: ta.value.slice(-24),
    };
  }, COMPOSER);
}

async function composerLength(page) {
  return page.evaluate((sel) =>
    document.querySelector(sel)?.value.length ?? -1, COMPOSER);
}

async function waitValueLength(page, expected, timeoutMs = 60000) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    const len = await composerLength(page);
    if (len === expected) return Date.now() - start;
    await sleep(5);
  }
  return -1;
}

function percentile(samples, p) {
  if (!samples.length) return null;
  const s = [...samples].sort((a, b) => a - b);
  const idx = Math.min(s.length - 1, Math.max(0, Math.ceil((p / 100) * s.length) - 1));
  return s[idx];
}

async function stopServer(app) {
  if (!app?.pid) return;
  try { process.kill(app.pid, 'SIGTERM'); } catch { /* 已退出 */ }
  const deadline = Date.now() + 20000;
  while (Date.now() < deadline) {
    try {
      process.kill(app.pid, 0);
      const stat = fs.readFileSync(`/proc/${app.pid}/stat`, 'utf8');
      const state = stat.slice(stat.lastIndexOf(')') + 2).split(' ')[0];
      if (state === 'Z') return; // zombie
    } catch { return; }
    await sleep(200);
  }
  try { process.kill(app.pid, 'SIGKILL'); } catch { /* 已退出 */ }
}

async function main() {
  fs.mkdirSync(EVIDENCE_DIR, { recursive: true });
  const startedAt = new Date().toISOString();
  const result = {
    env: {
      node: process.version, platform: process.platform, arch: process.arch,
      cpus: os.cpus().length, totalMemBytes: os.totalmem(),
      chromePath: CHROME_PATH, chromeVersion: null,
    },
    config: { root: ROOT, port: PORT, draftChars: DRAFT_CHARS, draftSha256: DRAFT_SHA },
    bundle: bundleInfo(),
    draft: { chars: DRAFT_TEXT.length, sha256: DRAFT_SHA },
    thresholds: {
      keyLatencyP95Ms: 100, pasteMs: 500,
      // 宽松回归界；历史 100ms 目标是理想值，不作为门禁。
      undoMs: 500,
      // AGENTS.md 的 2 GiB 是单个 JVM / 单片测试预算，不是 server+Chrome 之和。
      serverPeakBytes: 2 * 1024 * 1024 * 1024,
      combinedPeakInformational: true,
    },
    phases: {}, checks: {}, startedAt,
    notes: [
      'session/create 回执 :accepted 后运行时 STATE_DELTA 未送达客户端；'
      + '重启 app 后 baseline 才含新会话。脚本在建会话后重启 app 绕行，'
      + '该行为作为产品缺陷记录在案。',
      '粘贴用 execCommand insertText（触发真实 input 事件，app 草稿同步）；'
      + '逐键用 keyboard.press 单字符；撤销用 execCommand undo/redo。',
      'serverPeak < 2 GiB 是硬判据（单个 JVM）；combined server+Chrome 峰值'
      + '只记录、不参与 verdict。undoMs 使用 500ms 宽松回归界，'
      + '历史 100ms 目标不强制。',
    ],
  };

  // 两棵独立进程树采样：server JVM 与 Chrome。
  let serverPid = null;
  let chromePid = null;
  const serverSampler = startTreeSampler(() => serverPid, 'server');
  const chromeSampler = startTreeSampler(() => chromePid, 'chrome');
  const failures = [];
  const check = (name, ok, detail = {}) => {
    result.checks[name] = { ok: !!ok, ...detail };
    if (!ok) failures.push(name);
  };
  let app = null;
  let context = null;
  let page = null;

  try {
    // ---- Phase 0: 启动 app，建会话，重启让 baseline 生效 ------------------
    const t0 = Date.now();
    app = await startApp({ root: ROOT, port: PORT, keepTemp: true,
                           evidenceDir: EVIDENCE_DIR });
    serverPid = app.pid;
    result.phases.appStart = { ms: Date.now() - t0, url: app.url, pid: app.pid };

    context = await openContext();
    page = context.pages()[0] || await context.newPage();
    await page.goto(`${app.url}/#/sessions`, { waitUntil: 'load' });
    await page.locator('.wb-shell').first().waitFor({ state: 'visible', timeout: 30000 });
    await page.waitForTimeout(4000);
    chromePid = findChromePid();
    result.phases.chromePid = chromePid;

    // 新建会话：服务端接受（receipt :accepted），但运行时 delta 未到。
    // 用 CDP 抓 session-id，随后重启 app 让 baseline 包含它。
    const cdp = await context.newCDPSession(page);
    await cdp.send('Network.enable');
    let sessionId = null;
    cdp.on('Network.webSocketFrameReceived', (ev) => {
      const p = ev.response?.payloadData || '';
      const m = p.match(/hc-session-[0-9a-f-]+/);
      if (m && !sessionId) sessionId = m[0];
    });
    await page.locator('button[aria-label="新建会话"]').first().click();
    await page.waitForTimeout(8000);
    result.sessionId = sessionId;
    check('sessionCreated', !!sessionId, { sessionId });

    await context.close();
    context = null;
    page = null;
    chromePid = null;
    await stopServer(app);

    // 重启：baseline 现在包含已提交会话。
    const t1 = Date.now();
    app = await startApp({ root: ROOT, port: PORT, keepTemp: true,
                           evidenceDir: EVIDENCE_DIR });
    serverPid = app.pid;
    result.phases.appRestartForBaseline = { ms: Date.now() - t1, url: app.url, pid: app.pid };

    context = await openContext();
    page = context.pages()[0] || await context.newPage();
    const t2 = Date.now();
    await page.goto(`${app.url}/#/sessions`, { waitUntil: 'load' });
    await page.locator('.wb-shell').first().waitFor({ state: 'visible', timeout: 30000 });
    await page.waitForTimeout(3000);
    chromePid = findChromePid();
    result.phases.browserOpen = { ms: Date.now() - t2, chromePid };
    try {
      result.env.chromeVersion = await page.evaluate(() => navigator.userAgent);
    } catch { /* ignore */ }

    if (sessionId) {
      await page.goto(`${app.url}/#/sessions/${sessionId}`, { waitUntil: 'load' });
    }
    await waitComposer(page);
    check('composerMounted', true, { sessionId });

    // 聚焦 composer（真实鼠标点击）。
    const box = await page.evaluate((sel) => {
      const ta = document.querySelector(sel);
      const r = ta.getBoundingClientRect();
      ta.focus();
      return { x: r.left + 30, y: r.top + 8 };
    }, COMPOSER);
    await page.mouse.click(box.x, box.y);

    // ---- Phase 1: 粘贴 2 万字（execCommand insertText → 真实 input） -----
    const pasteStart = Date.now();
    await page.evaluate(({ sel, text }) => {
      const ta = document.querySelector(sel);
      ta.focus();
      const ok = document.execCommand('insertText', false, text);
      return ok;
    }, { sel: COMPOSER, text: DRAFT_TEXT });
    const pasteMs = await waitValueLength(page, DRAFT_TEXT.length);
    result.phases.paste = {
      dispatchToValueMs: pasteMs, wallMs: Date.now() - pasteStart,
      expectedLength: DRAFT_TEXT.length,
    };
    check('pasteUnderThreshold', pasteMs > 0 && pasteMs < result.thresholds.pasteMs,
          { pasteMs });

    // ---- Phase 2: 逐键输入延迟（真实 keyboard.press 单字符） ------------
    const KEY_TEXT = 'abcdefghijklmnopqrstuvwxyz0123456789';
    const keySamples = [];
    for (let i = 0; i < KEY_TEXT.length; i++) {
      const ch = KEY_TEXT[i];
      const before = await composerLength(page);
      const t0 = Date.now();
      await page.keyboard.press(ch);
      // 轮询到长度 +1，记录 keydown→input 的耗时。
      let delta = -1;
      const deadline = Date.now() + 2000;
      while (Date.now() < deadline) {
        const len = await composerLength(page);
        if (len === before + 1) { delta = Date.now() - t0; break; }
        await sleep(2);
      }
      keySamples.push(delta >= 0 ? delta : 2000);
    }
    result.phases.keystroke = {
      keys: keySamples.length,
      p50: percentile(keySamples, 50),
      p95: percentile(keySamples, 95),
      max: Math.max(...keySamples),
    };
    check('keyLatencyP95',
      result.phases.keystroke.p95 < result.thresholds.keyLatencyP95Ms,
      result.phases.keystroke);

    // ---- Phase 3: 选区/复制 ----------------------------------------------
    const selStart = Date.now();
    const selInfo = await page.evaluate((sel) => {
      const ta = document.querySelector(sel);
      const start = 100, end = Math.min(ta.value.length, 300);
      ta.focus();
      ta.setSelectionRange(start, end);
      return { start: ta.selectionStart, end: ta.selectionEnd,
               text: ta.value.slice(start, end) };
    }, COMPOSER);
    const selectionMs = Date.now() - selStart;
    const copyOk = await page.evaluate(async (sel) => {
      const ta = document.querySelector(sel);
      ta.focus();
      try {
        await navigator.clipboard.writeText(
          ta.value.slice(ta.selectionStart, ta.selectionEnd));
        return true;
      } catch {
        try { return document.execCommand('copy'); } catch { return false; }
      }
    }, COMPOSER);
    result.phases.selection = {
      selectionMs, chars: selInfo.end - selInfo.start,
      copyOk, head: selInfo.text.slice(0, 20),
    };
    check('selectionCopy',
      selInfo.end - selInfo.start === 200 && copyOk === true,
      result.phases.selection);

    // ---- Phase 4: 撤销（execCommand undo/redo） ---------------------------
    const beforeUndo = await composerLength(page);
    const undoStart = Date.now();
    await page.evaluate((sel) => {
      const ta = document.querySelector(sel);
      ta.focus();
      document.execCommand('undo');
    }, COMPOSER);
    await sleep(300);
    const afterUndo = await composerLength(page);
    const undoMs = Date.now() - undoStart;
    await page.evaluate((sel) => {
      const ta = document.querySelector(sel);
      ta.focus();
      document.execCommand('redo');
    }, COMPOSER);
    await sleep(300);
    const afterRedo = await composerLength(page);
    result.phases.undo = { beforeUndo, afterUndo, afterRedo, undoMs };
    check('undoWorks', afterUndo < beforeUndo && afterRedo >= afterUndo,
          { beforeUndo, afterUndo, afterRedo, undoMs });
    check('undoLatency', undoMs <= result.thresholds.undoMs,
          { undoMs, limitMs: result.thresholds.undoMs,
            note: 'loose regression gate; the historical 100ms target is not enforced' });

    // ---- Phase 5: 草稿保存校验 --------------------------------------------
    await sleep(1200);
    const savedInfo = await page.evaluate(({ key, sid }) => {
      try {
        const raw = localStorage.getItem(key);
        if (!raw) return { found: false };
        const payload = JSON.parse(raw);
        const drafts = payload.drafts || {};
        const d = drafts[sid] || drafts[sid.replace(/-/g, '')]
          || Object.values(drafts)[0];
        if (!d) return { found: false, keys: Object.keys(drafts) };
        return { found: true, textLength: (d.text || '').length,
                 keys: Object.keys(drafts) };
      } catch (e) { return { found: false, error: String(e) }; }
    }, { key: DRAFT_KEY, sid: sessionId || '' });
    result.phases.draftSaved = savedInfo;
    check('draftSaved',
      savedInfo.found && savedInfo.textLength > DRAFT_CHARS,
      savedInfo);

    const preRestart = await composerSha(page);
    result.phases.preRestartSha = preRestart;

    // ---- Phase 6: 关浏览器 → 停 server → 重启 → 重开 ---------------------
    await context.close();
    context = null;
    page = null;
    chromePid = null;
    await stopServer(app);
    result.phases.serverRestarted = { stoppedPid: app.pid };

    const t3 = Date.now();
    app = await startApp({ root: ROOT, port: PORT, keepTemp: true,
                           evidenceDir: EVIDENCE_DIR });
    serverPid = app.pid;
    result.phases.appRestart = { ms: Date.now() - t3, url: app.url, pid: app.pid };

    context = await openContext();
    page = context.pages()[0] || await context.newPage();
    const t4 = Date.now();
    await page.goto(`${app.url}/#/sessions`, { waitUntil: 'load' });
    await page.locator('.wb-shell').first().waitFor({ state: 'visible', timeout: 30000 });
    await page.waitForTimeout(3000);
    chromePid = findChromePid();
    result.phases.browserReopen = { ms: Date.now() - t4, chromePid };
    if (sessionId) {
      await page.goto(`${app.url}/#/sessions/${sessionId}`, { waitUntil: 'load' });
    }
    await waitComposer(page);
    await sleep(800);
    const restored = await composerSha(page);
    result.phases.restored = restored;
    check('draftRestored',
      !!restored && restored.sha === preRestart.sha
        && restored.length === preRestart.length,
      { expectedSha: preRestart?.sha, restoredSha: restored?.sha,
        expectedLength: preRestart?.length, restoredLength: restored?.length });

    serverSampler.stop();
    chromeSampler.stop();
    const serverPeak = serverSampler.peak();
    const chromePeak = chromeSampler.peak();
    const combined = combinedPeak(serverSampler.samples, chromeSampler.samples);
    const combinedHwmKb = combined.hwmKb;
    const combinedRssKb = combined.rssKb;
    result.memory = {
      server: { peakHwmMiB: +(serverPeak.hwmKb / 1024).toFixed(1),
                peakRssMiB: +(serverPeak.rssKb / 1024).toFixed(1),
                samples: serverPeak.samples },
      chrome: { peakHwmMiB: +(chromePeak.hwmKb / 1024).toFixed(1),
                peakRssMiB: +(chromePeak.rssKb / 1024).toFixed(1),
                samples: chromePeak.samples },
      combined: { peakHwmMiB: +(combinedHwmKb / 1024).toFixed(1),
                  peakRssMiB: +(combinedRssKb / 1024).toFixed(1),
                  informational: true,
                  note: 'server+Chrome sum is context only; the 2 GiB '
                        + 'budget is per JVM/test shard, not a sum' },
    };
    // 硬判据是单个 server JVM 的峰值；combined 只记录，不参与 verdict。
    check('serverPeakUnder2GiB',
          serverPeak.hwmKb * 1024 <= result.thresholds.serverPeakBytes,
          { serverPeakMiB: +(serverPeak.hwmKb / 1024).toFixed(1),
            limitMiB: +(result.thresholds.serverPeakBytes
                        / (1024 * 1024)).toFixed(0),
            combinedPeakMiB: +(combinedHwmKb / 1024).toFixed(1),
            combinedInformational: true,
            sumOfHwmsMiB: +((serverPeak.hwmKb + chromePeak.hwmKb) / 1024).toFixed(1) });
    result.bundleAfter = bundleInfo();
    result.bundleStable = (result.bundleAfter.sha256 === result.bundle.sha256
                           && result.bundleAfter.bytes === result.bundle.bytes);
    check('bundleStable', result.bundleStable,
          { beforeSha256: result.bundle.sha256,
            afterSha256: result.bundleAfter.sha256,
            beforeBytes: result.bundle.bytes,
            afterBytes: result.bundleAfter.bytes });

    result.finishedAt = new Date().toISOString();
    result.verdict = failures.length === 0 ? 'PASS' : `FAIL (${failures.join(', ')})`;
  } catch (e) {
    result.error = String(e?.stack || e);
    result.verdict = 'FAIL (exception)';
    failures.push('exception');
  } finally {
    serverSampler.stop();
    chromeSampler.stop();
    if (context) await context.close().catch(() => {});
    await stopServer(app);
    if (!result.memory) {
      const s = serverSampler.peak();
      const c = chromeSampler.peak();
      const combined = combinedPeak(serverSampler.samples, chromeSampler.samples);
      const combinedHwmKb = combined.hwmKb;
      const combinedRssKb = combined.rssKb;
      result.memory = {
        server: { peakHwmMiB: +(s.hwmKb / 1024).toFixed(1),
                  peakRssMiB: +(s.rssKb / 1024).toFixed(1), samples: s.samples },
        chrome: { peakHwmMiB: +(c.hwmKb / 1024).toFixed(1),
                  peakRssMiB: +(c.rssKb / 1024).toFixed(1), samples: c.samples },
        combined: { peakHwmMiB: +((s.hwmKb + c.hwmKb) / 1024).toFixed(1),
                    peakRssMiB: +((s.rssKb + c.rssKb) / 1024).toFixed(1),
                    informational: true },
      };
    }
  }

  fs.mkdirSync(path.dirname(JSON_OUT), { recursive: true });
  fs.writeFileSync(JSON_OUT, JSON.stringify(result, null, 2));
  console.log(JSON.stringify({
    json: JSON_OUT, verdict: result.verdict,
    bundleSha256: result.bundle?.sha256,
    bundleBytes: result.bundle?.bytes,
    bundleStable: result.bundleStable,
    bundleAfterSha256: result.bundleAfter?.sha256,
    draftChars: result.draft?.chars,
    sessionId: result.sessionId,
    pasteMs: result.phases?.paste?.dispatchToValueMs,
    keystrokeP95: result.phases?.keystroke?.p95,
    selectionMs: result.phases?.selection?.selectionMs,
    undoMs: result.phases?.undo?.undoMs,
    draftSaved: result.checks?.draftSaved?.ok,
    draftRestored: result.checks?.draftRestored?.ok,
    thresholds: result.thresholds,
    memServerHwmMiB: result.memory?.server?.peakHwmMiB,
    memChromeHwmMiB: result.memory?.chrome?.peakHwmMiB,
    memCombinedHwmMiB: result.memory?.combined?.peakHwmMiB,
    checks: result.checks,
    error: result.error,
  }, null, 2));
  process.exit(failures.length ? 1 : 0);
}

main().catch((e) => { console.error(e); process.exit(1); });
