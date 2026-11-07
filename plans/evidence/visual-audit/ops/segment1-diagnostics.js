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
  await page.waitForTimeout(1800);
  const scroll = (y) => page.evaluate((y) => { const c = document.querySelector('.wb-content'); if (c) c.scrollTop = y; }, y);
  const scrollTo = async (sel) => { await page.locator(sel).first().evaluate(el => el.scrollIntoView({block:'start'})); await page.waitForTimeout(200); };
  const shot = async (name) => { await page.screenshot({ path: `${DIR}/${name}.png` }); console.log('SHOT', name); };
  const click = async (sel, name) => { try { await page.locator(sel).filter({hasText:name}).first().evaluate(el => el.click()); } catch(e) { errors.push('CLICKFAIL '+name); } await page.waitForTimeout(700); };
  await click('button', '数据中心');
  await page.waitForTimeout(800);
  await scroll(0); await shot('s1-01-dc-top');
  await scrollTo('.hc-diagnostics'); await shot('s1-02-stats-top');
  await scroll(700); await shot('s1-03-stats-mid');
  await scroll(1400); await shot('s1-04-stats-bottom');
  console.log('TABS', await page.locator('.hc-diagnostics__tab').allInnerTexts());
  await click('.hc-diagnostics__tab', '详细日志'); await scrollTo('.hc-diagnostics'); await shot('s1-05-logs');
  await click('.hc-diagnostics__tab', '诊断包'); await scrollTo('.hc-diagnostics'); await shot('s1-06-bundle');
  await scroll(700); await shot('s1-07-bundle-mid');
  await click('.hc-diagnostics__tab', '反馈草稿'); await scrollTo('.hc-diagnostics'); await shot('s1-08-feedback');
  const metrics = await page.evaluate(() => {
    const out = { horizontal: [], smallButtons: [] };
    for (const el of document.querySelectorAll('body *')) {
      const r = el.getBoundingClientRect(); const cs = getComputedStyle(el);
      if (r.width > 0 && el.scrollWidth > el.clientWidth + 2 && cs.overflowX !== 'auto' && cs.overflowX !== 'scroll') out.horizontal.push({tag:el.tagName,cls:(el.className||'').toString().slice(0,60),sw:el.scrollWidth,cw:el.clientWidth,text:(el.innerText||'').slice(0,50)});
      if (el.tagName === 'BUTTON' && (r.width < 32 || r.height < 32)) out.smallButtons.push({text:(el.innerText||el.getAttribute('aria-label')||'').slice(0,40),w:Math.round(r.width),h:Math.round(r.height)});
    }
    return out;
  });
  fs.writeFileSync(`${DIR}/s1-metrics-1280.json`, JSON.stringify(metrics,null,2));
  console.log('METRICS', JSON.stringify(metrics).slice(0,1500));
  await page.setViewportSize({width:360,height:800}); await page.waitForTimeout(500);
  await scrollTo('.hc-diagnostics'); await shot('s1-09-diag-360');
  await click('.hc-diagnostics__tab', '诊断包'); await scrollTo('.hc-diagnostics'); await shot('s1-10-bundle-360');
  await page.evaluate(() => { document.documentElement.dataset.theme='dark'; });
  await page.waitForTimeout(400);
  await click('.hc-diagnostics__tab', '用量与费用'); await scrollTo('.hc-diagnostics'); await shot('s1-11-stats-dark-explicit');
  fs.writeFileSync(`${DIR}/s1-console.txt`, errors.join('\n'));
  console.log('ERRORS', errors.join(' | '));
  await browser.close();
})();
