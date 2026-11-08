// Mock OAuth, WebDAV and S3 services for E2E.  These are test doubles, not
// emulations of the real providers: they accept every credential and keep
// state in memory.  Every response is marked with x-herdchestra-mock.
//
// The OAuth surface mirrors test/hc/server/model_auth/support.clj
// `start-oauth-server`: unprefixed paths (/authorize, /token,
// /device_authorization, /userinfo, /revoke), PKCE S256 verification on the
// authorization_code grant, and a controllable device-code state machine
// (authorization_pending -> slow_down -> success, or denied).
import crypto from 'node:crypto';
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

function b64url(buffer) {
  return Buffer.from(buffer).toString('base64')
    .replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '');
}

function sha256B64Url(value) {
  return b64url(crypto.createHash('sha256').update(String(value), 'utf8').digest());
}

function jwt(claims) {
  const encode = (value) => b64url(JSON.stringify(value));
  return `${encode({ alg: 'none' })}.${encode(claims)}.`;
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

function newId(prefix) {
  return `${prefix}-${crypto.randomBytes(8).toString('hex')}`;
}

// ---------------------------------------------------------------------------
// OAuth: loopback contract of hc.server.model-auth.support/start-oauth-server.
//
// Paths (no /oauth/ prefix):
//   GET  /.well-known/oauth-authorization-server  discovery document
//   GET  /authorize                                authorization code + PKCE
//   POST /token                                    authorization_code, device_code,
//                                                   refresh_token grants
//   POST /device_authorization                     RFC 8628 device grant start
//   GET  /userinfo                                 Bearer-protected claims
//   POST /revoke                                   token revocation
//   GET  /device                                   user confirmation page
//   POST /device/confirm                           approve or deny a user code
//   POST /__mock/device/polls                      pending polls before success
//   POST /__mock/device/deny                       force access_denied
//   GET  /__mock/state                             request log + counts

export async function startMockOAuth(options = {}) {
  const state = {
    requests: [],
    codes: new Map(),          // code -> {redirectUri, codeChallenge, codeChallengeMethod}
    deviceCodes: new Map(),    // device_code -> device record
    userCodes: new Map(),      // user_code -> device_code
    tokens: new Map(),         // access_token -> {sub, email, name, iss}
    revoked: [],
    devicePollsBeforeSuccess: Number.isInteger(options.devicePollsBeforeSuccess)
      ? options.devicePollsBeforeSuccess : 2,
    idToken: Boolean(options.idToken),
  };
  const issuer = () => `http://127.0.0.1:${server.address().port}`;
  const user = {
    sub: 'mock-user-1',
    email: 'mock@example.test',
    name: 'Mock User',
  };

  function tokenResponse(res, access, refresh) {
    const claims = {
      iss: issuer(),
      aud: 'mock-client',
      sub: user.sub,
      email: user.email,
      name: user.name,
      exp: Math.floor(Date.now() / 1000) + 3600,
    };
    const body = {
      access_token: access,
      refresh_token: refresh,
      token_type: 'Bearer',
      expires_in: 3600,
      scope: 'openid email profile',
      ...(state.idToken ? { id_token: jwt(claims) } : {}),
    };
    json(res, 200, { ...body, mock: true });
  }

  function grantError(res, error, description) {
    json(res, 400, { error, error_description: description, mock: true });
  }

  function verifyPkce(entry, form) {
    if (!entry.codeChallenge) return true;
    if (entry.codeChallengeMethod && entry.codeChallengeMethod !== 'S256') {
      return false;
    }
    const verifier = form.get('code_verifier');
    if (!verifier) return false;
    return crypto.timingSafeEqual(
      Buffer.from(sha256B64Url(verifier)),
      Buffer.from(entry.codeChallenge));
  }

  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    logRequest(state, req, url.pathname);
    const base = issuer();

    if (req.method === 'GET' && url.pathname === '/.well-known/oauth-authorization-server') {
      return json(res, 200, {
        issuer: base,
        authorization_endpoint: `${base}/authorize`,
        token_endpoint: `${base}/token`,
        device_authorization_endpoint: `${base}/device_authorization`,
        userinfo_endpoint: `${base}/userinfo`,
        revocation_endpoint: `${base}/revoke`,
        mock: true,
      });
    }
    if (req.method === 'GET' && url.pathname === '/authorize') {
      const redirectUri = url.searchParams.get('redirect_uri');
      const stateParam = url.searchParams.get('state');
      if (!redirectUri) {
        return grantError(res, 'invalid_request', 'redirect_uri is required');
      }
      const code = newId('code');
      remember(state.codes, code, {
        redirectUri,
        state: stateParam,
        codeChallenge: url.searchParams.get('code_challenge'),
        codeChallengeMethod: url.searchParams.get('code_challenge_method'),
      });
      const target = new URL(redirectUri);
      target.searchParams.set('code', code);
      target.searchParams.set('state', stateParam || '');
      res.writeHead(302, { location: target.toString(), 'x-herdchestra-mock': MOCK_HEADER });
      return res.end();
    }
    if (req.method === 'POST' && url.pathname === '/token') {
      const raw = await readBody(req);
      if (raw === null) return grantError(res, 'request_too_large', 'body too large');
      const form = new URLSearchParams(raw.toString('utf8'));
      const grant = form.get('grant_type');
      if (grant === 'authorization_code') {
        const code = form.get('code');
        const entry = state.codes.get(code);
        if (!entry) {
          return grantError(res, 'invalid_grant', 'unknown code');
        }
        if (form.get('redirect_uri') !== entry.redirectUri) {
          return grantError(res, 'invalid_grant', 'redirect_uri mismatch');
        }
        if (!verifyPkce(entry, form)) {
          return grantError(res, 'invalid_grant', 'PKCE verification failed');
        }
        state.codes.delete(code);
        const access = newId('access-code');
        const refresh = newId('refresh-code');
        remember(state.tokens, access, { ...user, iss: base });
        state.tokens.set(refresh, { kind: 'refresh', ...user, iss: base });
        return tokenResponse(res, access, refresh);
      }
      if (grant === 'urn:ietf:params:oauth:grant-type:device_code') {
        const deviceCode = form.get('device_code');
        const device = state.deviceCodes.get(deviceCode);
        if (!device) {
          return grantError(res, 'invalid_grant', 'unknown device_code');
        }
        if (device.expiresAtMs && Date.now() > device.expiresAtMs) {
          return grantError(res, 'expired_token', 'device code expired');
        }
        device.polls += 1;
        if (device.status === 'denied') {
          return grantError(res, 'access_denied', 'the account owner denied access');
        }
        const pendingPolls = state.devicePollsBeforeSuccess;
        if (device.polls <= pendingPolls) {
          return grantError(res, 'authorization_pending', 'waiting for approval');
        }
        if (!device.slowDownSeen) {
          // Exactly one slow_down response after the pending window, then
          // the next poll succeeds once the device is approved.
          device.slowDownSeen = true;
          return grantError(res, 'slow_down', 'polling too fast');
        }
        if (device.status !== 'approved') {
          return grantError(res, 'authorization_pending', 'waiting for approval');
        }
        device.status = 'consumed';
        const access = newId('access-device');
        const refresh = newId('refresh-device');
        remember(state.tokens, access, { ...user, iss: base });
        state.tokens.set(refresh, { kind: 'refresh', ...user, iss: base });
        return tokenResponse(res, access, refresh);
      }
      if (grant === 'refresh_token') {
        const refresh = form.get('refresh_token');
        const entry = state.tokens.get(refresh);
        if (!entry || entry.kind !== 'refresh') {
          return grantError(res, 'invalid_grant', 'refresh token is not valid');
        }
        const access = newId('access-refresh');
        const nextRefresh = newId('refresh-next');
        remember(state.tokens, access, { sub: user.sub, email: user.email, name: user.name, iss: base });
        state.tokens.set(nextRefresh, { kind: 'refresh', ...user, iss: base });
        state.tokens.delete(refresh);
        return tokenResponse(res, access, nextRefresh);
      }
      return grantError(res, 'unsupported_grant_type', 'grant not supported');
    }
    if (req.method === 'POST' && url.pathname === '/device_authorization') {
      const raw = await readBody(req);
      if (raw === null) return grantError(res, 'request_too_large', 'body too large');
      const form = new URLSearchParams(raw.toString('utf8'));
      const deviceCode = newId('device');
      const userCode = `MOCK-${crypto.randomBytes(2).toString('hex').toUpperCase()}`;
      const device = {
        deviceCode,
        userCode,
        status: 'pending',
        polls: 0,
        clientId: form.get('client_id'),
        scope: form.get('scope'),
        expiresAtMs: Date.now() + 600_000,
        createdAt: new Date().toISOString(),
      };
      remember(state.deviceCodes, deviceCode, device);
      remember(state.userCodes, userCode, deviceCode);
      return json(res, 200, {
        device_code: deviceCode,
        user_code: userCode,
        verification_uri: `${base}/device`,
        verification_uri_complete: `${base}/device?code=${encodeURIComponent(userCode)}`,
        expires_in: 600,
        interval: 1,
        mock: true,
      });
    }
    if (req.method === 'GET' && url.pathname === '/userinfo') {
      const token = (req.headers.authorization || '').replace(/^Bearer\s+/i, '');
      const record = state.tokens.get(token);
      if (!record || record.kind === 'refresh') {
        return json(res, 401, { error: 'invalid_token', mock: true });
      }
      return json(res, 200, { ...record, mock: true });
    }
    if (req.method === 'POST' && url.pathname === '/revoke') {
      const raw = await readBody(req);
      if (raw === null) return grantError(res, 'request_too_large', 'body too large');
      const form = new URLSearchParams(raw.toString('utf8'));
      const token = form.get('token');
      if (token) state.tokens.delete(token);
      state.revoked.push({ token, at: new Date().toISOString() });
      return json(res, 200, { ok: true, mock: true });
    }
    if (req.method === 'GET' && url.pathname === '/device') {
      const code = url.searchParams.get('code') || '';
      const deviceCode = state.userCodes.get(code);
      const device = deviceCode ? state.deviceCodes.get(deviceCode) : null;
      const status = device ? device.status : 'unknown';
      const body = `<!doctype html><html><head><meta charset="utf-8">
<title>Mock device login</title></head><body>
<h1>Mock device login</h1>
<p>User code: <code>${code || '(none)'}</code></p>
<p>Status: ${status}</p>
<form method="post" action="/device/confirm">
<input type="hidden" name="user_code" value="${code}">
<button type="submit" name="decision" value="approve">Approve</button>
<button type="submit" name="decision" value="deny">Deny</button>
</form>
</body></html>`;
      return send(res, 200, body, { 'content-type': 'text/html; charset=utf-8' });
    }
    if (req.method === 'POST' && url.pathname === '/device/confirm') {
      const raw = await readBody(req);
      if (raw === null) return grantError(res, 'request_too_large', 'body too large');
      const form = new URLSearchParams(raw.toString('utf8'));
      const userCode = form.get('user_code');
      const decision = form.get('decision');
      const deviceCode = state.userCodes.get(userCode);
      const device = deviceCode ? state.deviceCodes.get(deviceCode) : null;
      if (device && decision === 'approve') device.status = 'approved';
      if (device && decision === 'deny') device.status = 'denied';
      res.writeHead(200, {
        'content-type': 'text/html; charset=utf-8',
        'x-herdchestra-mock': MOCK_HEADER,
      });
      return res.end(`<!doctype html><title>Mock device login</title>
<p>Decision recorded: ${decision || 'none'} for ${userCode || '(none)'}</p>`);
    }
    if (req.method === 'POST' && url.pathname === '/__mock/device/polls') {
      const raw = await readBody(req);
      let patch = {};
      try { patch = JSON.parse(raw?.toString('utf8') || '{}'); } catch { patch = {}; }
      if (Number.isInteger(patch.beforeSuccess)) {
        state.devicePollsBeforeSuccess = patch.beforeSuccess;
      }
      return json(res, 200, { ok: true, devicePollsBeforeSuccess: state.devicePollsBeforeSuccess, mock: true });
    }
    if (req.method === 'POST' && url.pathname === '/__mock/device/deny') {
      const raw = await readBody(req);
      let patch = {};
      try { patch = JSON.parse(raw?.toString('utf8') || '{}'); } catch { patch = {}; }
      const deviceCode = patch.deviceCode || patch.device_code;
      const device = state.deviceCodes.get(deviceCode);
      if (device) device.status = 'denied';
      return json(res, 200, { ok: Boolean(device), mock: true });
    }
    if (req.method === 'POST' && url.pathname === '/__mock/reset') {
      state.codes.clear();
      state.deviceCodes.clear();
      state.userCodes.clear();
      state.tokens.clear();
      state.revoked = [];
      state.requests = [];
      return json(res, 200, { ok: true, mock: true });
    }
    if (req.method === 'GET' && url.pathname === '/__mock/state') {
      return json(res, 200, {
        mock: true,
        requests: state.requests,
        codes: state.codes.size,
        deviceCodes: state.deviceCodes.size,
        tokens: state.tokens.size,
        revoked: state.revoked.length,
        devicePollsBeforeSuccess: state.devicePollsBeforeSuccess,
      });
    }
    return json(res, 404, { error: 'not_found', mock: true });
  });
  const port = await listen(server);
  return {
    port,
    url: `http://127.0.0.1:${port}`,
    state,
    issuer,
    user,
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
      // Match the collection itself or descendants only; a bare prefix would
      // also match sibling names such as /dav2.
      const entries = [...state.files.entries()].filter(([file]) =>
        path === '/' || path === '/dav' || file === path
        || file.startsWith(`${path.replace(/\/+$/, '')}/`));
      const responses = entries.map(([file, body]) => `
  <D:response>
    <D:href>${xmlEscape(file)}</D:href>
    <D:propstat>
      <D:prop>
        <D:getcontentlength>${body.length}</D:getcontentlength>
        <D:getcontenttype>text/plain</D:getcontenttype>
        <D:getlastmodified>Thu, 01 Jan 2026 00:00:00 GMT</D:getlastmodified>
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

// ---------------------------------------------------------------------------
// Speech: OpenAI-compatible STT (multipart), TTS (JSON -> WAV) and
// translation (chat completions JSON).  One server serves all three service
// areas; `mode` controls transcription outcomes so a spec can exercise the
// "cloud failure keeps the recording, retry with another service" path.

const MAX_SPEECH_BODY_BYTES = 32 * 1024 * 1024;

// A minimal valid 8-bit mono PCM WAV (matches test/hc/server/speech
// support.clj `wav-bytes`): enough for the server to store and the browser
// `Audio` element to decode in headless Chrome.
export function wavBytes(sampleRate = 8000, samples = 16000) {
  const dataLength = samples;
  const buffer = Buffer.alloc(44 + dataLength);
  buffer.write('RIFF', 0, 'ascii');
  buffer.writeUInt32LE(36 + dataLength, 4);
  buffer.write('WAVE', 8, 'ascii');
  buffer.write('fmt ', 12, 'ascii');
  buffer.writeUInt32LE(16, 16);
  buffer.writeUInt16LE(1, 20);   // PCM
  buffer.writeUInt16LE(1, 22);   // mono
  buffer.writeUInt32LE(sampleRate, 24);
  buffer.writeUInt32LE(sampleRate, 28); // byte rate, 8-bit mono
  buffer.writeUInt16LE(1, 32);   // block align
  buffer.writeUInt16LE(8, 34);   // bits per sample
  buffer.write('data', 36, 'ascii');
  buffer.writeUInt32LE(dataLength, 40);
  buffer.fill(0x80, 44);         // silence
  return buffer;
}

function parseMultipart(contentType, body) {
  // Return [{name, filename, contentType, value(Buffer)}] for assertions.
  const match = /boundary=([^;]+)/i.exec(String(contentType || ''));
  if (!match) return [];
  const boundary = match[1].trim().replace(/^"|"$/g, '');
  const marker = Buffer.from(`--${boundary}`);
  const parts = [];
  let cursor = 0;
  while (true) {
    const start = body.indexOf(marker, cursor);
    if (start < 0) break;
    let pos = start + marker.length;
    if (body.subarray(pos, pos + 2).toString('latin1') === '--') break;
    const headerEnd = body.indexOf('\r\n\r\n', pos);
    if (headerEnd < 0) break;
    const headers = body.subarray(pos, headerEnd).toString('latin1');
    const nameMatch = /name="([^"]*)"/.exec(headers);
    const fileMatch = /filename="([^"]*)"/.exec(headers);
    const typeMatch = /content-type:\s*([^\r\n]+)/i.exec(headers);
    const dataStart = headerEnd + 4;
    const next = body.indexOf(Buffer.from(`\r\n--${boundary}`), dataStart);
    const end = next < 0 ? body.length : next;
    parts.push({
      name: nameMatch ? nameMatch[1] : null,
      filename: fileMatch ? fileMatch[1] : null,
      contentType: typeMatch ? typeMatch[1].trim() : null,
      value: body.subarray(dataStart, end),
    });
    cursor = next < 0 ? body.length : next;
  }
  return parts;
}

export async function startMockSpeech(options = {}) {
  const state = {
    requests: [],          // {at, path, service, mode}
    stt: [],               // {at, fields, bytes}
    tts: [],               // {at, body}
    translate: [],         // {at, body}
    audio: wavBytes(),
    transcript: options.transcript || '这是模拟语音转写结果。',
    translatedText: options.translatedText || '这是模拟翻译结果。',
    sttStatus: options.sttStatus || 200,   // non-200 simulates provider failure
    ttsStatus: options.ttsStatus || 200,
    translateStatus: options.translateStatus || 200,
    delayMs: Number.isInteger(options.delayMs) ? options.delayMs : 0,
  };
  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    const entry = { at: new Date().toISOString(), method: req.method, path: url.pathname };
    state.requests.push(entry);
    if (state.requests.length > 300) state.requests.shift();
    const send = (status, body, headers = {}) => {
      const buffer = Buffer.isBuffer(body) ? body : Buffer.from(String(body));
      res.writeHead(status, {
        'content-length': buffer.length,
        'cache-control': 'no-store',
        'x-herdchestra-mock': 'speech',
        ...headers,
      });
      res.end(buffer);
    };
    const json = (status, value, headers = {}) => send(status, JSON.stringify(value), {
      'content-type': 'application/json; charset=utf-8', ...headers });
    const readBody = () => new Promise((resolve, reject) => {
      const chunks = [];
      let total = 0;
      let tooLarge = false;
      req.on('data', (chunk) => {
        if (tooLarge) return;
        total += chunk.length;
        if (total > MAX_SPEECH_BODY_BYTES) { tooLarge = true; chunks.length = 0; return; }
        chunks.push(chunk);
      });
      req.on('end', () => resolve(tooLarge ? null : Buffer.concat(chunks, total)));
      req.on('error', reject);
    });

    if (req.method === 'GET' && url.pathname === '/v1/models') {
      return json(200, {
        object: 'list',
        data: [
          { id: 'mock-stt', object: 'model', owned_by: 'herdchestra-mock' },
          { id: 'mock-tts', object: 'model', owned_by: 'herdchestra-mock' },
          { id: 'mock-translate', object: 'model', owned_by: 'herdchestra-mock' },
        ],
        mock: true,
      });
    }
    if (req.method === 'GET' && url.pathname === '/__mock/state') {
      return json(200, { mock: true, ...state });
    }
    if (req.method === 'POST' && url.pathname === '/__mock/config') {
      const body = await readBody();
      let patch = {};
      try { patch = JSON.parse((body || Buffer.alloc(0)).toString('utf8') || '{}'); } catch { patch = {}; }
      for (const key of ['sttStatus', 'ttsStatus', 'translateStatus',
                         'transcript', 'translatedText', 'delayMs']) {
        if (key in patch) state[key] = patch[key];
      }
      if (patch.audioBase64) state.audio = Buffer.from(patch.audioBase64, 'base64');
      return json(200, { ok: true, mock: true });
    }
    if (req.method === 'POST' && url.pathname === '/__mock/reset') {
      state.requests = [];
      state.stt = [];
      state.tts = [];
      state.translate = [];
      return json(200, { ok: true, mock: true });
    }
    if (req.method !== 'POST') return json(404, { error: 'not found' });

    if (state.delayMs > 0) {
      await new Promise((resolve) => setTimeout(resolve, state.delayMs));
    }

    if (url.pathname === '/v1/audio/transcriptions') {
      const body = await readBody();
      if (body === null) return json(413, { error: { message: 'request too large' } });
      const parts = parseMultipart(req.headers['content-type'], body);
      const fields = {};
      let fileBytes = 0;
      for (const part of parts) {
        if (part.name === 'file') fileBytes = part.value.length;
        else if (part.name) fields[part.name] = part.value.toString('utf8');
      }
      state.stt.push({
        at: new Date().toISOString(),
        model: fields.model,
        language: fields.language || null,
        responseFormat: fields.response_format || null,
        bytes: fileBytes,
        authorization: req.headers.authorization || null,
      });
      if (state.sttStatus !== 200) {
        return json(state.sttStatus,
          { error: { message: `mock stt failure ${state.sttStatus}` } });
      }
      return json(200, { text: state.transcript, language: 'zh' });
    }

    if (url.pathname === '/v1/audio/speech') {
      const body = await readBody();
      if (body === null) return json(413, { error: { message: 'request too large' } });
      let payload = {};
      try { payload = JSON.parse(body.toString('utf8') || '{}'); } catch { payload = {}; }
      state.tts.push({
        at: new Date().toISOString(),
        model: payload.model,
        input: typeof payload.input === 'string' ? payload.input.slice(0, 400) : null,
        voice: payload.voice || null,
        format: payload.response_format || null,
        speed: payload.speed ?? null,
      });
      if (state.ttsStatus !== 200) {
        return json(state.ttsStatus,
          { error: { message: `mock tts failure ${state.ttsStatus}` } });
      }
      return send(200, state.audio, { 'content-type': 'audio/wav' });
    }

    if (url.pathname === '/v1/chat/completions') {
      const body = await readBody();
      if (body === null) return json(413, { error: { message: 'request too large' } });
      let payload = {};
      try { payload = JSON.parse(body.toString('utf8') || '{}'); } catch { payload = {}; }
      const lastUser = [...(payload.messages || [])].reverse()
        .find((m) => m && m.role === 'user');
      state.translate.push({
        at: new Date().toISOString(),
        model: payload.model,
        system: (payload.messages || [])
          .find((m) => m && m.role === 'system')?.content || null,
        user: typeof lastUser?.content === 'string'
          ? lastUser.content.slice(0, 400) : null,
        stream: payload.stream === true,
      });
      if (state.translateStatus !== 200) {
        return json(state.translateStatus,
          { error: { message: `mock translate failure ${state.translateStatus}` } });
      }
      return json(200, {
        id: `chatcmpl-mock-speech-${state.translate.length}`,
        object: 'chat.completion',
        created: Math.floor(Date.now() / 1000),
        model: payload.model || 'mock-translate',
        choices: [{
          index: 0,
          message: { role: 'assistant', content: state.translatedText },
          finish_reason: 'stop',
        }],
        usage: { prompt_tokens: 8, completion_tokens: 6, total_tokens: 14 },
      });
    }
    return json(404, { error: { message: 'mock speech route not found' }, mock: true });
  });
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', resolve);
  });
  const { port } = server.address();
  return {
    port,
    url: `http://127.0.0.1:${port}`,
    state,
    async close() {
      await new Promise((resolve) => server.close(resolve));
    },
  };
}
