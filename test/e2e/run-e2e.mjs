#!/usr/bin/env node
// Run the Playwright suite inside a user + mount namespace on Linux.
//
// E2E 18 mounts a size-bounded tmpfs to produce a real ENOSPC.  Mounting
// needs CAP_SYS_ADMIN; `unshare -Ur -m` supplies it without root.  The whole
// Playwright process (and therefore Chromium and the app JVM) must live in
// that namespace, so this wrapper replaces the old direct playwright call in
// package.json.  `HC_E2E_NAMESPACE=1` marks the child and prevents recursion.
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(HERE, '../..');
const CONFIG = 'test/e2e/playwright.config.mjs';

function playwrightCommand() {
  const local = path.join(REPO_ROOT, 'node_modules', '.bin', 'playwright');
  if (fs.existsSync(local)) return { command: local, prefix: [] };
  return {
    command: process.platform === 'win32' ? 'npx.cmd' : 'npx',
    prefix: ['playwright'],
  };
}

function childEnv(insideNamespace) {
  const env = { ...process.env };
  if (insideNamespace) env.HC_E2E_NAMESPACE = '1';
  return env;
}

function main() {
  const forwarded = process.argv.slice(2);
  const hasConfig = forwarded.some((arg) => arg === '--config' || arg.startsWith('--config='));
  const { command, prefix } = playwrightCommand();
  const playwrightArgs = [
    ...prefix,
    'test',
    ...(hasConfig ? [] : ['--config', CONFIG]),
    ...forwarded,
  ];

  const alreadyInside = process.env.HC_E2E_NAMESPACE === '1';
  let result = null;

  if (process.platform === 'linux' && !alreadyInside) {
    const probe = spawnSync('unshare', ['-Ur', '-m', '--', 'true'], { stdio: 'ignore' });
    if (probe.status === 0) {
      result = spawnSync('unshare', ['-Ur', '-m', '--', command, ...playwrightArgs], {
        cwd: REPO_ROOT,
        env: childEnv(true),
        stdio: 'inherit',
      });
    } else {
      console.warn(
        '[e2e] unshare -Ur -m is unavailable; running without a mount namespace. '
        + 'E2E 18 will report blockedReason and skip unless HC_E2E_REQUIRE_DISK=1.',
      );
    }
  }

  if (!result) {
    result = spawnSync(command, playwrightArgs, {
      cwd: REPO_ROOT,
      env: childEnv(alreadyInside),
      stdio: 'inherit',
    });
  }

  if (result.error) {
    console.error(`[e2e] could not start Playwright: ${result.error.message}`);
    process.exit(1);
  }
  if (result.signal) {
    console.error(`[e2e] Playwright terminated by signal ${result.signal}`);
    process.exit(1);
  }
  process.exit(result.status ?? 1);
}

main();
