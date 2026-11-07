const { chromium } = require('playwright');
const fs = require('fs');
const DIR = '/tmp/hc-visual-ops/evidence';
(async () => {
  const browser = await chromium.launch({ executablePath: '/usr/bin/google-chrome', args: ['--disable-dev-shm-usage','--no-sandbox'] });
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 }, colorScheme: 'light' });
  const errors = [];
  page.on('console', m => { if (m.type() === 'error') errors.push('CONSOLE ' + m.text()); });
  page.on('pageerror', e => errors.push('PAGEERROR ' + e.message));
  await page.goto(process.env.BASE_URL + '/#/settings', { waitUntil: 'networkidle' });
  await page.waitForTimeout(1600);
  const scroll = (y) => page.evaluate((y) => { const c = document.querySelector('.wb-content'); if (c) c.scrollTop = y; }, y);
  const top = () => scroll(0);
  const shot = async (name) => { await page.screenshot({ path: `${DIR}/${name}.png` }); console.log('SHOT', name); };
  const click = async (sel, name) => { try { await page.locator(sel).filter({hasText:name}).first().evaluate(el => el.click()); } catch(e) { errors.push('CLICKFAIL '+name); } await page.waitForTimeout(550); };
  const tab = async (name) => { await click('button', name); await top(); };
  const diagTab = async (name) => { await click('.hc-diagnostics__tab', name); await page.locator('.hc-diagnostics').first().evaluate(el => el.scrollIntoView({block:'start'})); await page.waitForTimeout(200); };

  // 1280 light
  await tab('数据中心'); await shot('f-01-diag-stats-1280');
  await diagTab('详细日志'); await shot('f-02-diag-logs-1280');
  await diagTab('诊断包'); await shot('f-03-diag-bundle-1280');
  await click('.hc-backup-tab', '创建备份'); await page.locator('.hc-backup').first().evaluate(el => el.scrollIntoView({block:'start'})); await shot('f-04-backup-create-1280');
  // Optional real create + verify + preview; bounded waits so the segment stays finite.
  try {
    await page.locator('input[aria-label="备份密码"]').fill('correct horse battery staple');
    await page.locator('input[aria-label="再次输入备份密码"]').fill('correct horse battery staple');
    await page.waitForTimeout(600);
    await click('button', '生成加密备份');
    await page.getByText('最近一次备份').first().waitFor({ timeout: 25000 });
    await page.locator('.hc-backup-result').first().evaluate(el => el.scrollIntoView({block:'start'}));
    await shot('f-04b-backup-result-1280');
  } catch (e) { errors.push('CREATE_TIMEOUT'); }
  await click('.hc-backup-tab', '恢复与预览'); await page.locator('.hc-backup').first().evaluate(el => el.scrollIntoView({block:'start'})); await shot('f-05-backup-restore-1280');
  try {
    await click('button', '选择这份备份');
    await page.waitForTimeout(400);
    await page.locator('input[aria-label="恢复用备份密码"]').fill('correct horse battery staple');
    await page.waitForTimeout(400);
    await click('button', '校验备份');
    await page.getByText('校验通过').first().waitFor({ timeout: 15000 });
    await page.locator('.hc-backup-verify').first().evaluate(el => el.scrollIntoView({block:'start'}));
    await shot('f-05b-backup-verified-1280');
    await click('button', '校验并生成预览');
    await page.getByText('恢复预览').first().waitFor({ timeout: 25000 });
    await page.locator('.hc-backup-preview').first().evaluate(el => el.scrollIntoView({block:'start'}));
    await shot('f-05c-backup-preview-1280');
  } catch (e) { errors.push('PREVIEW_TIMEOUT'); }
  await tab('语音与朗读'); await shot('f-06-speech-empty-1280');
  await click('button', '添加服务'); await top(); await shot('f-07-speech-form-1280');
  await tab('定时任务'); await shot('f-08-schedule-empty-1280');
  await click('button', '新建任务'); await top(); await shot('f-09-schedule-form-1280');

  // 360
  await page.setViewportSize({width:360,height:800}); await page.waitForTimeout(450);
  await tab('数据中心'); await diagTab('用量与费用'); await shot('f-10-diag-360');
  await click('.hc-backup-tab', '创建备份'); await page.locator('.hc-backup').first().evaluate(el => el.scrollIntoView({block:'start'})); await shot('f-11-backup-360');
  await tab('语音与朗读'); await shot('f-12-speech-360');
  await tab('定时任务'); await click('button', '新建任务'); await top(); await shot('f-13-schedule-form-360');

  // 820
  await page.setViewportSize({width:820,height:1180}); await page.waitForTimeout(450);
  await tab('数据中心'); await diagTab('用量与费用'); await shot('f-14-diag-820');
  await click('.hc-backup-tab', '创建备份'); await page.locator('.hc-backup').first().evaluate(el => el.scrollIntoView({block:'start'})); await shot('f-15-backup-820');
  await tab('语音与朗读'); await shot('f-16-speech-820');
  await tab('定时任务'); await click('button', '新建任务'); await top(); await shot('f-17-schedule-820');

  // dark explicit while OS light
  await page.setViewportSize({width:1280,height:900}); await page.waitForTimeout(400);
  await page.evaluate(() => { document.documentElement.dataset.theme='dark'; }); await page.waitForTimeout(300);
  await tab('数据中心'); await diagTab('用量与费用'); await shot('f-18-diag-dark');
  await click('.hc-backup-tab', '创建备份'); await page.locator('.hc-backup').first().evaluate(el => el.scrollIntoView({block:'start'})); await shot('f-19-backup-dark');
  await tab('语音与朗读'); await shot('f-20-speech-dark');
  await tab('定时任务'); await click('button', '新建任务'); await top(); await shot('f-21-schedule-dark');

  // 200% text
  await page.evaluate(() => { document.documentElement.dataset.theme='light'; document.documentElement.style.setProperty('--wb-font-scale','2'); }); await page.waitForTimeout(350);
  await tab('数据中心'); await diagTab('用量与费用'); await shot('f-22-diag-200');
  await click('.hc-backup-tab', '创建备份'); await page.locator('.hc-backup').first().evaluate(el => el.scrollIntoView({block:'start'})); await shot('f-23-backup-200');
  await tab('语音与朗读'); await shot('f-24-speech-200');
  await tab('定时任务'); await click('button', '新建任务'); await top(); await shot('f-25-schedule-200');

  const metrics = await page.evaluate(() => {
    const out = {horizontal: [], smallButtons: []};
    for (const el of document.querySelectorAll('body *')) {
      const r = el.getBoundingClientRect(); const cs = getComputedStyle(el);
      if (r.width > 0 && el.scrollWidth > el.clientWidth + 2 && cs.overflowX !== 'auto' && cs.overflowX !== 'scroll') out.horizontal.push({tag:el.tagName,cls:(el.className||'').toString().slice(0,60),sw:el.scrollWidth,cw:el.clientWidth,text:(el.innerText||'').slice(0,50)});
      if (el.tagName === 'BUTTON' && (r.width < 32 || r.height < 32)) out.smallButtons.push({text:(el.innerText||el.getAttribute('aria-label')||'').slice(0,40),w:Math.round(r.width),h:Math.round(r.height)});
    }
    return out;
  });
  fs.writeFileSync(`${DIR}/f-metrics-200.json`, JSON.stringify(metrics,null,2));
  fs.writeFileSync(`${DIR}/f-console.txt`, errors.join('\n'));
  console.log('METRICS', JSON.stringify(metrics).slice(0,1200));
  console.log('ERRORS', errors.join(' | '));
  await browser.close();
})();
