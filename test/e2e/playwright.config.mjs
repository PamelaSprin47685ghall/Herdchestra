import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { defineConfig } from '@playwright/test';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(HERE, '../..');
const EVIDENCE = path.join(REPO_ROOT, 'plans/evidence/e2e-auto');

export default defineConfig({
  testDir: HERE,
  testMatch: '**/*.spec.mjs',
  timeout: 240000,
  expect: { timeout: 20000 },
  workers: 1,
  fullyParallel: false,
  retries: 0,
  forbidOnly: true,
  outputDir: path.join(EVIDENCE, 'playwright-artifacts'),
  reporter: [
    ['list'],
    ['json', { outputFile: path.join(EVIDENCE, 'playwright-results.json') }],
    ['html', { outputFolder: path.join(EVIDENCE, 'playwright-report'), open: 'never' }],
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
