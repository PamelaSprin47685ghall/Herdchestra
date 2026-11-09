// Unified evidence writers for the E2E suite.  Every automated spec records
// the same envelope: checks, client bundle hash, source tree hash, mock
// state and screenshot paths.
import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { clientBundleInfo, evidenceDir, writeJson, REPO_ROOT } from './ui.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));

// Dual-hash requirement from the 0.0.1 manual evidence: the client bundle
// hash alone does not pin the server-side code the specs exercise.
export function sourceTreeSha256() {
  // `LC_ALL=C` pins the collation order.  The release evidence tooling uses
  // the same command, so a locale change must not move this hash.
  const script = 'find src test -type f | LC_ALL=C sort | xargs sha256sum | sha256sum';
  const out = execFileSync('bash', ['-c', script], {
    cwd: REPO_ROOT,
    encoding: 'utf8',
    maxBuffer: 16 * 1024 * 1024,
  });
  return out.trim().split(/\s+/)[0];
}

export function mockStateSnapshot(mocks) {
  const snapshot = {};
  for (const [name, mock] of Object.entries(mocks || {})) {
    if (!mock || typeof mock !== 'object') continue;
    snapshot[name] = {
      url: mock.url || null,
      port: mock.port ?? null,
    };
  }
  return snapshot;
}

// Pull the request log from each mock that exposes /__mock/state.
export async function collectMockState(mocks) {
  const states = {};
  await Promise.all(Object.entries(mocks || {}).map(async ([name, mock]) => {
    if (!mock || !mock.url) return;
    try {
      const response = await fetch(`${mock.url}/__mock/state`);
      if (response.ok) states[name] = await response.json();
    } catch {
      // A mock without /__mock/state contributes nothing.
    }
  }));
  return states;
}

// Strict aggregate for automated checks.  Diagnostic values (strings,
// arrays, counts) must stay out of `checks`; mixing them in silently makes
// `Object.values(checks).every(Boolean)` meaningless.
export function checksOk(checks) {
  const values = Object.values(checks || {});
  return values.length > 0 && values.every((value) => value === true);
}

// Write the standard result envelope.  `extra` merges top-level fields such
// as appUrl, mockUrls, chromeVersion or error.
export function recordEvidence({ dir, name = 'result', test, mode = 'automated',
                                 mock = true, startedAt, finishedAt, extra = {},
                                 checks, ok, evidence }) {
  const clientBundle = clientBundleInfo();
  const normalizedChecks = checks || {};
  const result = {
    test,
    mode,
    mock,
    startedAt,
    finishedAt: finishedAt || new Date().toISOString(),
    // `clientBundle.sha256` is the release-visible client pin; the extra
    // mtime/path fields travel beside it.
    clientBundle,
    sourceTreeSha256: sourceTreeSha256(),
    checks: normalizedChecks,
    screenshots: [],
    ...extra,
    // When the caller omits `ok`, derive it strictly from booleans.  A
    // non-boolean diagnostic left in `checks` makes the result false rather
    // than letting a truthy string or count masquerade as a passed check.
    ok: ok === undefined ? checksOk(normalizedChecks) : Boolean(ok),
    evidence: evidence || null,
  };
  if (!result.evidence) {
    result.evidence = writeJson(dir || evidenceDir(name), `${name}-result`, result);
  }
  return result;
}
