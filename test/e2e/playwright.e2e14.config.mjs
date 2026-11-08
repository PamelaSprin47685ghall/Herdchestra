// Isolated runner for E2E 14: the shared playwright.config.mjs outputDir is
// cleaned by sibling spec runs, which intermittently deletes this run's trace
// chunks (ENOENT on browserContext.close).  Same settings, private artifacts.
import base from './playwright.config.mjs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));

export default {
  ...base,
  outputDir: path.resolve(HERE, '../../plans/evidence/e2e-14/artifacts'),
};
