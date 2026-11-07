// Mock OAuth, WebDAV and S3 services for E2E.  These are test doubles, not
// emulations of the real providers: they accept every credential and keep
// state in memory.  Every response is marked with x-herdchestra-mock.
import http from 'node:http';
import { URL } from 'node:url';

const MOCK_HEADER = 'oauth|webdav|s3';
const MAX_REQUESTS = 200;
const MAX_BODY_BYTES = 1024 * 1024;
const MAX_TOTAL_BYTES = 16 * 1024 * 1024;

function remember(map, key, value) {
  map.set(key, value);
  while (map.size > MAX_REQUESTS) map.delete(map.keys().next().value);
}

function logRequest(state, req, path) {
  state.requests.push({ at: new Date().toISOString(), method: req.method, path });
  if (state.requests.length > MAX_REQUESTS) state.requests.shift();
}

function xmlEscape(value) {
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&apos;');
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let total = 0;
    let tooLarge = false;
    req.on('data', (chunk) => {
      if (tooLarge) return;
      total += chunk.length;
      if (total > MAX_BODY_BYTES) {
        tooLarge = true;
        chunks.length = 0;
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => resolve(tooLarge ? null : Buffer.concat(chunks, total)));
    req.on('error', reject);
  });
}

function send(res, status, body, headers = {}) {
  const buffer = Buffer.isBuffer(body) ? body : Buffer.from(String(body));
  res.writeHead(status, {
    'content-length': buffer.length,
    'cache-control': 'no-store',
    'x-herdchestra-mock': MOCK_HEADER,
    ...headers,
  });
  res.end(buffer);
}

function json(res, status, value) {
  send(res, status, JSON.stringify(value), {
    'content-type': 'application/json; charset=utf-8',
  });
}

async function listen(server) {
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  return server.address().port;
}

function close(server) {
  return new Promise((resolve) => server.close(resolve));
}

// ---------------------------------------------------------------------------
// OAuth: device code and authorization-code callback.

export async function startMockOAuth() {
  const state = {
    requests: [],
    deviceCodes: new Map(),
    authCodes: new Map(),
    accessTokens: new Map(),
  };
  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    logRequest(state, req, url.pathname);
    if (req.method === 'GET' && url.pathname === '/.well-known/oauth-authorization-server') {
      return json(res, 200, {
        issuer: `http://127.0.0.1:${server.address().port}`,
        authorization_endpoint: `http://127.0.0.1:${server.address().port}/oauth/authorize`,
        token_endpoint: `http://127.0.0.1:${server.address().port}/oauth/token`,
        device_authorization_endpoint: `http://127.0.0.1:${server.address().port}/oauth/device/code`,
        userinfo_endpoint: `http://127.0.0.1:${server.address().port}/oauth/userinfo`,
        mock: true,
      });
    }
    if (req.method === 'POST' && url.pathname === '/oauth/device/code') {
      const deviceCode = `device-${state.deviceCodes.size + 1}`;
      const userCode = 'MOCK-1234';
      remember(state.deviceCodes, deviceCode, { approved: true, userCode });
      return json(res, 200, {
        device_code: deviceCode,
        user_code: userCode,
        verification_uri: `http://127.0.0.1:${server.address().port}/oauth/device`,
        verification_uri_complete:
          `http://127.0.0.1:${server.address().port}/oauth/device?user_code=${userCode}`,
        expires_in: 600,
        interval: 1,
        mock: true,
      });
    }
    if (req.method === 'GET' && url.pathname === '/oauth/authorize') {
      const redirect = url.searchParams.get('redirect_uri') || 'http://127.0.0.1/callback';
      const code = `code-${state.authCodes.size + 1}`;
      remember(state.authCodes, code, { redirect });
      const target = new URL(redirect);
      target.searchParams.set('code', code);
      if (url.searchParams.get('state')) target.searchParams.set('state', url.searchParams.get('state'));
      res.writeHead(302, { location: target.toString(), 'x-herdchestra-mock': MOCK_HEADER });
      return res.end();
    }
    if (req.method === 'POST' && url.pathname === '/oauth/token') {
      const raw = await readBody(req);
      if (raw === null) return json(res, 413, { error: 'request_too_large', mock: true });
      const form = new URLSearchParams(raw.toString('utf8'));
      const grant = form.get('grant_type');
      if (grant === 'urn:ietf:params:oauth:grant-type:device_code') {
        const code = form.get('device_code');
        if (!state.deviceCodes.has(code)) {
          return json(res, 400, { error: 'invalid_grant', mock: true });
        }
        const token = `access-device-${code}`;
        remember(state.accessTokens, token, { sub: 'mock-user', email: 'mock@example.test' });
        return json(res, 200, {
          access_token: token,
          token_type: 'Bearer',
          expires_in: 3600,
          refresh_token: `refresh-${code}`,
          scope: 'openid profile email',
          mock: true,
        });
      }
      if (grant === 'authorization_code') {
        const code = form.get('code');
        if (!state.authCodes.has(code)) {
          return json(res, 400, { error: 'invalid_grant', mock: true });
        }
        const token = `access-code-${code}`;
        remember(state.accessTokens, token, { sub: 'mock-user', email: 'mock@example.test' });
        return json(res, 200, {
          access_token: token,
          token_type: 'Bearer',
          expires_in: 3600,
          refresh_token: `refresh-${code}`,
          scope: 'openid profile email',
          mock: true,
        });
      }
      if (grant === 'refresh_token') {
        return json(res, 200, {
          access_token: `access-refresh-${state.accessTokens.size + 1}`,
          token_type: 'Bearer',
          expires_in: 3600,
          scope: 'openid profile email',
          mock: true,
        });
      }
      return json(res, 400, { error: 'unsupported_grant_type', mock: true });
    }
    if (req.method === 'GET' && url.pathname === '/oauth/userinfo') {
      const token = (req.headers.authorization || '').replace(/^Bearer\s+/i, '');
      const user = state.accessTokens.get(token);
      if (!user) return json(res, 401, { error: 'invalid_token', mock: true });
      return json(res, 200, { ...user, mock: true });
    }
    if (req.method === 'POST' && url.pathname === '/oauth/revoke') {
      return json(res, 200, { ok: true, mock: true });
    }
    return json(res, 404, { error: 'not_found', mock: true });
  });
  const port = await listen(server);
  return {
    port,
    url: `http://127.0.0.1:${port}`,
    state,
    close: () => close(server),
  };
}

// ---------------------------------------------------------------------------
// WebDAV: PROPFIND, PUT, GET, HEAD, DELETE and MKCOL over one in-memory tree.

export async function startMockWebDAV() {
  const state = {
    requests: [],
    files: new Map([['/dav/readme.txt', Buffer.from('mock webdav readme\n')]]),
    bytes: 19,
  };
  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    logRequest(state, req, url.pathname);
    const path = decodeURIComponent(url.pathname);
    if (req.method === 'OPTIONS') {
      return send(res, 200, '', {
        dav: '1, 2',
        allow: 'OPTIONS, PROPFIND, GET, HEAD, PUT, DELETE, MKCOL',
      });
    }
    if (req.method === 'PROPFIND') {
      const entries = [...state.files.entries()].filter(([file]) =>
        path === '/' || path === '/dav' || file.startsWith(path));
      const responses = entries.map(([file, body]) => `
  <D:response>
    <D:href>${xmlEscape(file)}</D:href>
    <D:propstat>
      <D:prop>
        <D:getcontentlength>${body.length}</D:getcontentlength>
        <D:getcontenttype>text/plain</D:getcontenttype>
        <D:resourcetype/>
      </D:prop>
      <D:status>HTTP/1.1 200 OK</D:status>
    </D:propstat>
  </D:response>`).join('');
      const body = `<?xml version="1.0" encoding="utf-8"?>
<D:multistatus xmlns:D="DAV:">${responses}
</D:multistatus>`;
      return send(res, 207, body, { 'content-type': 'application/xml; charset=utf-8' });
    }
    if (req.method === 'PUT') {
      const body = await readBody(req);
      if (body === null) return send(res, 413, 'mock body too large');
      if (!state.files.has(path) && state.files.size >= MAX_REQUESTS) return send(res, 507, 'mock storage full');
      const previous = state.files.get(path);
      const nextBytes = state.bytes - (previous ? previous.length : 0) + body.length;
      if (nextBytes > MAX_TOTAL_BYTES) return send(res, 507, 'mock storage full');
      state.files.set(path, body);
      state.bytes = nextBytes;
      return send(res, 201, '', { etag: `"${body.length}"` });
    }
    if (req.method === 'MKCOL') {
      return send(res, 201, '');
    }
    if (req.method === 'GET' || req.method === 'HEAD') {
      const body = state.files.get(path);
      if (!body) return send(res, 404, 'not found');
      return send(res, 200, req.method === 'HEAD' ? '' : body, {
        'content-type': 'text/plain; charset=utf-8',
        etag: `"${body.length}"`,
      });
    }
    if (req.method === 'DELETE') {
      const previous = state.files.get(path);
      const existed = state.files.delete(path);
      if (existed) state.bytes -= previous.length;
      return send(res, existed ? 204 : 404, '');
    }
    return send(res, 405, 'method not allowed', { allow: 'OPTIONS, PROPFIND, GET, HEAD, PUT, DELETE, MKCOL' });
  });
  const port = await listen(server);
  return {
    port,
    url: `http://127.0.0.1:${port}`,
    state,
    close: () => close(server),
  };
}

// ---------------------------------------------------------------------------
// S3: path-style ListBucket, PutObject and GetObject.

export async function startMockS3() {
  const state = {
    requests: [],
    objects: new Map(),
    bytes: 0,
  };
  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    logRequest(state, req, url.pathname);
    const parts = decodeURIComponent(url.pathname).replace(/^\/+/, '').split('/');
    const bucket = parts[0];
    const key = parts.slice(1).join('/');
    if (!bucket) return send(res, 404, 'no bucket');
    if (req.method === 'GET' && !key) {
      const prefix = url.searchParams.get('prefix') || '';
      const keys = [...state.objects.keys()]
        .filter((entry) => entry.startsWith(`${bucket}/`))
        .map((entry) => entry.slice(bucket.length + 1))
        .filter((entry) => entry.startsWith(prefix))
        .sort();
      const contents = keys.map((entry) => {
        const body = state.objects.get(`${bucket}/${entry}`);
        return `
  <Contents>
    <Key>${xmlEscape(entry)}</Key>
    <Size>${body.length}</Size>
    <LastModified>2026-01-01T00:00:00.000Z</LastModified>
  </Contents>`;
      }).join('');
      const body = `<?xml version="1.0" encoding="UTF-8"?>
<ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
  <Name>${xmlEscape(bucket)}</Name>
  <Prefix>${xmlEscape(prefix)}</Prefix>
  <KeyCount>${keys.length}</KeyCount>
  <MaxKeys>1000</MaxKeys>
  <IsTruncated>false</IsTruncated>${contents}
</ListBucketResult>`;
      return send(res, 200, body, { 'content-type': 'application/xml; charset=utf-8' });
    }
    if (req.method === 'PUT' && key) {
      const body = await readBody(req);
      if (body === null) return send(res, 413, 'mock body too large');
      const objectKey = `${bucket}/${key}`;
      if (!state.objects.has(objectKey) && state.objects.size >= MAX_REQUESTS) return send(res, 507, 'mock storage full');
      const previous = state.objects.get(objectKey);
      const nextBytes = state.bytes - (previous ? previous.length : 0) + body.length;
      if (nextBytes > MAX_TOTAL_BYTES) return send(res, 507, 'mock storage full');
      state.objects.set(objectKey, body);
      state.bytes = nextBytes;
      return send(res, 200, '', { etag: `"${body.length}"` });
    }
    if ((req.method === 'GET' || req.method === 'HEAD') && key) {
      const body = state.objects.get(`${bucket}/${key}`);
      if (!body) return send(res, 404, 'no such key');
      return send(res, 200, req.method === 'HEAD' ? '' : body, {
        'content-type': 'application/octet-stream',
        etag: `"${body.length}"`,
      });
    }
    if (req.method === 'DELETE' && key) {
      const objectKey = `${bucket}/${key}`;
      const previous = state.objects.get(objectKey);
      state.objects.delete(objectKey);
      if (previous) state.bytes -= previous.length;
      return send(res, 204, '');
    }
    if (req.method === 'PUT' && !key) {
      return send(res, 200, '');
    }
    return send(res, 405, 'method not allowed');
  });
  const port = await listen(server);
  return {
    port,
    url: `http://127.0.0.1:${port}`,
    state,
    close: () => close(server),
  };
}

export async function startMockExternalServices() {
  const [oauth, webdav, s3] = await Promise.all([
    startMockOAuth(),
    startMockWebDAV(),
    startMockS3(),
  ]);
  return {
    oauth,
    webdav,
    s3,
    async close() {
      await Promise.all([oauth.close(), webdav.close(), s3.close()]);
    },
  };
}
