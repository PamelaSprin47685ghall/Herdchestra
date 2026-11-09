// Per-spec stack fixture: start the requested mocks, build the app options
// that point at them, start the real Herdchestra server, and hand back one
// closeable handle.  Mocks never share state across specs.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { startMockModel } from './mock-model.mjs';
import { startMockExternalServices, startMockOAuth, startMockWebDAV, startMockS3 } from './mock-services.mjs';
import { startMockSearch } from './mock-search.mjs';
import { startApp } from './app.mjs';
import { checksOk } from './evidence.mjs';

const MOCK_FLAGS = ['model', 'oauth', 'webdav', 's3', 'search'];
const HERE = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(HERE, '../../..');

// Optional keys spelled with a trailing `?`.  The server's schemas reject
// `:allow-insecure-http` as an unknown provider field and would never start a
// loopback OAuth flow; these names must match the Clojure sources verbatim.
const MODEL_AUTH_FIXTURE_KEYS = ['allow-insecure-http?', 'pkce?', 'supports-refresh?'];

function readRepoSource(relative) {
  return fs.readFileSync(path.join(REPO_ROOT, relative), 'utf8');
}

// Unit-level guard against schema drift.  It reads the Clojure sources (the
// same files the server loads), extracts the provider key set, and proves
// both sides of the contract: the server declares each key and
// `buildAppOptions` emits that exact key.  A missing `?` fails here instead
// of surfacing as an unexplained server-startup configuration error.
export function verifyFixtureKeysAgainstServerSource() {
  const providerSource = readRepoSource('src/hc/server/model_auth/provider.clj');
  const providerBlock = /\(def provider-keys[\s\S]*?#\{([\s\S]*?)\}\)/
    .exec(providerSource);
  if (!providerBlock) {
    throw new Error('provider-keys set not found in hc.server.model-auth.provider');
  }
  const serverProviderKeys = new Set(
    [...providerBlock[1].matchAll(/:([A-Za-z0-9?!*+_<>=\/.-]+)/g)]
      .map((match) => match[1]));
  const missingFromServerSource = MODEL_AUTH_FIXTURE_KEYS
    .filter((key) => !serverProviderKeys.has(key));

  const s3Source = readRepoSource('src/hc/server/backup/s3.clj');
  const serviceSource = readRepoSource('src/hc/server/backup/service.clj');
  const pathStyleDeclared = /:path-style\?/.test(s3Source)
    && /:path-style\?/.test(serviceSource);

  // The fixture side, built without starting any mock server.
  const sample = buildAppOptions({
    oauth: { url: 'http://127.0.0.1:9' },
    s3: { url: 'http://127.0.0.1:9' },
  });
  const provider = sample.modelAuth?.providers?.local || {};
  const missingFromFixture = MODEL_AUTH_FIXTURE_KEYS.filter((key) =>
    !Object.prototype.hasOwnProperty.call(provider, key));
  const pathStyleEmitted = sample.backup?.targets?.s3?.['path-style?'] === true;

  const report = {
    ok: missingFromServerSource.length === 0
      && missingFromFixture.length === 0
      && pathStyleDeclared
      && pathStyleEmitted,
    modelAuthFixtureKeys: MODEL_AUTH_FIXTURE_KEYS,
    serverProviderKeys: [...serverProviderKeys].sort(),
    missingFromServerSource,
    missingFromFixture,
    pathStyleDeclared,
    pathStyleEmitted,
  };
  if (!report.ok) {
    throw new Error(`fixture key alignment failed: ${JSON.stringify(report)}`);
  }
  return report;
}

// One strict verdict for every spec.  `checks` holds booleans only; every
// non-`true` value fails (`undefined` included, via checksOk).  `diagnostics`
// holds observations only and must be registered in `diagnosticOnly`; the
// mismatch throw keeps the two lists from drifting apart.
export function summarizeChecks(checks, diagnostics = {},
                                 diagnosticOnly = Object.keys(diagnostics)) {
  const declared = [...diagnosticOnly].sort();
  const observed = Object.keys(diagnostics).sort();
  if (declared.join('|') !== observed.join('|')) {
    throw new Error(
      `diagnosticOnly mismatch: declared=[${declared.join(', ')}] `
      + `diagnostics=[${observed.join(', ')}]`);
  }
  const failedChecks = Object.entries(checks || {})
    .filter(([, value]) => value !== true)
    .map(([key]) => key);
  const ok = checksOk(checks);
  // Keep the two accepted formulas equivalent: an empty check set is a
  // failure even though it has no false entry.
  if (!ok && failedChecks.length === 0) failedChecks.push('no-checks');
  return {
    checks,
    diagnostics,
    diagnosticOnly: declared,
    failedChecks,
    ok,
  };
}

// options.mocks: { model, oauth, webdav, s3, search, external }
//   true/false per service; `external` is shorthand for oauth+webdav+s3.
//   Default (no mocks key): nothing starts — callers opt in explicitly.
export async function startMocks(mocks = {}) {
  const want = (name) => {
    if (mocks[name] !== undefined) return Boolean(mocks[name]);
    if (name !== 'model' && name !== 'search' && mocks.external !== undefined) {
      return Boolean(mocks.external);
    }
    return false;
  };
  const started = {};
  const jobs = [];
  if (want('model')) jobs.push(['model', () => startMockModel(mocks.modelOptions || {})]);
  if (want('search')) jobs.push(['search', () => startMockSearch(mocks.searchOptions || {})]);
  if (want('oauth')) jobs.push(['oauth', () => startMockOAuth(mocks.oauthOptions || {})]);
  if (want('webdav')) jobs.push(['webdav', () => startMockWebDAV()]);
  if (want('s3')) jobs.push(['s3', () => startMockS3()]);
  await Promise.all(jobs.map(async ([name, start]) => {
    started[name] = await start();
  }));
  return {
    ...started,
    urls: Object.fromEntries(Object.entries(started).map(([k, v]) => [k, v.url])),
    async close() {
      await Promise.all(Object.values(started).map((mock) => mock.close()));
    },
  };
}

// Build startApp options from running mocks.  `appOptions` is merged last so
// callers can override or extend (workspaceFiles, evidenceDir, roles, ...).
export function buildAppOptions(mocks, appOptions = {}) {
  const options = { ...appOptions };
  if (mocks.model && !options.models) {
    options.models = {
      connections: [{
        id: 'mock',
        name: 'Mock 模型服务',
        baseUrl: `${mocks.model.url}/v1`,
        models: [{ id: 'mock-fast', displayName: 'Mock Fast' }],
      }],
      roles: { main: { connection: 'mock', model: 'mock-fast' } },
    };
  }
  if (mocks.oauth && !options.modelAuth) {
    const base = mocks.oauth.url;
    options.modelAuth = {
      providers: {
        local: {
          id: 'local',
          name: 'Mock OAuth',
          issuer: base,
          authorizationEndpoint: `${base}/authorize`,
          tokenEndpoint: `${base}/token`,
          deviceAuthorizationEndpoint: `${base}/device_authorization`,
          userinfoEndpoint: `${base}/userinfo`,
          revocationEndpoint: `${base}/revoke`,
          clientId: 'mock-client',
          clientSecret: 'mock-secret',
          flows: ['authorization-code', 'device-code'],
          defaultScopes: ['openid', 'email', 'profile'],
          baseUrl: `${base}/v1`,
          accountClaims: ['email', 'sub'],
          // The server's provider schema names these keys with a trailing
          // `?`.  kebab-case conversion cannot add it, so the keys are
          // spelled out; `:allow-insecure-http` would be rejected as an
          // unknown field and loopback HTTP providers would never start.
          'allow-insecure-http?': true,
          'pkce?': true,
          'supports-refresh?': true,
        },
      },
    };
  }
  if ((mocks.webdav || mocks.s3) && !options.backup) {
    const targets = {};
    if (mocks.webdav) {
      targets.webdav = {
        kind: 'webdav',
        baseUrl: mocks.webdav.url,
        directory: 'backups',
        username: 'mock',
        password: 'mock',
      };
    }
    if (mocks.s3) {
      targets.s3 = {
        kind: 's3',
        endpoint: mocks.s3.url,
        bucket: 'mock-bucket',
        region: 'us-east-1',
        accessKey: 'mock-access',
        secretKey: 'mock-secret',
        prefix: 'backups',
        'path-style?': true,
      };
    }
    options.backup = { targets, defaultTargetId: 'webdav' };
  }
  if (mocks.search && !options.search) {
    const services = [];
    if (mocks.search.searxngUrl) {
      services.push({
        id: 'mock-searxng',
        name: 'Mock SearXNG',
        kind: 'searxng',
        baseUrl: mocks.search.searxngUrl,
        enabled: true,
      });
    }
    if (mocks.search.braveUrl) {
      services.push({
        id: 'mock-brave',
        name: 'Mock Brave',
        kind: 'brave',
        baseUrl: mocks.search.braveUrl,
        enabled: true,
        credentials: { apiKey: 'mock-token' },
      });
    }
    options.search = { services, defaultServiceId: services[0]?.id };
  }
  return options;
}

// Full stack: mocks -> app options -> app.  `close()` stops the app first,
// then every mock.
export async function startStack(stackOptions = {}) {
  const { mocks: mockOptions = {}, app: appOptions = {}, evidenceName } = stackOptions;
  const mocks = await startMocks(mockOptions);
  try {
    const app = await startApp(buildAppOptions(mocks, appOptions));
    return {
      mocks,
      app,
      mockUrls: mocks.urls,
      async close() {
        await app.close();
        await mocks.close();
      },
    };
  } catch (error) {
    await mocks.close();
    throw error;
  }
}
