#!/usr/bin/env node
/*
 * Real-browser §8.5 probe for a 20,000-character mixed draft.
 *
 * Measures insertion, selection, native undo, debounce persistence, and a
 * reload recovery.  It uses CDP only; no product source is changed.
 *
 *   node test/js/scale_draft_probe.cjs --url http://127.0.0.1:7581/#/sessions \
 *     --out plans/evidence/scale-draft
 */
const {spawn} = require('child_process');
const fs = require('fs');
const http = require('http');
const path = require('path');
const WebSocket = require('ws');

const args = process.argv.slice(2);
const opt = (name, fallback) => {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : fallback;
};
const URL = opt('--url', 'http://127.0.0.1:7581/#/sessions');
const OUT = path.resolve(opt('--out', 'plans/evidence/scale-draft'));
const PORT = Number(opt('--debug-port', '9224'));
const CHROME = opt('--chrome', 'google-chrome');
fs.mkdirSync(OUT, {recursive: true});
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function getJson(pathname) {
  return new Promise((resolve, reject) => {
    const req = http.get({host: '127.0.0.1', port: PORT, path: pathname}, (res) => {
      let body = '';
      res.setEncoding('utf8');
      res.on('data', (d) => (body += d));
      res.on('end', () => {
        try { resolve(JSON.parse(body)); } catch (e) { reject(e); }
      });
    });
    req.on('error', reject);
    req.setTimeout(2000, () => req.destroy(new Error('timeout')));
  });
}

async function waitFor(fn, timeoutMs, label) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    const value = await fn();
    if (value) return value;
    await sleep(200);
  }
  throw new Error('timeout waiting for ' + label);
}

async function main() {
  const userData = path.join(OUT, 'chrome-profile');
  fs.rmSync(userData, {recursive: true, force: true});
  const chrome = spawn(CHROME, [
    '--headless=new', '--no-sandbox', '--disable-gpu',
    '--disable-dev-shm-usage', '--window-size=1280,800',
    '--remote-debugging-port=' + PORT, '--user-data-dir=' + userData,
    'about:blank',
  ], {stdio: 'ignore'});
  let ws;
  try {
    await waitFor(() => getJson('/json/version').catch(() => null),
                   15000, 'chrome');
    const targets = await waitFor(() => getJson('/json/list').catch(() => null),
                                  15000, 'targets');
    const page = targets.find((t) => t.type === 'page');
    ws = new WebSocket(page.webSocketDebuggerUrl);
    await new Promise((res, rej) => {
      ws.once('open', res);
      ws.once('error', rej);
    });
    let nextId = 1;
    const pending = new Map();
    ws.on('message', (raw) => {
      const msg = JSON.parse(raw.toString());
      if (msg.id && pending.has(msg.id)) {
        const {resolve, reject} = pending.get(msg.id);
        pending.delete(msg.id);
        if (msg.error) reject(new Error(JSON.stringify(msg.error)));
        else resolve(msg.result);
      }
    });
    const send = (method, params = {}) => new Promise((resolve, reject) => {
      const id = nextId++;
      pending.set(id, {resolve, reject});
      ws.send(JSON.stringify({id, method, params}));
    });
    const evalJs = async (expression) => {
      const r = await send('Runtime.evaluate', {
        expression, returnByValue: true, awaitPromise: true,
      });
      if (r.exceptionDetails) throw new Error(r.exceptionDetails.text || 'eval');
      return r.result.value;
    };

    await send('Page.enable');
    await send('Runtime.enable');
    await send('Performance.enable');
    await send('Page.navigate', {url: URL});
    await waitFor(() => evalJs("!!document.querySelector('.wb-shell')"),
                  15000, 'shell');
    await waitFor(
      () => evalJs("document.querySelectorAll('.wb-session-item').length > 0"),
      20000, 'session');
    await evalJs(`(() => {
      const b = document.querySelector('.wb-session-item .wb-list-button');
      if (b) b.click();
      return !!b;
    })()`);
    await waitFor(() => evalJs("!!document.querySelector('.hc-composer textarea')"),
                  20000, 'composer');

    // Focus the composer through a real mouse click so native editing owns it.
    const box = await evalJs(`(() => {
      const ta = document.querySelector('.hc-composer textarea');
      const r = ta.getBoundingClientRect();
      return {x: r.left + 20, y: r.top + 10};
    })()`);
    await send('Input.dispatchMouseEvent',
               {type: 'mousePressed', x: box.x, y: box.y, button: 'left', clickCount: 1});
    await send('Input.dispatchMouseEvent',
               {type: 'mouseReleased', x: box.x, y: box.y, button: 'left', clickCount: 1});

    const big = Array.from({length: 10000}, (_, i) =>
      i % 7 === 0 ? '```js\nconst x = 1;\n```\n' : '中a').join('').slice(0, 20000);
    const t0 = Date.now();
    await send('Input.insertText', {text: big});
    const insertMs = Date.now() - t0;
    await sleep(600);

    const afterInsert = await evalJs(`(() => {
      const ta = document.querySelector('.hc-composer textarea');
      const saved = localStorage.getItem('hc.workbench.v1');
      const hint = document.querySelector('.hc-composer-hint');
      return {
        length: ta.value.length,
        selectionStart: ta.selectionStart,
        selectionEnd: ta.selectionEnd,
        savedBytes: saved ? saved.length : 0,
        hint: hint ? hint.innerText : null,
        bodyHasSaved: /草稿暂未保存/.test(document.body.innerText),
      };
    })()`);

    // Select a 100-character range and measure the selection update.
    const selStart = Date.now();
    await evalJs(`(() => {
      const ta = document.querySelector('.hc-composer textarea');
      ta.focus(); ta.setSelectionRange(100, 200);
      return [ta.selectionStart, ta.selectionEnd];
    })()`);
    const selectionMs = Date.now() - selStart;

    // Native undo: Ctrl+Z on the focused textarea.
    const beforeUndo = await evalJs(
      "document.querySelector('.hc-composer textarea').value.length");
    const undoStart = Date.now();
    await send('Input.dispatchKeyEvent', {
      type: 'keyDown', key: 'z', code: 'KeyZ',
      windowsVirtualKeyCode: 90, nativeVirtualKeyCode: 90, modifiers: 2,
    });
    await send('Input.dispatchKeyEvent', {
      type: 'keyUp', key: 'z', code: 'KeyZ',
      windowsVirtualKeyCode: 90, nativeVirtualKeyCode: 90, modifiers: 2,
    });
    const undoMs = Date.now() - undoStart;
    await sleep(400);
    const afterUndo = await evalJs(
      "document.querySelector('.hc-composer textarea').value.length");

    // Ensure a known draft is persisted, then reload for crash recovery.
    await evalJs(`(() => {
      const ta = document.querySelector('.hc-composer textarea');
      if (ta.value.length < 20000) {
        const setter = Object.getOwnPropertyDescriptor(
          HTMLTextAreaElement.prototype, 'value').set;
        setter.call(ta, ${JSON.stringify(big)});
        ta.dispatchEvent(new Event('input', {bubbles: true}));
      }
      return ta.value.length;
    })()`);
    await sleep(900);
    const savedBeforeReload = await evalJs(
      "localStorage.getItem('hc.workbench.v1') ? localStorage.getItem('hc.workbench.v1').length : 0");
    await send('Page.reload', {ignoreCache: false});
    await waitFor(() => evalJs("!!document.querySelector('.wb-shell')"),
                  15000, 'shell after reload');
    await waitFor(() => evalJs("!!document.querySelector('.hc-composer textarea')"),
                  20000, 'composer after reload');
    await sleep(600);
    const afterReload = await evalJs(`(() => {
      const ta = document.querySelector('.hc-composer textarea');
      return {
        length: ta.value.length,
        head: ta.value.slice(0, 20),
        savedBytes: localStorage.getItem('hc.workbench.v1')
          ? localStorage.getItem('hc.workbench.v1').length : 0,
      };
    })()`);

    const metrics = await send('Performance.getMetrics');
    const metric = (name) =>
      (metrics.metrics.find((m) => m.name === name) || {}).value;
    const shot = await send('Page.captureScreenshot', {format: 'png'});
    fs.writeFileSync(path.join(OUT, 'scale-draft.png'),
                     Buffer.from(shot.data, 'base64'));
    const report = {
      url: URL,
      capturedAt: new Date().toISOString(),
      insertMs,
      selectionMs,
      undoMs,
      beforeUndo,
      afterUndo,
      afterInsert,
      savedBeforeReload,
      afterReload,
      metrics: {
        JSHeapUsedSize: metric('JSHeapUsedSize'),
        Nodes: metric('Nodes'),
        LayoutCount: metric('LayoutCount'),
      },
    };
    fs.writeFileSync(path.join(OUT, 'scale-draft.json'),
                     JSON.stringify(report, null, 2));
    console.log(JSON.stringify(report, null, 2));
  } finally {
    try { ws && ws.close(); } catch (_) {}
    chrome.kill('SIGKILL');
  }
}

main().catch((err) => {
  console.error(err.stack || String(err));
  process.exit(1);
});
