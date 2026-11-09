# E2E spec quality ledger — 0.0.3

Static preflight only. No Playwright, JVM, or test was run for this ledger.
Source of truth: the completed read-only audit of the 22 `test/e2e/e2e-*.spec.mjs`
files and their shared support. Line numbers below are the audit snapshot; use
the spec file names when a later edit shifts lines.

## 22-spec ledger

| E2E | spec 文件 | tag | ok 推导方式 | 字面 true 数 | 未参与 ok 的 checks | 负向断言数 | waitForTimeout 数（最大 ms） | 判定 |
|---|---|---|---|---|---|---|---|---|
| 01 | e2e-01-empty-install.spec.mjs | @core | summarizeChecks + assert(result.ok) | 0 | 0 | 0 | 1 (100) | READY* |
| 02 | e2e-02.spec.mjs | @core | Object.values(checks).every + assert(result.ok) | 2 checks (L286,L368) | 0 | 0 | 5 (3000) | READY* |
| 03 | e2e-03-streaming-draft.spec.mjs | @core | Object.values(checks).every + assert(result.ok) | 0 | 0 | 1 (L100) | 8 (8000) | READY |
| 04 | e2e-04.spec.mjs | @core | Object.values(checks).every + assert(result.ok) | 0 | 0 | 0 | 5 (500) | READY* |
| 05 | e2e-05.spec.mjs | @core | Object.values(checks).every + assert(result.ok) | 1 ok-helper (L159) | 0 | 0 | 4 (5000) | READY* |
| 06 | e2e-06.spec.mjs | @core | Object.values(checks).every + assert(result.ok) | 0 | 0 | 0 | 3 (4000) | READY* |
| 07 | e2e-07.spec.mjs | @core | Object.values(checks).every + assert(result.ok) | 0 | 0 | 0 | 1 (1500) | READY* |
| 08 | e2e-08.spec.mjs | @core | failed.length===0 + assert(failed.length===0) | 0 | 0 | 0 | 3 (5000) | READY* |
| 09 | e2e-09-protected-commit.spec.mjs | @core | Object.values(checks).every + assert(result.ok) | 0 | 0 | 0 | 0 | READY* |
| 10 | e2e-10-tool-interrupt.spec.mjs | @core | failedChecks + checks.all + assert(result.checks.all) | 2 checks (L201,L261) | 0 | 1 (L206) | 3 (3000) | READY |
| 11 | e2e-11.spec.mjs | @e2e | Object.values(result.checks).every(pass) + assert(result.ok) | 0 | 0 | 0 | 7 (2500) | READY* |
| 12 | e2e-12.spec.mjs | @core | Object.values(result.checks).every(pass) + failedChecks + assert(result.ok) | 0 | 0 | 0 | 6 (1500) | READY* |
| 13 | e2e-13.spec.mjs | @e2e | checksOk + uiAssert(ok) | 0 | 0 | 0 | 3 (600) | READY* |
| 14 | e2e-14.spec.mjs | @e2e | Object.values(checks).every + assert(evidence.ok) | 11 checks (L243,259,306,318,326,336,355,381,413,437,444) | 0 | 0 | 7 (1500) | READY* |
| 15 | e2e-15.spec.mjs | @core | summarizeChecks + assert(evidence.ok) | 0 | 0 | 0 | 0 | READY* |
| 16 | e2e-16.spec.mjs | @e2e | checksOk + assertCheck(ok) | 0 | 0 | 1 (L354) | 0 | READY |
| 17 | e2e-17.spec.mjs | @e2e | summarizeChecks (2 tests) + assert(summary.ok) | 0 | 0 | 0 | 1 (2000) | READY* |
| 18 | e2e-18-disk-full.spec.mjs | @core @disk | checksOk + assert(evidence.ok) | 3 (L66,L92 ok-helper; L210 checks) | 0 | 0 | 0 | READY* |
| 19 | e2e-19-ime-a11y.spec.mjs | @core @a11y | checksOk + assert(evidence.ok) | 0 | 0 | 0 | 4 (500) | READY* |
| 20 | e2e-20.spec.mjs | @core | Object.values(checks).every(pass) + assert(result.ok) | 0 | 0 | 0 | 19 (3000) | READY* |
| 21 | e2e-21.spec.mjs | @core | Object.values(checks).every + assert(result.ok) | 0 | 0 | 0 | 7 (1000) | READY* |
| 22 | e2e-22.spec.mjs | @e2e | summarizeChecks + finalizeEvidence + assertCheck(finalOk) | 0 | 0 | 3 (L472,L474,L480) | 0 | READY |

Counts: **READY = 4** (E2E 03, 10, 16, 22); **READY* = 18**.

## 判定口径

- **READY**: `node --check` passes; a strict global `ok` is derived from every
  check (`checksOk`, `summarizeChecks`, `Object.values(checks).every`,
  `failedChecks`/`failed.length`, or `finalOk`); at least one
  `assert`/`assertCheck`/`uiAssert` consumes that verdict; no final-verdict
  `ok: true` literal; 0 unconsumed check keys; at least one `not`/`!==` style
  negative assertion.
- **READY***: the same structural guarantees, but the spec has no
  `not`/`!==`-form negative assertion. Most of these compute a negative fact
  into `checks.x` and assert it as `true`; that is still a real check, but it
  does not satisfy the mechanical negative-assertion gate. `READY*` is
  auto-runnable; treat it as **NEEDS-FIX before freezing evidence** if the
  negative-assertion rule is a hard gate.

No spec in this audit sets its final verdict with a literal `ok: true` or an
unchecked `checks.x = true`. Literal-true occurrences are explicit check
values or helper returns (E2E 02/05/10/14/18); all are either consumed by the
global verdict or are helper protocol values, not release verdicts.

## Gate integrity

- **playwright.config.mjs**: PASS for coverage. `testDir` is `test/e2e` and
  `testMatch` is `**/*.spec.mjs`, so all 22 specs are included (plus
  `memory.spec.mjs` and `mock-services.spec.mjs`). `workers=1` and
  `fullyParallel=false`; the shared `outputDir`
  `plans/evidence/e2e-auto/playwright-artifacts` cannot suffer a parallel
  trace `ENOENT`.
- **support/app.mjs**: PASS. The child `stderr` handler matches
  `/Syntax error reading source/` and rejects immediately with the reader
  location (around L212-L246), rather than waiting for the listen timeout.
- **Evidence names**: PASS. Evidence files are spec-prefixed; E2E 01 writes
  `e2e01-result` and `e2e01-oauth-result`, E2E 17 writes `e2e-17.json` and
  `e2e-17-s3.json`, and no two specs share the same `(directory, name)` pair.

## E2E 20: the 19 `waitForTimeout` points

| Line | Wait point | Real condition available? |
|---|---|---|
| 264 | body-text poll inside a bounded loop | Yes: `expect.poll` / `waitForFunction` on the same text |
| 275 | after `fill` on the controlled session-search input | Yes: assert `input` value / `expect(input).toHaveValue` |
| 283 | after replace-all keyboard input | Yes: assert the input value |
| 314 | after keyboard typing fallback | Yes: assert the input value |
| 319 | send button enabled retry loop | Yes: `expect(sendBtn).toBeEnabled()` |
| 348 | send-button retry after disabled state | Yes: same enabled condition |
| 367 | assistant-body count poll | Yes: `waitForFunction` / `expect.poll` on count |
| 387 | quiet-settle poll | Yes: `expect.poll` on the busy-text predicate |
| 437 | generic enabled-helper poll | Yes: `expect.poll` on the element state |
| 485 | after every `clickButton` click | Yes: wait for the specific post-click locator/text |
| 660 | memory-suggestion candidate poll (3s interval) | Yes: `expect.poll` with a long timeout/interval |
| 697 | discard-completion poll | Yes: `expect.poll` on the discarded state |
| 712 | after discard, before reading message bodies | Yes: `waitForFunction` on the message text |
| 730 | after client-side session filter | Yes: `expect.poll` on the filtered list |
| 744 | memory page after discard | Yes: wait for the memory panel/row |
| 815 | backup preview-enabled poll | Yes: `expect.poll` on `disabled === false` |
| 845 | deleted-session area load | Yes: wait for the deleted-area text/row |
| 907 | transfer-completion poll | Yes: `expect.poll` on the transferred state |
| 911 | after transfer, before reading content | Yes: `waitForFunction` on the transferred body |

All 19 have an observable condition. None requires a blind fixed sleep; the
three loop intervals (264/319/437) and 660 are polling cadences rather than
one-shot waits. This is a quality/de-flake list, not a runtime blocker.

## Residual manual item

All 22 specs are structurally auto-runnable. **E2E 18 requires a Linux
environment with working `unshare`/`mount`/`umount`/`dd`/`df` and permission to
mount a tmpfs.** On non-Linux or restricted containers it records
`platformSupported=false` and calls `test.skip`; with `HC_E2E_REQUIRE_DISK=1`
it throws instead of skipping (`e2e-18-disk-full.spec.mjs` L208-L211), so the
release run can require a real disk-full execution rather than a silent skip.

## Release verdict

This ledger can support the narrow claim that **the 22 specs are structurally
fail-capable and auto-runnable**, but it **cannot alone be the release evidence
that all 22 actually pass**: it lacks a real Playwright run with evidence JSON,
the E2E 18 privileged disk environment, and negative-assertion hardening for
the 18 `READY*` specs.
