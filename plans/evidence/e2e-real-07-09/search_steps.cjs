'use strict';
module.exports = async ({page, baseUrl, screenshot}) => {
  const out = {assertions:{}, searchRequests:[]};
  page.on('request', r => { if (r.url().includes('127.0.0.1:18095')) out.searchRequests.push(r.url()); });
  await page.goto(baseUrl.replace(/\/$/, '') + '/#/sessions', {waitUntil:'domcontentloaded'});
  await page.waitForTimeout(2000);
  const open = page.getByRole('button', {name:'展开', exact:true}).first();
  if (await open.count()) await open.click();
  await page.waitForTimeout(500);
  const sel = page.locator('select[aria-label="选择独立搜索服务"]').first();
  out.assertions.selectFound = await sel.count() > 0;
  await sel.selectOption('local-stub');
  await page.waitForTimeout(500);
  out.selectValue = await sel.inputValue();
  const must = page.getByRole('button', {name:'本轮必须搜索', exact:true}).first();
  out.assertions.mustFound = await must.count() > 0;
  if (out.assertions.mustFound) await must.click();
  await page.waitForTimeout(500);
  out.modeButtons = await page.locator('button').filter({hasText:/自动|本轮必须搜索|关闭/}).evaluateAll(els => els.map(e => ({text:e.innerText, pressed:e.getAttribute('aria-pressed'), cls:e.className})));
  out.bodyAfterSetup = (await page.locator('body').innerText()).slice(0,2000);
  const ta = page.locator('textarea').first();
  await ta.fill('请只根据搜索结果回答：ALPHA-ONE 来自哪个文档？请用 [1] 引用来源。');
  await page.waitForTimeout(300);
  const send = page.locator('button[aria-label="发送消息"]').first();
  out.assertions.sendDisabled = await send.isDisabled();
  if (!out.assertions.sendDisabled) await send.click();
  for (let i=0;i<45;i++) {
    await page.waitForTimeout(2000);
    const body = await page.locator('body').innerText().catch(()=> '');
    out.body = body.slice(0,6000);
    if (!body.includes('正在生成') && !body.includes('无人值守')) break;
  }
  out.assertions.searchRan = out.searchRequests.length > 0;
  out.assertions.sourceCardCount = await page.locator('.hc-source-card').count();
  out.sourceCards = await page.locator('.hc-source-card').allTextContents();
  out.assertions.bodyHasNumbers = /\[1\]/.test(out.body || '');
  out.screenshot = await screenshot('/tmp/hc-e2e07search/answer.png');
  return out;
};
