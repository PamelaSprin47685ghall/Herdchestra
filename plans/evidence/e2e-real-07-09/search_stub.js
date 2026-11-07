const http = require('http');
const url = require('url');
const server = http.createServer((req, res) => {
  const u = url.parse(req.url, true);
  if (u.pathname !== '/search') { res.writeHead(404); res.end('no'); return; }
  const q = String(u.query.q || '');
  const base = 'http://127.0.0.1:18096';
  const results = [
    {url: base + '/doc1.html', title: 'Doc One', content: 'Alpha fact from Doc One. ALPHA-ONE.'},
    {url: base + '/doc1.html', title: 'Doc One duplicate', content: 'Same URL exact duplicate.'},
    {url: base + '/missing.html', title: 'Missing Source', content: 'This source will 404; title and URL must remain.'},
    {url: base + '/doc2.html', title: 'Doc Two', content: 'Beta fact from Doc Two. BETA-TWO.', publishedDate: '2025-01-02T00:00:00Z'}
  ];
  res.writeHead(200, {'content-type':'application/json'});
  res.end(JSON.stringify({query: q, results}));
});
server.listen(18095, '127.0.0.1', () => console.log('search stub 18095'));
