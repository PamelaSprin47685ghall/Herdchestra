// E2E 09: a protected workspace commit meets an external edit.
// The driver runs the same `:workspace` port the server program receives.
// It reads a real CSV, an external editor changes one line, the stale write
// is rejected with :FILE_CONFLICT, a fresh read commits the worker content,
// and the server's own file API points at the real result and stamp.
import { test } from '@playwright/test';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { clojureCommand, REPO_ROOT, startApp } from './support/app.mjs';
import { assert, clientBundleInfo, evidenceDir, writeJson } from './support/ui.mjs';

test.describe.configure({ mode: 'serial' });

function runDriver(args, logPath, timeout = 180000) {
  const maxLog = 1024 * 1024;
  return new Promise((resolve, reject) => {
    const child = spawn(clojureCommand(),
      ['-J-Xmx384m', '-J-XX:MaxDirectMemorySize=256m',
       '-M:dev:compose:server', '-m', 'e2e.e2e09-workspace', ...args],
      { cwd: REPO_ROOT, env: process.env, stdio: ['ignore', 'pipe', 'pipe'] });
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', (chunk) => {
      if (stdout.length < maxLog) stdout += chunk.toString().slice(0, maxLog - stdout.length);
    });
    child.stderr.on('data', (chunk) => {
      if (stderr.length < maxLog) stderr += chunk.toString().slice(0, maxLog - stderr.length);
    });
    const timer = setTimeout(() => {
      child.kill('SIGKILL');
      reject(new Error(`E2E 09 driver timed out after ${timeout} ms\n${stdout}\n${stderr}`));
    }, timeout);
    child.once('error', (error) => {
      clearTimeout(timer);
      reject(error);
    });
    child.once('exit', (code, signal) => {
      clearTimeout(timer);
      fs.writeFileSync(logPath, `exit=${code} signal=${signal}\n--- stdout ---\n${stdout}\n--- stderr ---\n${stderr}\n`);
      resolve({ code, signal, stdout, stderr });
    });
  });
}

function stampFromEdn(text) {
  const read = (name) => {
    const match = text.match(new RegExp(`:${name}\\s+(\\d+)`));
    return match ? Number(match[1]) : null;
  };
  const stamp = { 'mtime-sec': read('mtime-sec'), 'mtime-nsec': read('mtime-nsec'), size: read('size') };
  return Object.values(stamp).every((value) => value !== null) ? stamp : null;
}

test('@core E2E 09 protected commit keeps the external edit, then applies after re-read', async () => {
  const dir = evidenceDir('e2e09');
  const app = await startApp({ evidenceDir: dir });
  const outputPath = path.join(dir, 'e2e09-driver.json');
  const logPath = path.join(dir, 'e2e09-driver.log');
  const result = {
    test: 'E2E 09',
    mode: 'automated-server-side',
    mock: false,
    startedAt: new Date().toISOString(),
    appUrl: app.url,
    workspaceRoot: app.workspaceRoot,
    clientBundle: clientBundleInfo(),
    checks: {},
  };
  try {
    const started = Date.now();
    const run = await runDriver([app.workspaceRoot, outputPath], logPath);
    result.driverExitCode = run.code;
    result.driverSignal = run.signal;
    result.driverMs = Date.now() - started;
    result.driverLog = logPath;
    assert(run.code === 0, `E2E 09 driver exited ${run.code}; see ${logPath}`);
    assert(fs.existsSync(outputPath), 'E2E 09 driver did not write its JSON report');
    const report = JSON.parse(fs.readFileSync(outputPath, 'utf8'));
    result.driver = report;
    result.checks.driverOk = report.ok === true;
    result.checks.conflictStatus = report.checks['conflict-status'] === true;
    result.checks.conflictCode = report.checks['conflict-code'] === true;
    result.checks.externalContentKept = report.checks['external-content-kept'] === true;
    result.checks.conflictExpectedHasOriginal = report.checks['conflict-expected-has-original'] === true;
    result.checks.conflictActualIsExternal = report.checks['conflict-actual-is-external'] === true;
    result.checks.rereadSeesExternal = report.checks['reread-sees-external'] === true;
    result.checks.retryApplied = report.checks['retry-applied'] === true;
    result.checks.workerContentOnDisk = report.checks['worker-content-on-disk'] === true;
    result.checks.fileCardPointsToRealFile = report.checks['file-card-points-to-real-file'] === true;
    result.checks.fileCardStampIsFinal = report.checks['file-card-stamp-is-final'] === true;
    assert(result.checks.driverOk, `driver checks failed: ${JSON.stringify(report.checks)}`);

    const readResponse = await fetch(`${app.url}/api/workspace/read?path=report.csv`);
    const readText = await readResponse.text();
    const statResponse = await fetch(`${app.url}/api/workspace/stat?path=report.csv`);
    const statText = await statResponse.text();
    result.serverReadStatus = readResponse.status;
    result.serverStatStatus = statResponse.status;
    result.serverRead = readText;
    result.serverStat = statText;
    result.checks.serverReadOk = readResponse.ok && readText.includes('worker,7');
    result.checks.serverStatOk = statResponse.ok && statText.includes(':path "report.csv"');
    const serverStamp = stampFromEdn(statText);
    result.serverStamp = serverStamp;
    result.checks.serverStampIsFinal = serverStamp !== null
      && serverStamp['mtime-sec'] === report['final-stamp']['mtime-sec']
      && serverStamp['mtime-nsec'] === report['final-stamp']['mtime-nsec']
      && serverStamp.size === report['final-stamp'].size;
    result.checks.realFileMatches = fs.readFileSync(path.join(app.workspaceRoot, 'report.csv'), 'utf8')
      === report['final-text'];
    assert(result.checks.serverReadOk, `server file API did not return worker content: ${readText.slice(0, 300)}`);
    assert(result.checks.serverStatOk, `server stat API failed: ${statText.slice(0, 300)}`);
    assert(result.checks.serverStampIsFinal, `server stamp ${JSON.stringify(serverStamp)} != ${JSON.stringify(report['final-stamp'])}`);
    assert(result.checks.realFileMatches, 'the real file does not match the driver final text');

    result.ok = Object.values(result.checks).every(Boolean);
    result.finishedAt = new Date().toISOString();
    result.evidence = writeJson(dir, 'e2e09-result', result);
    assert(result.ok, 'one or more E2E 09 checks failed');
  } catch (error) {
    result.ok = false;
    result.error = error.message;
    result.finishedAt = new Date().toISOString();
    result.evidence = writeJson(dir, 'e2e09-result', result);
    throw error;
  } finally {
    await app.close();
  }
});
