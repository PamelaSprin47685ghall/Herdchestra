'use strict';
/*
 * E2E 10 phase 2: observe the unknown call in the real UI, acknowledge it,
 * and prove the panel shows the operator conclusion.  No model call and no
 * replay are involved.
 */
function sleep(ms) { return new Promise((r) => setTimeout(r, ms)); }

module.exports = async ({ page, baseUrl, screenshot, log }) => {
  const out = { baseUrl };
  await page.goto(baseUrl.replace(/\/$/, '') + '/#/board',
                  { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2500);
  const panel = page.locator('.hc-unresolved');
  out.panelFound = (await panel.count()) > 0;
  out.panelText = out.panelFound ? await panel.innerText() : '';
  out.callIds = await page.locator('.hc-unresolved-call')
    .evaluateAll((els) => els.map((e) => e.getAttribute('data-call-id')));
  out.unknownRows = await page.locator('.hc-unresolved-call[data-outcome-unknown="true"]').count();
  out.ackButtons = await page.locator('.hc-unresolved-call button[data-ack-outcome="succeeded"]').count();
  out.screenshotBefore = await screenshot('/tmp/hc-w5/evidence/e2e10-before-ack.png');

  const button = page.locator('.hc-unresolved-call[data-outcome-unknown="true"] button[data-ack-outcome="succeeded"]').first();
  if (await button.count()) {
    await button.click();
    out.clicked = true;
    const deadline = Date.now() + 30000;
    while (Date.now() < deadline) {
      const text = await panel.innerText().catch(() => '');
      if (text.includes('已确认成功')) break;
      await sleep(1000);
    }
  }
  out.afterText = await panel.innerText().catch(() => '');
  out.ackShown = out.afterText.includes('已确认成功');
  out.unknownAfter = await page.locator('.hc-unresolved-call[data-outcome-unknown="true"]').count();
  out.body = (await page.locator('body').innerText()).slice(0, 5000);
  out.screenshotAfter = await screenshot('/tmp/hc-w5/evidence/e2e10-after-ack.png');
  log(`panel=${out.panelFound} rows=${out.callIds.length} ackShown=${out.ackShown}`);
  return out;
};
