'use strict';
module.exports = async ({page, baseUrl, screenshot}) => {
  const out = {assertions:{}, body:'', modelLabel:null, prompt:null};
  await page.goto(baseUrl.replace(/\/$/, '') + '/#/sessions', {waitUntil:'domcontentloaded'});
  await page.waitForTimeout(2000);
  const newBtn = page.getByRole('button', {name:'新建会话', exact:true}).first();
  if (await newBtn.count()) { await newBtn.click(); await page.waitForTimeout(1500); }
  const ta = page.locator('textarea').first();
  await ta.waitFor({state:'visible', timeout:20000});
  const prompt = 'Use the clj-plan program tool to modify the workspace file e2e09.txt. The program must call (write {:path "e2e09.txt" :content "model-write"}) and return the result. Do not use devops. Do not only describe the change; actually submit the program.';
  out.prompt = prompt;
  await ta.fill(prompt);
  await page.waitForTimeout(300);
  const send = page.locator('button[aria-label="发送消息"]').first();
  out.assertions.sendFound = await send.count() > 0;
  out.assertions.sendDisabledBefore = out.assertions.sendFound ? await send.isDisabled() : null;
  if (out.assertions.sendFound) await send.click();
  for (let i=0;i<60;i++) {
    await page.waitForTimeout(2000);
    const body = await page.locator('body').innerText().catch(()=> '');
    out.body = body.slice(0,8000);
    if (!body.includes('正在生成') && !body.includes('无人值守')) break;
  }
  out.assertions.modelLabel = await page.locator('button').filter({hasText:'模型：'}).first().innerText().catch(()=>null);
  out.assertions.bodyHasConflict = /冲突|FILE_CONFLICT|stamp/i.test(out.body);
  out.assertions.bodyHasTool = /clj-plan|defprogram|write|工具/.test(out.body);
  out.assertions.bodyHasError = /失败|错误|refused|拒绝/.test(out.body);
  out.screenshot = await screenshot('/tmp/hc-e2e09final/e2e09-after.png');
  return out;
};
