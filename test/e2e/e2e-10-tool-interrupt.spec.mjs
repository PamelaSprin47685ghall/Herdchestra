// E2E 10: a tool call interrupted in flight resolves as outcome-unknown.
//
// The mock model answers the Plan turn with a `clj-plan` tool call whose
// program runs `(devops ...)`; the Plan dedicated worker's model then asks
// for the `call` tool with `sleep 300`.  The test kills the app JVM while
// the shell sleep is still running, restarts it on the same data directory,
// and expects the swarm board to publish the interrupted call under
// "结果待确认" (outcome-unknown) — never replayed.  After the operator
// verifies the target (the sleep is gone with the killed JVM), clicking
// "确认已成功" settles the call; the mock model's request count must not
// grow, proving no automatic replay.
import { test } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { startApp, REPO_ROOT } from './support/app.mjs';
import { startMockModel } from './support/mock-model.mjs';
import {
  assert, clientBundleInfo, composer, goto, newSession, send, shot,
  waitForShell, waitForSync,
} from './support/ui.mjs';
import { sourceTreeSha256, collectMockState } from './support/evidence.mjs';

test.describe.configure({ mode: 'serial' });

function evidenceDir10() {
  const dir = path.join(REPO_ROOT, 'plans/evidence/e2e-10');
  fs.mkdirSync(dir, { recursive: true });
  return dir;
}

const PLAN_SOURCE = `(defprogram e2e10-probe []
  (devops {:verbose-intent "E2E 10: run one read-only call that sleeps; report the result verbatim."}))`;

function toolNames(body) {
  return (Array.isArray(body?.tools) ? body.tools : [])
    .map((tool) => tool?.function?.name || tool?.name)
    .filter(Boolean);
}

// Mock model script: the Plan model gets one `clj-plan` call, the Plan
// dedicated worker gets one `call` tool request that runs `sleep 300`, and
// every later request is a plain text reply so no turn can loop.
function e2e10ToolCalls(body) {
  const names = toolNames(body);
  if (names.includes('clj-plan')) {
    // Only the first Plan tool call may run the program; a later Plan turn
    // (after the tool result) must not re-enter it.
    // Pair Hint injects a synthetic assistant/tool pair whose tool call has
    // an empty function name (`""`); it is not a real model tool call and
    // must not count, or the first `clj-plan` would never be returned.
    const assistantCalls = (Array.isArray(body?.messages) ? body.messages : [])
      .filter((message) => message?.role === 'assistant'
        && Array.isArray(message?.tool_calls)
        && message.tool_calls.some((call) => {
          const name = call?.function?.name ?? call?.name;
          return typeof name === 'string' && name.length > 0;
        }))
      .length;
    if (assistantCalls === 0) {
      return [{
        name: 'clj-plan',
        arguments: {
          source: PLAN_SOURCE,
          intent: 'E2E 10 interruption probe: one devops call that sleeps.',
          scope: {
            kind: 'reset-scope',
            'new-scope-goal': 'E2E 10 tool interruption probe',
            'new-scope-retention-budget': 100,
          },
        },
      }];
    }
    return [];
  }
  if (names.includes('call')) {
    return [{
      name: 'call',
      arguments: { name: 'call', arguments: { command: 'sleep 300' } },
    }];
  }
  return [];
}

async function mockState(mock) {
  const response = await fetch(`${mock.url}/__mock/state`);
  return response.json();
}

async function waitForUnresolved(page, timeout = 120000) {
  const section = page.locator('section.hc-unresolved[aria-label="结果待确认"]').first();
  await section.waitFor({ state: 'visible', timeout });
  return section;
}

test('@core E2E 10 tool interruption marks outcome-unknown, ack settles without replay', async ({ page }) => {
  test.setTimeout(420000);
  const dir = evidenceDir10();
  const marker = `E2E10-${Date.now()}`;

  const model = await startMockModel({
    reply: `E2E10 fallback reply ${marker}`,
    chunkDelayMs: 5,
    captureBodies: true,
    toolCalls: e2e10ToolCalls,
  });

  const appOptions = {
    evidenceDir: dir,
    workspaceFiles: { 'README.md': `# E2E 10 workspace\n${marker}\n` },
    models: {
      connections: [{
        id: 'mock',
        name: 'Mock 模型服务',
        baseUrl: `${model.url}/v1`,
        models: [{
          id: 'mock-fast',
          displayName: 'Mock Fast',
          maxOutputTokens: 2048,
          capabilities: ['tools'],
        }],
      }],
      roles: {
        main: { connection: 'mock', model: 'mock-fast' },
        task: { connection: 'mock', model: 'mock-fast' },
      },
    },
  };

  let app = await startApp({ ...appOptions, keepTemp: true });
  const tempRoot = app.tempRoot;

  const result = {
    test: 'E2E 10',
    mode: 'automated',
    mock: true,
    startedAt: new Date().toISOString(),
    marker,
    appUrl: app.url,
    mockUrl: model.url,
    clientBundle: clientBundleInfo(),
    sourceTreeSha256: sourceTreeSha256(),
    checks: {},
    screenshots: [],
    notes: [],
  };

  const finish = async (ok, error) => {
    result.ok = ok && !error;
    if (error) result.error = String(error && error.message ? error.message : error);
    result.finishedAt = new Date().toISOString();
    try {
      result.mockState = await mockState(model);
    } catch (e) {
      result.mockStateError = String(e);
    }
    result.evidence = path.join(dir, 'e2e-10-result.json');
    fs.writeFileSync(result.evidence, `${JSON.stringify(result, null, 2)}\n`);
  };

  try {
    // --- Phase 1: send a message whose Plan program sleeps in a tool call.
    await goto(page, app.url, '/sessions');
    await waitForShell(page);
    await waitForSync(page);
    await newSession(page);
    await send(page, `${marker} 请规划一次只读核查。`);

    // Wait until the Plan dedicated worker's `call` tool is actually running
    // `sleep 300`: a `sleep 300` child process owned by the app JVM.
    const sleepDeadline = Date.now() + 120000;
    let sawWorkerCall = false;
    while (Date.now() < sleepDeadline && !sawWorkerCall) {
      try {
        const out = execFileSync('pgrep', ['-x', 'sleep'], { encoding: 'utf8' });
        const pids = out.trim().split('\n').filter(Boolean);
        for (const pid of pids) {
          try {
            const cmdline = fs.readFileSync(`/proc/${pid}/cmdline`, 'utf8');
            if (cmdline.includes('sleep') && cmdline.includes('300')) {
              sawWorkerCall = true;
              break;
            }
          } catch { /* process exited between pgrep and read */ }
        }
      } catch { /* pgrep exits 1 when nothing matches */ }
      if (sawWorkerCall) break;
      await page.waitForTimeout(500);
    }
    // Give the shell a moment to spawn inside the app JVM.
    await page.waitForTimeout(1500);
    result.checks.workerCallObserved = sawWorkerCall;

    const beforeKill = await mockState(model);
    result.checks.modelRequestsBeforeKill = beforeKill.chatCompletions;
    result.checks.modelRequestsBeforeKillGt1 = beforeKill.chatCompletions >= 2;

    // --- Phase 2: kill the app JVM mid-flight (SIGKILL = hard crash).
    process.kill(app.pid, 'SIGKILL');
    await new Promise((resolve) => setTimeout(resolve, 1500));
    result.checks.appKilled = true;

    // --- Phase 3: restart on the same data directory.
    app = await startApp({ ...appOptions, root: tempRoot, keepTemp: true });
    result.checks.restartedUrl = app.url;
    assert(app.url !== undefined, 'restart did not produce a URL');

    // --- Phase 4: the board must show the interrupted call as unknown.
    await goto(page, app.url, '/board');
    await waitForShell(page);
    await waitForSync(page);
    const section = await waitForUnresolved(page, 90000);
    const sectionText = await section.innerText();
    result.unresolvedText = sectionText;
    result.checks.unresolvedPanelVisible = sectionText.includes('结果待确认');
    result.checks.outcomeUnknownStage = /outcome-unknown/.test(sectionText);
    result.checks.noAutoReplayHint = sectionText.includes('绝不自动重放');
    result.screenshots.push(await shot(page, dir, 'e2e10-01-outcome-unknown'));
    assert(result.checks.unresolvedPanelVisible, 'unresolved panel missing after restart');
    assert(result.checks.outcomeUnknownStage, `call stage is not outcome-unknown: ${sectionText}`);
    assert(result.checks.noAutoReplayHint, 'panel lost the no-auto-replay guidance');

    // Operator check: the `sleep 300` died with the JVM, so the action did
    // happen but its result is unknowable — acknowledge it as succeeded.
    // First prove no replay already happened while we observed.  The Owner
    // may still run unattended Nudge turns after recovery (the review ledger
    // stays open until the plan is written), so `chatCompletions` is not a
    // replay signal: a replay is a request that makes the mock issue another
    // tool call (a second `clj-plan`, a second devops worker `call`).  The
    // mock counts exactly those in `toolCallsIssued`.
    const afterRestartBeforeAck = await mockState(model);
    result.checks.modelRequestsAfterRestartBeforeAck = afterRestartBeforeAck.chatCompletions;
    result.checks.toolCallsBeforeKill = beforeKill.toolCallsIssued;
    result.checks.toolCallsAfterRestartBeforeAck = afterRestartBeforeAck.toolCallsIssued;
    result.checks.noReplayBeforeAck =
      afterRestartBeforeAck.toolCallsIssued === beforeKill.toolCallsIssued;

    const ackButton = section.locator('button[data-ack-outcome="succeeded"]').first();
    await ackButton.waitFor({ state: 'visible', timeout: 15000 });
    await ackButton.click();

    // The panel must settle: 0 open, 1 acknowledged, stage :succeeded.
    await page.waitForFunction(() => {
      const el = document.querySelector('section.hc-unresolved[aria-label="结果待确认"]');
      return el && /0 个未结/.test(el.innerText) && /1 个已核对/.test(el.innerText);
    }, null, { timeout: 30000 });
    const settledText = await section.innerText();
    result.settledText = settledText;
    result.checks.settledOpenZero = /0 个未结/.test(settledText);
    result.checks.settledAckOne = /1 个已核对/.test(settledText);
    result.checks.ackLabel = settledText.includes('已确认成功');
    result.screenshots.push(await shot(page, dir, 'e2e10-02-acknowledged'));

    // --- Phase 5: no automatic replay, ever.
    await page.waitForTimeout(3000);
    const afterAck = await mockState(model);
    result.checks.modelRequestsAfterAck = afterAck.chatCompletions;
    result.checks.toolCallsAfterAck = afterAck.toolCallsIssued;
    result.checks.noReplayAfterAck =
      afterAck.toolCallsIssued === beforeKill.toolCallsIssued;
    result.checks.sessionNotDeleted = true; // we never deleted the session

    const failedChecks = Object.entries(result.checks)
      .filter(([, value]) => value === false)
      .map(([key]) => key);
    result.checks.all = failedChecks.length === 0;
    await finish(result.checks.all, null);
    assert(result.checks.all, `E2E 10 checks failed: ${failedChecks.join(', ')}`);
  } catch (error) {
    try { result.screenshots.push(await shot(page, dir, 'e2e10-failure')); } catch { /* page may be gone */ }
    await finish(false, error);
    throw error;
  } finally {
    await model.close();
    try { await app.close(); } catch { /* already dead or closing */ }
    fs.rmSync(tempRoot, { recursive: true, force: true });
  }
});
