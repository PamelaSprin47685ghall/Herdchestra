import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { defineConfig } from '@playwright/test';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(HERE, '../..');
const EVIDENCE = path.join(REPO_ROOT, 'plans/evidence/e2e-auto');
// Parallel shards write their artifacts under a per-shard suffix so one
// shard's Playwright outputDir cleanup cannot delete another shard's trace
// chunks (the ENOENT seen when 22 specs ran in parallel).  Empty when the
// variable is unset, so the default paths stay byte-for-byte unchanged.
const SHARD = (process.env.HC_PW_SHARD || '').trim();
const SUFFIX = SHARD ? `-${SHARD}` : '';

export default defineConfig({
  testDir: HERE,
  testMatch: '**/*.spec.mjs',
  // The global budget stays at 240s for the ordinary specs.  E2E 18 raises
  // its own budget to 900s and E2E 19 to 600s with `test.setTimeout` inside
  // those files, so a long disk-full or accessibility run cannot slow down
  // or mask a hang in the rest of the suite.
  timeout: 240000,
  expect: { timeout: 20000 },
  workers: 1,
  fullyParallel: false,
  retries: 0,
  forbidOnly: true,
  outputDir: path.join(EVIDENCE, `playwright-artifacts${SUFFIX}`),
  reporter: [
    ['list'],
    ['json', { outputFile: path.join(EVIDENCE, `playwright-results${SUFFIX}.json`) }],
    ['html', { outputFolder: path.join(EVIDENCE, `playwright-report${SUFFIX}`), open: 'never' }],
  ],
  use: {
    headless: true,
    viewport: { width: 1440, height: 1000 },
    permissions: ['clipboard-read', 'clipboard-write'],
    launchOptions: {
      executablePath: process.env.CHROME_PATH || '/usr/bin/google-chrome',
      // Keep the test browser's shared memory and V8 heap bounded.  Several
      // renderer processes otherwise size themselves from host RAM.
      args: ['--no-sandbox',
             '--disable-dev-shm-usage',
             '--disable-gpu',
             '--js-flags=--max-old-space-size=512',
             '--font-render-hinting=none'],
    },
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
    video: 'off',
  },
});
