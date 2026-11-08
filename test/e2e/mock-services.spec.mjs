// Mock-service smoke: the E2E suite must prove that the OAuth, WebDAV, S3
// and search doubles answer their minimal contracts without a real account,
// bucket or search provider.  The OAuth checks mirror
// test/hc/server/model_auth/support.clj: unprefixed paths, PKCE S256 on the
// authorization_code grant, and the device pending/slow_down state machine.
import crypto from 'node:crypto';
import { test } from '@playwright/test';
import { startMockExternalServices } from './support/mock-services.mjs';
import { startMockSearch } from './support/mock-search.mjs';
import { assert, evidenceDir, writeJson } from './support/ui.mjs';
import { collectMockState, sourceTreeSha256 } from './support/evidence.mjs';

function b64url(buffer) {
  return Buffer.from(buffer).toString('base64')
    .replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '');
}

function pkcePair() {
  const verifier = b64url(crypto.randomBytes(32));
  const challenge = b64url(crypto.createHash('sha256').update(verifier).digest());
  return { verifier, challenge };
}

async function postForm(url, params) {
  const response = await fetch(url, {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams(params),
  });
  return { status: response.status, body: await response.json().catch(() => ({})) };
}

test('@mock mock OAuth / WebDAV / S3 / search minimal endpoints', async () => {
  const dir = evidenceDir('mock-services');
  const services = await startMockExternalServices();
  const search = await startMockSearch();
  const result = {
    test: 'mock-services',
    mode: 'automated',
    mock: true,
    startedAt: new Date().toISOString(),
    sourceTreeSha256: sourceTreeSha256(),
    endpoints: {
      oauth: services.oauth.url,
      webdav: services.webdav.url,
      s3: services.s3.url,
      search: search.url,
    },
    checks: {},
  };
  try {
    // --- OAuth discovery -------------------------------------------------
    const wellKnown = await fetch(`${services.oauth.url}/.well-known/oauth-authorization-server`);
    const wellKnownJson = await wellKnown.json();
    result.checks.oauthWellKnown = wellKnown.ok
      && wellKnownJson.mock === true
      && wellKnownJson.authorization_endpoint === `${services.oauth.url}/authorize`
      && wellKnownJson.token_endpoint === `${services.oauth.url}/token`
      && wellKnownJson.device_authorization_endpoint === `${services.oauth.url}/device_authorization`
      && wellKnownJson.userinfo_endpoint === `${services.oauth.url}/userinfo`
      && wellKnownJson.revocation_endpoint === `${services.oauth.url}/revoke`;

    // --- authorization_code + PKCE S256 full chain ------------------------
    const redirectUri = 'http://127.0.0.1:9999/callback';
    const { verifier, challenge } = pkcePair();
    const stateParam = 'state-e2e-1';
    const authorize = await fetch(
      `${services.oauth.url}/authorize?response_type=code&client_id=mock-client`
      + `&redirect_uri=${encodeURIComponent(redirectUri)}`
      + `&scope=${encodeURIComponent('openid email profile')}`
      + `&state=${stateParam}`
      + `&code_challenge=${challenge}&code_challenge_method=S256`,
      { redirect: 'manual' });
    const location = authorize.headers.get('location') || '';
    const callback = new URL(location);
    const code = callback.searchParams.get('code');
    result.checks.oauthAuthorizeRedirect = authorize.status === 302
      && callback.origin + callback.pathname === redirectUri
      && Boolean(code)
      && callback.searchParams.get('state') === stateParam;

    // PKCE failure: wrong verifier must be refused.
    const badVerifier = await postForm(`${services.oauth.url}/token`, {
      grant_type: 'authorization_code',
      code,
      redirect_uri: redirectUri,
      code_verifier: b64url(crypto.randomBytes(32)),
    });
    result.checks.oauthPkceRejectsBadVerifier = badVerifier.status === 400
      && badVerifier.body.error === 'invalid_grant';

    // redirect_uri mismatch must be refused even with the right verifier.
    const wrongRedirect = await postForm(`${services.oauth.url}/token`, {
      grant_type: 'authorization_code',
      code,
      redirect_uri: 'http://127.0.0.1:9999/other',
      code_verifier: verifier,
    });
    result.checks.oauthRedirectMismatch = wrongRedirect.status === 400
      && wrongRedirect.body.error === 'invalid_grant';

    // Correct verifier succeeds; the code is single-use.
    const tokenResponse = await postForm(`${services.oauth.url}/token`, {
      grant_type: 'authorization_code',
      code,
      redirect_uri: redirectUri,
      code_verifier: verifier,
    });
    result.checks.oauthAuthorizationCodeToken = tokenResponse.status === 200
      && Boolean(tokenResponse.body.access_token)
      && tokenResponse.body.token_type === 'Bearer'
      && Boolean(tokenResponse.body.refresh_token);

    const replay = await postForm(`${services.oauth.url}/token`, {
      grant_type: 'authorization_code',
      code,
      redirect_uri: redirectUri,
      code_verifier: verifier,
    });
    result.checks.oauthCodeSingleUse = replay.status === 400
      && replay.body.error === 'invalid_grant';

    const userResponse = await fetch(`${services.oauth.url}/userinfo`, {
      headers: { authorization: `Bearer ${tokenResponse.body.access_token}` },
    });
    const user = await userResponse.json();
    result.checks.oauthUserInfo = userResponse.ok
      && user.sub === 'mock-user-1'
      && user.email === 'mock@example.test'
      && user.iss === services.oauth.url;

    // --- device grant: pending -> slow_down -> approved ------------------
    const deviceResponse = await postForm(`${services.oauth.url}/device_authorization`, {
      client_id: 'mock-client',
      scope: 'openid email profile',
    });
    const device = deviceResponse.body;
    result.checks.oauthDeviceAuthorization = deviceResponse.status === 200
      && Boolean(device.device_code) && Boolean(device.user_code)
      && device.verification_uri === `${services.oauth.url}/device`
      && device.verification_uri_complete.startsWith(`${services.oauth.url}/device?code=`);

    const poll = (deviceCode) => postForm(`${services.oauth.url}/token`, {
      grant_type: 'urn:ietf:params:oauth:grant-type:device_code',
      device_code: deviceCode,
    });
    const poll1 = await poll(device.device_code);
    result.checks.oauthDevicePending = poll1.status === 400
      && poll1.body.error === 'authorization_pending';

    // Approve through the verification_uri confirmation page contract.
    const confirm = await fetch(`${services.oauth.url}/device/confirm`, {
      method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ user_code: device.user_code, decision: 'approve' }),
    });
    result.checks.oauthDeviceConfirmPage = confirm.ok;

    const errors = [];
    let deviceToken = null;
    for (let i = 0; i < 8 && !deviceToken; i += 1) {
      const attempt = await poll(device.device_code);
      if (attempt.status === 200) {
        deviceToken = attempt.body;
      } else if (attempt.body.error === 'access_denied'
                 || attempt.body.error === 'expired_token'
                 || attempt.body.error === 'invalid_grant') {
        break;
      } else {
        errors.push(attempt.body.error);
      }
    }
    result.checks.oauthDeviceSlowDown = errors.includes('slow_down');
    result.checks.oauthDeviceToken = Boolean(deviceToken?.access_token);

    // Denied device must surface access_denied.
    const deniedDevice = await postForm(`${services.oauth.url}/device_authorization`, {
      client_id: 'mock-client',
      scope: 'openid',
    });
    await fetch(`${services.oauth.url}/device/confirm`, {
      method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ user_code: deniedDevice.body.user_code, decision: 'deny' }),
    });
    let deniedError = null;
    for (let i = 0; i < 6 && !deniedError; i += 1) {
      const attempt = await poll(deniedDevice.body.device_code);
      if (attempt.body.error === 'access_denied') {
        deniedError = attempt.body.error;
      } else if (attempt.body.error === 'expired_token'
                 || attempt.body.error === 'invalid_grant') {
        break;
      }
    }
    result.checks.oauthDeviceDenied = deniedError === 'access_denied';

    // Revoke is accepted and removes the token.
    const revoke = await postForm(`${services.oauth.url}/revoke`, {
      token: tokenResponse.body.access_token,
      token_type_hint: 'access_token',
    });
    result.checks.oauthRevoke = revoke.status === 200;
    const revokedUser = await fetch(`${services.oauth.url}/userinfo`, {
      headers: { authorization: `Bearer ${tokenResponse.body.access_token}` },
    });
    result.checks.oauthRevokeEffective = revokedUser.status === 401;

    // --- WebDAV ----------------------------------------------------------
    const put = await fetch(`${services.webdav.url}/dav/e2e.txt`, { method: 'PUT', body: 'webdav body' });
    const get = await fetch(`${services.webdav.url}/dav/e2e.txt`);
    const getText = await get.text();
    const propfind = await fetch(`${services.webdav.url}/dav`, {
      method: 'PROPFIND',
      headers: { depth: '1' },
    });
    const propfindText = await propfind.text();
    result.checks.webdavPut = put.ok;
    result.checks.webdavGet = get.ok && getText === 'webdav body';
    result.checks.webdavPropfind = propfind.status === 207
      && propfindText.includes('/dav/e2e.txt')
      && propfindText.includes('getlastmodified');
    const del = await fetch(`${services.webdav.url}/dav/e2e.txt`, { method: 'DELETE' });
    result.checks.webdavDelete = del.status === 204;

    // --- S3 ---------------------------------------------------------------
    const s3Put = await fetch(`${services.s3.url}/e2e-bucket/report.csv`, {
      method: 'PUT',
      body: 'name,value\nworker,7\n',
    });
    const s3Get = await fetch(`${services.s3.url}/e2e-bucket/report.csv`);
    const s3Text = await s3Get.text();
    const s3List = await fetch(`${services.s3.url}/e2e-bucket?list-type=2`);
    const s3ListText = await s3List.text();
    result.checks.s3Put = s3Put.ok;
    result.checks.s3Get = s3Get.ok && s3Text.includes('worker,7');
    result.checks.s3ListBucket = s3List.ok
      && s3ListText.includes('<Key>report.csv</Key>')
      && s3ListText.includes('<IsTruncated>false</IsTruncated>');
    const s3Head = await fetch(`${services.s3.url}/e2e-bucket/report.csv`, { method: 'HEAD' });
    result.checks.s3HeadNoBody = s3Head.status === 200
      && (await s3Head.text()) === '';

    // --- Search ------------------------------------------------------------
    const brave = await fetch(`${search.url}/res/v1/web/search?q=herdchestra&count=4`, {
      headers: { 'x-subscription-token': 'mock-token', accept: 'application/json' },
    });
    const braveJson = await brave.json();
    result.checks.searchBrave = brave.ok
      && Array.isArray(braveJson.web?.results)
      && braveJson.web.results.length === 4
      && braveJson.web.results.every((r) => r.url && r.title);
    const searxng = await fetch(`${search.url}/search?q=herdchestra&format=json`);
    const searxngJson = await searxng.json();
    result.checks.searchSearxng = searxng.ok
      && Array.isArray(searxngJson.results)
      && searxngJson.results.length === 4
      && searxngJson.results.some((r) => r.url === 'https://example.test/docs/one')
      && searxngJson.results.some((r) => r.publishedDate === null);

    result.checks.markedMock = s3Put.headers.get('x-herdchestra-mock') === 'oauth|webdav|s3'
      && brave.headers.get('x-herdchestra-mock') === 'search';
    result.mockState = await collectMockState({ oauth: services.oauth, webdav: services.webdav, s3: services.s3, search });

    result.ok = Object.values(result.checks).every(Boolean);
    result.finishedAt = new Date().toISOString();
    result.evidence = writeJson(dir, 'mock-services-result', result);
    assert(result.ok, `mock service checks failed: ${JSON.stringify(result.checks)}`);
  } catch (error) {
    result.ok = false;
    result.error = error.message;
    result.finishedAt = new Date().toISOString();
    result.evidence = writeJson(dir, 'mock-services-result', result);
    throw error;
  } finally {
    await services.close();
    await search.close();
  }
});
