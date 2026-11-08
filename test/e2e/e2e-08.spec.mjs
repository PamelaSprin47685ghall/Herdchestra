// E2E 08: Plan S1 `(ask ...)` card -> answer -> unpark continuation in the
// same run -> Esc cancels the session tree while preserving generated text.
//
// The mock model drives the real Owner pipeline: the first `clj-plan` tool
// call runs a program whose `(ask {:question ...})` parks in S1 and projects
// an `.hc-ask` card.  Submitting the answer clears `:awaiting-user?` and opens
// a pending plan turn; the second `clj-plan` call runs `(unpark {:name ...})`
// which resumes the parked program with the answer value, and the run then
// settles on a plain text reply.  A later long streaming turn is cancelled
// with Esc; already-generated text must remain and no late append may land.
//
// Role-boundary refusals (`:ASK_FORBIDDEN_OUTSIDE_S1`, `:TOKEN_REQUIRED`) are
// asserted through the real program runtime in a separate `clojure -M:dev`
// probe — driving the full S1->S2->swarm lifecycle through the UI is out of
// scope for one spec; the probe exercises the same `evaluate-program!` entry
// point the app wires to `workspace/run-program!`.
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { test } from '@playwright/test';
import { clojureCommand, startApp, REPO_ROOT } from './support/app.mjs';
import { startMockModel, LONG_TEXT } from './support/mock-model.mjs';
import {
  assert, clientBundleInfo, composer, goto, newSession, send, shot,
  waitForShell, waitForSync,
} from './support/ui.mjs';
import { sourceTreeSha256, collectMockState } from './support/evidence.mjs';

test.describe.configure({ mode: 'serial' });

const ASK_QUESTION = 'E2E-08 请给出一个数字';
const ASK_PROGRAM_NAME = 'e2e08-ask';
const ASK_ANSWER = '42';
const LONG_MARKER = 'E2E08-LONG-STREAM';

function evidenceDir08() {
  const dir = path.join(REPO_ROOT, 'plans/evidence/e2e-07-08');
  fs.mkdirSync(dir, { recursive: true });
  return dir;
}

const ASK_SOURCE = `(defprogram ${ASK_PROGRAM_NAME} []
  (let [answer (ask {:question "${ASK_QUESTION}"})]
    answer))`;

const UNPARK_SOURCE = `(defprogram e2e08-resume []
  (unpark {:name "${ASK_PROGRAM_NAME}" :value "${ASK_ANSWER}"}))`;

function toolNames(body) {
  return (Array.isArray(body?.tools) ? body.tools : [])
    .map((tool) => tool?.function?.name || tool?.name)
    .filter(Boolean);
}

// Scripted tool calls for the Plan lane.  `cljPlanCalls` counts how many
// clj-plan tool requests the mock has already answered; the first runs the
// S1 ask program, the second (the pending plan turn opened by the operator
// answer) runs the unpark program, and every later plan turn falls back to
// plain text so the unattended Nudge tail cannot re-park the ask forever.
function e2e08ToolCalls(state) {
  return function toolCalls(body) {
    const names = toolNames(body);
    if (!names.includes('clj-plan')) return [];
    if (state.cljPlanCalls === 0) {
      state.cljPlanCalls += 1;
      return [{
        name: 'clj-plan',
        arguments: {
          source: ASK_SOURCE,
          intent: 'E2E 08: park on an S1 ask so the operator card appears.',
          scope: {
            kind: 'reset-scope',
            'new-scope-goal': 'E2E 08 S1 ask boundary probe',
            'new-scope-retention-budget': 8,
          },
        },
      }];
    }
    // The second clj-plan request is the resume turn by construction: the
    // pending plan turn opened by the operator answer is the only model
    // call between the parked ask and the Nudge tail.  The parked receipt
    // always carries the program `:name`, so gating on a tool-result
    // substring would make this branch unreachable.
    if (state.cljPlanCalls === 1) {
      state.cljPlanCalls += 1;
      return [{
        name: 'clj-plan',
        arguments: {
          source: UNPARK_SOURCE,
          intent: 'E2E 08: resume the parked S1 ask program with the answer.',
          scope: {
            kind: 'keep-last',
          },
        },
      }];
    }
    return [];
  };
}

async function mockState(mock) {
  return (await fetch(`${mock.url}/__mock/state`)).json();
}

async function bodyText(page) {
  return page.locator('body').innerText();
}

async function waitFor(predicate, timeoutMs, intervalMs = 250) {
  const started = Date.now();
  while (Date.now() - started < timeoutMs) {
    const value = await predicate();
    if (value) return value;
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
  }
  return null;
}

async function composerHint(page) {
  return (await page.locator('#hc-composer-hint').innerText().catch(() => '')).trim();
}

async function sendEnabled(page, timeoutMs = 40000) {
  const button = page.locator('button[aria-label="发送消息"]').first();
  const ok = await waitFor(async () => {
    try { return (await button.isEnabled()) ? true : null; } catch { return null; }
  }, timeoutMs, 400);
  return Boolean(ok);
}

async function assistantBodies(page) {
  return page.evaluate(() => {
    const texts = [];
    document.querySelectorAll('.hc-message-in').forEach((el) => {
      const meta = el.querySelector('.hc-message-meta');
      const body = el.querySelector('.hc-message-body');
      if (!body) return;
      const m = meta ? meta.textContent.trim() : '';
      if (/助手/.test(m)) texts.push(body.textContent || '');
    });
    return texts;
  });
}

test('@core E2E 08 S1 ask card, unpark continuation, Esc preserves content', async ({ page, browser }) => {
  test.setTimeout(420000);
  const dir = evidenceDir08();
  const marker = `E2E08-${Date.now()}`;
  const bundle = clientBundleInfo();
  const treeSha = sourceTreeSha256();
  const tag = `${bundle.sha256.slice(0, 8)}-${treeSha.slice(0, 8)}`;

  const scriptState = { cljPlanCalls: 0 };
  const model = await startMockModel({
    reply: `${LONG_MARKER} ${LONG_TEXT}`,
    chunkSize: 48,
    chunkDelayMs: 30,
    captureBodies: true,
    toolCalls: e2e08ToolCalls(scriptState),
  });

  const app = await startApp({
    evidenceDir: dir,
    workspaceFiles: { 'README.md': `# E2E 08 workspace\n${marker}\n` },
    // The unattended Nudge tail would otherwise loop the plan turn; two
    // rounds still exercise continuation without an unbounded tail.
    nudgeRoundBudget: 2,
    models: {
      connections: [{
        id: 'mock',
        name: 'Mock 模型服务',
        baseUrl: `${model.url}/v1`,
        models: [{
          id: 'mock-fast',
          displayName: 'Mock Fast',
          maxOutputTokens: 4096,
          capabilities: ['tools'],
        }],
      }],
      roles: {
        main: { connection: 'mock', model: 'mock-fast' },
        task: { connection: 'mock', model: 'mock-fast' },
      },
    },
  });

  const result = {
    test: 'E2E 08',
    mode: 'automated',
    mock: true,
    startedAt: new Date().toISOString(),
    marker,
    appUrl: app.url,
    mockUrl: model.url,
    chromeVersion: browser.version(),
    clientBundle: bundle,
    sourceTreeSha256: treeSha,
    checks: {},
    screenshots: [],
    notes: [],
  };
  const pageErrors = [];
  page.on('pageerror', (error) => pageErrors.push(String(error).slice(0, 300)));

  const shotName = (name) => shot(page, dir, `e2e08-${tag}-${name}`);
  const finish = async (ok, error) => {
    result.ok = ok && !error;
    if (error) result.error = String(error && error.message ? error.message : error);
    result.finishedAt = new Date().toISOString();
    try { result.mockState = await collectMockState({ model }); } catch { /* best effort */ }
    result.pageErrors = pageErrors;
    const file = path.join(dir, `e2e08-${tag}-result.json`);
    fs.writeFileSync(file, `${JSON.stringify(result, null, 2)}\n`);
    result.evidence = file;
    return result;
  };

  try {
    // --- Phase 1: S1 ask card -------------------------------------------
    await goto(page, app.url, '/sessions');
    await waitForSync(page);
    await newSession(page);
    await composer(page).fill(`${marker} 请进入 S1 并向我提问`);
    assert(await sendEnabled(page), 'send button never enabled for the S1 turn');
    await page.locator('button[aria-label="发送消息"]').first().click();

    const card = page.locator('.hc-ask').first();
    const cardSeen = await waitFor(async () => {
      try { return (await card.count()) > 0 ? true : null; } catch { return null; }
    }, 120000, 400);
    result.checks.askCard = Boolean(cardSeen);
    assert(result.checks.askCard, 'the S1 (ask ...) card never appeared');
    result.screenshots.push(await shotName('ask-card'));

    const cardText = await card.innerText();
    result.askCardText = cardText.slice(0, 500);
    result.checks.askQuestion = cardText.includes(ASK_QUESTION);
    assert(result.checks.askQuestion, `ask card lacks the question: ${cardText}`);

    const answerInput = card.locator('textarea[aria-label="回答内容"]').first();
    result.checks.askInputPresent = (await answerInput.count()) > 0;
    assert(result.checks.askInputPresent, 'ask card has no answer textarea');
    await answerInput.fill(ASK_ANSWER);
    const submit = card.locator('button.hc-button-primary').first();
    result.checks.askSubmitPresent = (await submit.count()) > 0;
    assert(result.checks.askSubmitPresent, 'ask card has no submit button');
    await submit.click();

    const answered = await waitFor(async () => {
      const text = await card.innerText().catch(() => '');
      return /已提交回答|回答记录/.test(text) ? text : null;
    }, 60000, 400);
    result.checks.askAnswered = Boolean(answered);
    assert(result.checks.askAnswered, 'the ask card never showed a submitted answer');
    result.screenshots.push(await shotName('answered'));

    // --- Phase 2: unpark resumes the same run ----------------------------
    // The pending plan turn must issue the unpark clj-plan call; the mock
    // counts clj-plan requests, so `cljPlanCalls === 2` proves the resume
    // turn happened without any new operator send.
    const unparked = await waitFor(async () => {
      if (scriptState.cljPlanCalls >= 2) {
        const bodies = await assistantBodies(page);
        const joined = bodies.join('\n');
        const body = await bodyText(page);
        const busy = /正在生成|无人值守 · 自动继续/.test(body)
          || /当前轮仍在生成|无人值守运行中/.test(await composerHint(page));
        if (!busy) return { bodies: bodies.slice(-3), body: body.slice(0, 4000) };
      }
      return null;
    }, 180000, 800);
    result.checks.unparkContinued = Boolean(unparked);
    result.unparkState = unparked ? {
      cljPlanCalls: scriptState.cljPlanCalls,
      assistantTail: unparked.bodies,
      bodyHead: unparked.body.slice(0, 1200),
    } : { cljPlanCalls: scriptState.cljPlanCalls };
    assert(result.checks.unparkContinued,
      `the run never resumed after the answer (cljPlanCalls=${scriptState.cljPlanCalls})`);
    result.screenshots.push(await shotName('unparked'));

    // The run must go idle on its own; the composer must recover.
    const idle = await waitFor(async () => {
      const hint = await composerHint(page);
      const body = await bodyText(page);
      const busy = /正在生成|无人值守运行中|无人值守 · 自动继续/.test(hint)
        || /无人值守 · 自动继续|正在生成/.test(body);
      return busy ? null : true;
    }, 240000, 1000);
    result.checks.runIdleAfterUnpark = Boolean(idle);
    assert(result.checks.runIdleAfterUnpark, 'the run never became idle after unpark');

    await composer(page).fill('');
    await page.waitForTimeout(400);
    const hintAfter = await composerHint(page);
    result.composerHintAfterAsk = hintAfter;
    result.checks.composerHintNoBlocker = !/当前轮仍在生成|无人值守运行中|正在停止|先选择一个可用模型/.test(hintAfter);
    result.screenshots.push(await shotName('composer-recovered'));

    // --- Phase 3: Esc cancels a streaming turn, content preserved ---------
    await composer(page).fill(`${marker} 请输出一段长分析`);
    assert(await sendEnabled(page), 'send button disabled for the Esc turn');
    await page.locator('button[aria-label="发送消息"]').first().click();

    const bodiesBefore = await assistantBodies(page);
    const beforeCount = bodiesBefore.length;
    const beforeLast = bodiesBefore.length ? bodiesBefore[bodiesBefore.length - 1] : '';

    // Wait for the first chunk of THIS turn's assistant stream.
    let firstChunk = null;
    const chunkDeadline = Date.now() + 120000;
    while (Date.now() < chunkDeadline) {
      const bodies = await assistantBodies(page);
      if (bodies.length > beforeCount) {
        const last = bodies[bodies.length - 1] || '';
        if (last.includes(LONG_MARKER) && last.trim().length > 20) {
          firstChunk = last;
          break;
        }
      }
      await page.waitForTimeout(250);
    }
    result.checks.escFirstChunk = Boolean(firstChunk);
    assert(result.checks.escFirstChunk, 'no assistant stream chunk appeared before Esc');
    result.escBeforeLength = (firstChunk || '').length;
    result.screenshots.push(await shotName('esc-before'));

    await page.keyboard.press('Escape');

    const stopped = await waitFor(async () => {
      const hint = await composerHint(page);
      const body = await bodyText(page);
      if (/已停止/.test(body)) return 'stopped-banner';
      if (!/正在生成|无人值守运行中|停止当前生成|停止无人值守运行/.test(hint)
          && !/正在生成|无人值守 · 自动继续/.test(body)) return 'idle';
      return null;
    }, 60000, 400);
    result.checks.escStopped = Boolean(stopped);
    assert(result.checks.escStopped, 'Esc did not stop the run');

    const bodiesAfter = await assistantBodies(page);
    const afterLast = bodiesAfter.length ? bodiesAfter[bodiesAfter.length - 1] : '';
    result.checks.escContentPreserved = afterLast.length > 0
      && afterLast.includes((firstChunk || '').trim().slice(0, 30));
    assert(result.checks.escContentPreserved,
      'generated assistant text was lost after Esc');
    result.screenshots.push(await shotName('esc-after'));

    // No late append: the same message must not grow after the stop settles.
    await page.waitForTimeout(5000);
    const bodiesLate = await assistantBodies(page);
    const lateLast = bodiesLate.length ? bodiesLate[bodiesLate.length - 1] : '';
    result.checks.escNoLateAppend = lateLast.length <= afterLast.length + 5;
    result.escLengths = { before: result.escBeforeLength, after: afterLast.length, late: lateLast.length };
    assert(result.checks.escNoLateAppend,
      `assistant text grew after Esc: ${afterLast.length} -> ${lateLast.length}`);

    // --- Phase 4: role-boundary refusals via the real program runtime -----
    const probeOut = execFileSync(clojureCommand(), [
      '-M:dev',
      '-i', 'test/e2e/e2e08_probe.clj',
      '-e', 'nil',
    ], { cwd: REPO_ROOT, encoding: 'utf8', timeout: 120000 });
    const probeLine = probeOut.trim().split('\n').pop();
    const probeJson = JSON.parse(probeLine);
    result.boundaryProbe = probeJson;
    const codeOf = (raw) => {
      if (!raw || typeof raw !== 'object') return null;
      const inner = (raw.error && typeof raw.error === 'object') ? raw.error : raw;
      return inner.code ?? inner.status ?? inner.error ?? null;
    };
    result.checks.askForbiddenOutsideS1 =
      probeJson['ask-s2-code'] === 'ASK_FORBIDDEN_OUTSIDE_S1'
      || codeOf(probeJson['ask-s2-raw']) === 'ASK_FORBIDDEN_OUTSIDE_S1';
    result.checks.workerCallTokenRequired =
      probeJson['call-w-code'] === 'TOKEN_REQUIRED'
      || codeOf(probeJson['call-w-raw']) === 'TOKEN_REQUIRED';
    result.checks.askAllowedInS1 = probeJson['ask-s1'] === 'PARKED';
    assert(result.checks.askForbiddenOutsideS1,
      `non-S1 ask probe returned ${JSON.stringify(probeJson)}`);
    assert(result.checks.workerCallTokenRequired,
      `worker call probe returned ${JSON.stringify(probeJson)}`);
    assert(result.checks.askAllowedInS1,
      `S1 ask probe did not park: ${JSON.stringify(probeJson)}`);

    result.checks.noPageErrors = pageErrors.length === 0;
    assert(result.checks.noPageErrors, `page errors: ${pageErrors.join(' | ')}`);

    const failed = Object.entries(result.checks).filter(([, v]) => !v).map(([k]) => k);
    await finish(failed.length === 0, null);
    assert(failed.length === 0, `E2E 08 checks failed: ${failed.join(', ')}`);
  } catch (error) {
    await shot(page, dir, `e2e08-${tag}-failure`).catch(() => {});
    await finish(false, error);
    throw error;
  } finally {
    await model.close();
    await app.close();
  }
});
