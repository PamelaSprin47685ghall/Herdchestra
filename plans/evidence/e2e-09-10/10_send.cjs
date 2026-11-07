'use strict';
/*
 * E2E 10 phase 1: start a real in-flight read-only shell through the Plan
 * dedicated worker.  The browser only sends an operator message; the model
 * and the worker own the tool calls.
 */
const { execSync } = require('child_process');

function sleep(ms) { return new Promise((r) => setTimeout(r, ms)); }

function shellPids() {
  try {
    const out = execSync(
      "ps -eo pid=,args= | grep -F 'sleep 120' | grep -v grep || true",
      { encoding: 'utf8' });
    return out.trim().split(/\n/).filter(Boolean);
  } catch (_) {
    return [];
  }
}

module.exports = async ({ page, baseUrl, screenshot, log }) => {
  const out = { baseUrl, shellPids: [], started: false };
  await page.goto(baseUrl.replace(/\/$/, '') + '/#/sessions',
                  { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2000);
  const body0 = await page.locator('body').innerText();
  out.modelLabel = (body0.match(/模型：[^\n]+/) || [null])[0];
  out.textareas = await page.locator('textarea').count();
  const prompt = [
    '这是 E2E 10 的真实中断测试。请立即调用一次 devops 工具，',
    '把 verbose-intent 写为：运行只读 shell 命令 sleep 120 并等待它结束。',
    'Plan dedicated worker 必须调用 call 工具执行 `sleep 120`，不要读文件，不要做其他事。',
    '调用 devops 后等待它的结果。'
  ].join('');
  const area = page.locator('textarea').first();
  await area.fill(prompt);
  await page.waitForTimeout(300);
  const send = page.getByRole('button', { name: '发送消息' }).first();
  out.sendFound = (await send.count()) > 0;
  if (out.sendFound) {
    await send.click();
    out.started = true;
  }
  const deadline = Date.now() + 240000;
  let lastBody = '';
  while (Date.now() < deadline) {
    const pids = shellPids();
    if (pids.length) {
      out.shellPids = pids;
      break;
    }
    lastBody = await page.locator('body').innerText().catch(() => '');
    if (/调用失败|错误|没有可用模型|未选择模型/.test(lastBody)) break;
    await sleep(2000);
  }
  out.body = (await page.locator('body').innerText().catch(() => lastBody)).slice(0, 6000);
  out.shellStarted = out.shellPids.length > 0;
  out.hasDevops = /devops/i.test(out.body);
  out.hasSleep = /sleep 120/.test(out.body);
  out.screenshot = await screenshot('/tmp/hc-w5/evidence/e2e10-phase1.png');
  log(`shellStarted=${out.shellStarted} pids=${JSON.stringify(out.shellPids)}`);
  return out;
};
