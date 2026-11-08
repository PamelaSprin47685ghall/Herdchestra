// Isolated runner for E2E 20: the shared playwright.config.mjs outputDir is
// cleaned by sibling spec runs, which intermittently deletes this run's
// trace chunks (ENOENT on browserContext.close).  Same settings, private
// artifacts, and trace disabled so no chunk files are needed at all.
import base from './playwright.config.mjs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const EVIDENCE = path.resolve(HERE, '../../plans/evidence/e2e-20');

export default {
  ...base,
  outputDir: path.join(EVIDENCE, 'artifacts'),
  reporter: [
    ['list'],
    ['json', { outputFile: path.join(EVIDENCE, 'playwright-results.json') }],
    ['html', { outputFolder: path.join(EVIDENCE, 'playwright-report'), open: 'never' }],
  ],
  use: {
    ...base.use,
    trace: 'off',
  },
};
