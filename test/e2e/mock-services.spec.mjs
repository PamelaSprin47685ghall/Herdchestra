// Mock-service smoke: the E2E suite must prove that the OAuth, WebDAV and S3
// doubles answer their minimal contracts without a real account or bucket.
// This is a test double check, not a user journey.
import { test } from '@playwright/test';
import { startMockExternalServices } from './support/mock-services.mjs';
import { assert, evidenceDir, writeJson } from './support/ui.mjs';

test('@mock mock OAuth / WebDAV / S3 minimal endpoints', async () => {
  const dir = evidenceDir('mock-services');
  const services = await startMockExternalServices();
  const result = {
    test: 'mock-services',
    mode: 'automated',
    mock: true,
    startedAt: new Date().toISOString(),
    endpoints: {
      oauth: services.oauth.url,
      webdav: services.webdav.url,
      s3: services.s3.url,
    },
    checks: {},
  };
  try {
    const wellKnown = await fetch(`${services.oauth.url}/.well-known/oauth-authorization-server`);
    const wellKnownJson = await wellKnown.json();
    result.checks.oauthWellKnown = wellKnown.ok && wellKnownJson.mock === true;

    const deviceResponse = await fetch(`${services.oauth.url}/oauth/device/code`, { method: 'POST' });
    const device = await deviceResponse.json();
    result.checks.oauthDeviceCode = deviceResponse.ok && Boolean(device.device_code) && Boolean(device.user_code);
    const tokenResponse = await fetch(`${services.oauth.url}/oauth/token`, {
      method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        grant_type: 'urn:ietf:params:oauth:grant-type:device_code',
        device_code: device.device_code,
      }),
    });
    const token = await tokenResponse.json();
    const userResponse = await fetch(`${services.oauth.url}/oauth/userinfo`, {
      headers: { authorization: `Bearer ${token.access_token}` },
    });
    const user = await userResponse.json();
    result.checks.oauthDeviceToken = tokenResponse.ok && Boolean(token.access_token);
    result.checks.oauthUserInfo = userResponse.ok && user.sub === 'mock-user';

    const authorize = await fetch(`${services.oauth.url}/oauth/authorize?redirect_uri=${encodeURIComponent('http://127.0.0.1/callback')}&state=state-1`, { redirect: 'manual' });
    const location = authorize.headers.get('location') || '';
    const code = new URL(location).searchParams.get('code');
    const codeTokenResponse = await fetch(`${services.oauth.url}/oauth/token`, {
      method: 'POST',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ grant_type: 'authorization_code', code }),
    });
    const codeToken = await codeTokenResponse.json();
    result.checks.oauthCallbackFlow = authorize.status === 302
      && Boolean(code) && codeTokenResponse.ok && Boolean(codeToken.access_token);

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
    result.checks.webdavPropfind = propfind.status === 207 && propfindText.includes('/dav/e2e.txt');
    const del = await fetch(`${services.webdav.url}/dav/e2e.txt`, { method: 'DELETE' });
    result.checks.webdavDelete = del.status === 204;

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
    result.checks.s3ListBucket = s3List.ok && s3ListText.includes('<Key>report.csv</Key>');
    result.checks.markedMock = s3Put.headers.get('x-herdchestra-mock') === 'oauth|webdav|s3';

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
  }
});
