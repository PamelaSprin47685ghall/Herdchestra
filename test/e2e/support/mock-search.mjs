// Mock web-search service for E2E.  It implements the two provider shapes
// the server adapters consume (hc.server.search.providers.brave and
// .searxng) without calling a real search provider.  The fixed result set is
// deliberately small and includes a duplicate URL and one result without a
// publication date so specs can assert dedup and "date unknown" handling.
import http from 'node:http';
import { URL } from 'node:url';

const MOCK_HEADER = 'search';
const MAX_REQUESTS = 200;
const MAX_BODY_BYTES = 1024 * 1024;

export const SEARCH_RESULTS = [
  {
    title: 'Herdchestra mock result one',
    url: 'https://example.test/docs/one',
    snippet: 'First mock search result with a fixed body.',
    publishedDate: '2026-01-02T03:04:05.000Z',
  },
  {
    title: 'Herdchestra mock result two',
    url: 'https://example.test/docs/two',
    snippet: 'Second mock search result.',
    publishedDate: null,
  },
  {
    // Same canonical URL as result one: consumers must deduplicate.
    title: 'Herdchestra mock result one (duplicate)',
    url: 'https://example.test/docs/one',
    snippet: 'Duplicate URL entry.',
    publishedDate: '2026-01-03T00:00:00.000Z',
  },
  {
    title: 'Herdchestra mock result three',
    url: 'https://example.test/docs/three',
    snippet: 'Third mock search result without a date.',
    publishedDate: null,
  },
];

function rememberRequests(state, entry) {
  state.requests.push(entry);
  if (state.requests.length > MAX_REQUESTS) state.requests.shift();
}

function json(res, status, value, extra = {}) {
  const body = JSON.stringify(value);
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(body),
    'cache-control': 'no-store',
    'x-herdchestra-mock': MOCK_HEADER,
    ...extra,
  });
  res.end(body);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let total = 0;
    req.on('data', (chunk) => {
      total += chunk.length;
      if (total > MAX_BODY_BYTES) {
        chunks.length = 0;
        return resolve(null);
      }
      chunks.push(chunk);
    });
    req.on('end', () => resolve(Buffer.concat(chunks, total)));
    req.on('error', reject);
  });
}

export async function startMockSearch(options = {}) {
  const state = {
    requests: [],
    queries: [],
    // brave | searxng — both are served on the same port; the client picks
    // the path shape through its provider adapter.
    mode: options.mode || 'both',
    // Optional per-query result rules: `queries` is a list of
    // `{match, results}` where `match` is a substring of the provider's
    // `q` parameter and `results` is an array of result rows (any subset
    // of SEARCH_RESULTS or caller-supplied rows).  The first matching
    // rule wins; without rules the fixed SEARCH_RESULTS set is returned.
    rules: Array.isArray(options.queries) ? options.queries : null,
  };

  const resultSetFor = (query) => {
    if (state.rules) {
      const rule = state.rules.find((entry) =>
        entry && typeof entry.match === 'string' && query.includes(entry.match));
      if (rule) return Array.isArray(rule.results) ? rule.results : [];
    }
    return SEARCH_RESULTS;
  };

  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    const path = url.pathname;
    if (req.method === 'GET' && path === '/__mock/state') {
      return json(res, 200, {
        mock: true,
        mode: state.mode,
        requests: state.requests,
        queries: state.queries,
      });
    }
    if (req.method === 'POST' && path === '/__mock/reset') {
      await readBody(req);
      state.requests = [];
      state.queries = [];
      return json(res, 200, { ok: true, mock: true });
    }
    if (req.method !== 'GET') {
      return json(res, 405, { error: 'method not allowed', mock: true });
    }

    const isBrave = path === '/res/v1/web/search';
    const isSearxng = path === '/search' || path.endsWith('/search');
    if (!isBrave && !isSearxng) {
      return json(res, 404, { error: 'not_found', mock: true });
    }
    if (isBrave && state.mode === 'searxng') {
      return json(res, 404, { error: 'not_found', mock: true });
    }
    if (isSearxng && state.mode === 'brave') {
      return json(res, 404, { error: 'not_found', mock: true });
    }

    const query = url.searchParams.get('q') || '';
    const entry = {
      at: new Date().toISOString(),
      path,
      query,
      params: Object.fromEntries(url.searchParams.entries()),
      subscriptionToken: req.headers['x-subscription-token'] || null,
    };
    rememberRequests(state, entry);
    state.queries.push(query);
    if (state.queries.length > MAX_REQUESTS) state.queries.shift();

    const results = resultSetFor(query);
    if (isBrave) {
      return json(res, 200, {
        web: {
          results: results.map((result) => ({
            title: result.title,
            url: result.url,
            description: result.snippet,
            page_age: result.publishedDate,
          })),
        },
        mock: true,
      });
    }
    return json(res, 200, {
      results: results.map((result) => ({
        title: result.title,
        url: result.url,
        content: result.snippet,
        publishedDate: result.publishedDate,
      })),
      mock: true,
    });
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
    // Provider-shaped endpoint URLs for config.edn injection.
    braveUrl: `http://127.0.0.1:${port}/res/v1/web/search`,
    searxngUrl: `http://127.0.0.1:${port}/search`,
    async close() {
      await new Promise((resolve) => server.close(resolve));
    },
  };
}
