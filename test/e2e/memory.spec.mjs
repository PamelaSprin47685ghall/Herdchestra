// Memory bound benchmark for the E2E doubles.  It proves that request
// histories and stored bodies stay bounded instead of growing with the
// number of calls.  No app JVM or browser is started.
import { test } from '@playwright/test';
import { startMockModel } from './support/mock-model.mjs';
import { startMockExternalServices } from './support/mock-services.mjs';
import { assert, evidenceDir, writeJson } from './support/ui.mjs';

test.setTimeout(120000);

test('@memory mock request and body state stay bounded', async () => {
  const dir = evidenceDir('memory');
  const before = process.memoryUsage();
  const started = Date.now();
  const mock = await startMockModel();
  const services = await startMockExternalServices();
  const result = {
    test: 'memory-bounds',
    mode: 'automated-benchmark',
    mock: true,
    startedAt: new Date().toISOString(),
    before: { heapUsed: before.heapUsed, rss: before.rss },
    checks: {},
  };
  try {
    const chatBody = JSON.stringify({
      model: 'mock-fast',
      stream: false,
      messages: [{ role: 'user', content: 'memory probe' }],
    });
    for (let index = 0; index < 300; index += 1) {
      const response = await fetch(`${mock.url}/v1/chat/completions`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: chatBody,
      });
      await response.arrayBuffer();
    }
    const modelState = await (await fetch(`${mock.url}/__mock/state`)).json();
    result.modelRequests = modelState.requests;
    result.modelChatCompletions = modelState.chatCompletions;
    result.checks.modelHistoryBounded = modelState.requests <= 200;
    result.checks.modelCountAccurate = modelState.chatCompletions === 300;

    const webdavBody = 'x'.repeat(1024);
    for (let index = 0; index < 300; index += 1) {
      const response = await fetch(`${services.webdav.url}/dav/memory.txt`, {
        method: 'PUT',
        body: webdavBody,
      });
      await response.arrayBuffer();
    }
    result.checks.webdavHistoryBounded = services.webdav.state.requests.length <= 200;
    result.checks.webdavOneObject = services.webdav.state.files.size === 2;
    result.checks.webdavBytesBounded = services.webdav.state.bytes === 19 + 1024;

    for (let index = 0; index < 300; index += 1) {
      const response = await fetch(`${services.s3.url}/memory-bucket/memory.txt`, {
        method: 'PUT',
        body: webdavBody,
      });
      await response.arrayBuffer();
    }
    result.checks.s3HistoryBounded = services.s3.state.requests.length <= 200;
    result.checks.s3OneObject = services.s3.state.objects.size === 1;
    result.checks.s3BytesBounded = services.s3.state.bytes === 1024;

    if (global.gc) global.gc();
    const after = process.memoryUsage();
    result.after = { heapUsed: after.heapUsed, rss: after.rss };
    result.heapDelta = after.heapUsed - before.heapUsed;
    result.rssDelta = after.rss - before.rss;
    result.durationMs = Date.now() - started;
    result.checks.heapDeltaBounded = result.heapDelta < 128 * 1024 * 1024;
    result.ok = Object.values(result.checks).every(Boolean);
    result.finishedAt = new Date().toISOString();
    result.evidence = writeJson(dir, 'memory-result', result);
    assert(result.ok, `memory checks failed: ${JSON.stringify(result.checks)}`);
  } catch (error) {
    result.ok = false;
    result.error = error.message;
    result.finishedAt = new Date().toISOString();
    result.evidence = writeJson(dir, 'memory-result', result);
    throw error;
  } finally {
    await services.close();
    await mock.close();
  }
});
