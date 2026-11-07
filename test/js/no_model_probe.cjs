#!/usr/bin/env node
/*
 * Real-browser probe for the no-model release gate.
 *
 * Starts headless Chrome, connects over CDP, waits for the real workbench to
 * synchronize, creates a session if the server has none, types a draft, and
 * inspects the send button / visible reason.  It never calls the model and
 * never fabricates a model.
 *
 * Usage:
 *   node test/js/no_model_probe.cjs --url http://127.0.0.1:7581/#/sessions \
 *     --out plans/evidence/no-model
 *
 * Requires the `ws` package already used by the repository's JS harnesses.
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
const OUT = path.resolve(opt('--out', 'plans/evidence/no-model'));
const PORT = Number(opt('--debug-port', '9223'));
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
    '--headless=new',
    '--no-sandbox',
    '--disable-gpu',
    '--disable-dev-shm-usage',
    '--window-size=1280,800',
    '--remote-debugging-port=' + PORT,
    '--user-data-dir=' + userData,
    'about:blank',
  ], {stdio: 'ignore'});

  let ws;
  try {
    const version = await waitFor(
      () => getJson('/json/version').catch(() => null), 15000, 'chrome');
    const targets = await waitFor(
      () => getJson('/json/list').catch(() => null), 15000, 'targets');
    const page = targets.find((t) => t.type === 'page');
    if (!page) throw new Error('no page target');
    ws = new WebSocket(page.webSocketDebuggerUrl);
    await new Promise((resolve, reject) => {
      ws.once('open', resolve);
      ws.once('error', reject);
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
      if (r.exceptionDetails) {
        throw new Error(r.exceptionDetails.text || 'evaluate failed');
      }
      return r.result.value;
    };

    await send('Page.enable');
    await send('Runtime.enable');
    await send('Performance.enable');
    await send('Page.navigate', {url: URL});

    await waitFor(
      () => evalJs("!!document.querySelector('.wb-shell')"), 15000, 'shell');

    // The gateway may take a few seconds; wait for a session row or create one.
    let sessions = await waitFor(
      () => evalJs("document.querySelectorAll('.wb-session-item').length"),
      20000, 'session sync').catch(() => 0);
    if (!sessions) {
      await evalJs(`(() => {
        const b = [...document.querySelectorAll('button')]
          .find((x) => (x.getAttribute('aria-label') || '') === '新建会话');
        if (b) b.click();
        return !!b;
      })()`);
      await waitFor(
        () => evalJs("document.querySelectorAll('.wb-session-item').length > 0"),
        20000, 'created session');
    }

    // Select the first session and wait for the composer.
    await evalJs(`(() => {
      const b = document.querySelector('.wb-session-item .wb-list-button');
      if (b) b.click();
      return !!b;
    })()`);
    await waitFor(
      () => evalJs("!!document.querySelector('.hc-composer textarea')"),
      20000, 'composer');

    const before = await evalJs(`(() => {
      const ta = document.querySelector('.hc-composer textarea');
      const send = [...document.querySelectorAll('.hc-composer button')]
        .find((b) => (b.getAttribute('aria-label') || '').includes('发送'));
      const body = document.body.innerText;
      return {
        modelText: (document.querySelector('.hc-model-label, [data-model-label]') || {}).innerText || null,
        sendFound: !!send,
        sendDisabled: send ? !!send.disabled : null,
        sendTitle: send ? (send.getAttribute('title') || '') : null,
        sendAria: send ? (send.getAttribute('aria-label') || '') : null,
        textareaValue: ta ? ta.value : null,
        mentionsSelectModel: /先选择一个可用模型|需要选择模型|选择模型/.test(body),
        mentionsNoModel: /未选择模型|未设置模型|没有可用模型/.test(body),
      };
    })()`);

    // Type a draft and press Enter.  The no-model gate must keep it.
    await evalJs(`(() => {
      const ta = document.querySelector('.hc-composer textarea');
      ta.focus();
      const setter = Object.getOwnPropertyDescriptor(
        window.HTMLTextAreaElement.prototype, 'value').set;
      setter.call(ta, '无模型发布验证草稿');
      ta.dispatchEvent(new Event('input', {bubbles: true}));
      ta.dispatchEvent(new KeyboardEvent('keydown', {
        key: 'Enter', bubbles: true, cancelable: true,
      }));
      return ta.value;
    })()`);
    await sleep(800);

    const after = await evalJs(`(() => {
      const ta = document.querySelector('.hc-composer textarea');
      const send = [...document.querySelectorAll('.hc-composer button')]
        .find((b) => (b.getAttribute('aria-label') || '').includes('发送'));
      const body = document.body.innerText;
      return {
        textareaValue: ta ? ta.value : null,
        sendDisabled: send ? !!send.disabled : null,
        sendTitle: send ? (send.getAttribute('title') || '') : null,
        bodyHasModelPrompt: /先选择一个可用模型|需要选择模型/.test(body),
        bodyHasDraft: body.includes('无模型发布验证草稿'),
        messageCount: document.querySelectorAll('[data-message-id]').length,
        commandRows: document.querySelectorAll('.wb-command-status').length,
      };
    })()`);

    const shot = await send('Page.captureScreenshot', {format: 'png'});
    fs.writeFileSync(path.join(OUT, 'no-model.png'),
                     Buffer.from(shot.data, 'base64'));
    fs.writeFileSync(path.join(OUT, 'no-model-dom.html'),
                     await evalJs('document.documentElement.outerHTML'));

    const metrics = await send('Performance.getMetrics');
    const metric = (name) =>
      (metrics.metrics.find((m) => m.name === name) || {}).value;
    const report = {
      url: URL,
      capturedAt: new Date().toISOString(),
      before,
      after,
      metrics: {
        JSHeapUsedSize: metric('JSHeapUsedSize'),
        Nodes: metric('Nodes'),
        LayoutCount: metric('LayoutCount'),
      },
    };
    fs.writeFileSync(path.join(OUT, 'no-model.json'),
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
