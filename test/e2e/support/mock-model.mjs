// Mock OpenAI-compatible model service for E2E.  It never calls a real
// provider and is marked as a mock in every response.
import http from 'node:http';
import { URL } from 'node:url';

export const FAST_REPLY =
  'Mock 模型回复：这是一条可复制的固定回复。E2E-MOCK-REPLY';

const LONG_PARAGRAPHS = [
  '本地模型推理的第一件事，是承认它和云端推理并没有本质区别：权重固定在磁盘上，计算发生在离你最近的处理器里，唯一的差别是网络这一跳被拿掉了。',
  '第二件事是内存。权重要全部或部分装进显存或内存，装不下就会退到更慢的层次，速度下降往往是数量级的，而不是百分比的。',
  '第三件事是上下文长度。它决定模型一次能看见多少内容，也决定 KV 缓存要占多少内存；上下文翻倍，缓存近似翻倍，这就是长对话突然变慢的原因。',
  '第四件事是量化。把权重从十六位压到八位、四位，换来更小的内存和更快的读取，代价是精度，通常表现为长链条推理更容易走偏。',
  '第五件事是采样参数。温度控制随机性，top-p 控制候选范围，重复惩罚控制复读；它们不会让模型变聪明，只会改变它在你面前的表现。',
  '第六件事是停止条件。最大输出长度、停止词以及客户端的取消，任何一项缺失都会让一次本该结束的生成继续消耗算力。',
  '第七件事是并发。单机单模型时，并行请求通常只是排队，表面上的并发换来每个请求都更慢，而不是总吞吐更高。',
  '第八件事是可观测性。记录首字延迟、总耗时、token 用量与结束原因，否则你无法区分“模型慢”和“客户端在等一个永远不会来的块”。',
];

const LONG_TABLE = [
  '',
  '| 观察项 | 本地服务 | 云端服务 |',
  '| --- | --- | --- |',
  '| 网络跳数 | 0 | 1 到多次 |',
  '| 数据离开本机 | 否 | 是 |',
  '| 首字延迟 | 取决于硬件 | 取决于网络与排队 |',
  '',
  '```text',
  'stop = operator || max_tokens || stop_sequence',
  'stream = chunks.map(render)',
  '```',
  '',
];

export const LONG_TEXT = [
  ...LONG_PARAGRAPHS,
  ...LONG_TABLE,
  ...LONG_PARAGRAPHS.map((paragraph) => `补充说明：${paragraph}`),
  ...LONG_PARAGRAPHS,
].join('\n\n');

const MODEL_IDS = ['mock-fast', 'mock-long', 'mock-tool'];
const MAX_BODY_BYTES = 1024 * 1024;

function json(res, status, value, extra = {}) {
  const body = JSON.stringify(value);
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(body),
    'cache-control': 'no-store',
    'x-herdchestra-mock': 'model',
    ...extra,
  });
  res.end(body);
}

function sse(res, value) {
  res.write(`data: ${JSON.stringify(value)}\n\n`);
}

function nowSeconds() {
  return Math.floor(Date.now() / 1000);
}

function completionChunk(id, model, delta, finishReason = null) {
  return {
    id,
    object: 'chat.completion.chunk',
    created: nowSeconds(),
    model,
    choices: [{ index: 0, delta, finish_reason: finishReason }],
  };
}

function completion(id, model, message, finishReason, usage) {
  return {
    id,
    object: 'chat.completion',
    created: nowSeconds(),
    model,
    choices: [{ index: 0, message, finish_reason: finishReason }],
    usage,
  };
}

function toolCall() {
  return {
    id: 'call_mock_tool',
    type: 'function',
    function: { name: 'mock_echo', arguments: '{"ok":true,"source":"mock"}' },
  };
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let body = '';
    let tooLarge = false;
    req.setEncoding('utf8');
    req.on('data', (chunk) => {
      if (tooLarge) return;
      if (body.length + chunk.length > MAX_BODY_BYTES) {
        tooLarge = true;
        body = '';
        return;
      }
      body += chunk;
    });
    req.on('end', () => resolve(tooLarge ? null : body));
    req.on('error', reject);
  });
}

export async function startMockModel(options = {}) {
  const state = {
    requests: [],
    chatCompletions: 0,
    active: 0,
    aborted: 0,
    config: {
      reply: FAST_REPLY,
      chunkSize: 24,
      chunkDelayMs: 90,
      errorStatus: null,
      failAfterChunks: null,
      forceToolCall: false,
      ...options,
    },
  };

  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    if (req.method === 'GET' && (url.pathname === '/v1/models' || url.pathname === '/models')) {
      return json(res, 200, {
        object: 'list',
        data: MODEL_IDS.map((id) => ({ id, object: 'model', owned_by: 'herdchestra-mock' })),
        mock: true,
      });
    }
    if (req.method === 'GET' && url.pathname === '/__mock/state') {
      return json(res, 200, {
        mock: true,
        requests: state.requests.length,
        chatCompletions: state.chatCompletions,
        active: state.active,
        aborted: state.aborted,
        config: state.config,
        last: state.requests[state.requests.length - 1] || null,
      });
    }
    if (req.method === 'POST' && url.pathname === '/__mock/config') {
      const body = await readBody(req);
      if (body === null) return json(res, 413, { error: { message: 'request too large' }, mock: true });
      let patch = {};
      try { patch = JSON.parse(body || '{}'); } catch { /* invalid config is rejected below */ }
      if (!patch || typeof patch !== 'object' || Array.isArray(patch)) {
        return json(res, 400, { error: { message: 'config must be a JSON object' }, mock: true });
      }
      Object.assign(state.config, patch);
      return json(res, 200, { ok: true, config: state.config, mock: true });
    }
    if (req.method === 'POST' && url.pathname === '/__mock/reset') {
      state.requests = [];
      state.chatCompletions = 0;
      state.aborted = 0;
      return json(res, 200, { ok: true, mock: true });
    }
    if (req.method === 'POST' && (url.pathname === '/v1/chat/completions' || url.pathname === '/chat/completions')) {
      const raw = await readBody(req);
      if (raw === null) return json(res, 413, { error: { message: 'request too large' }, mock: true });
      let body = {};
      try { body = JSON.parse(raw || '{}'); } catch { /* handled as an empty request */ }
      const model = String(body.model || 'mock-fast');
      const stream = body.stream === true;
      const id = `chatcmpl-mock-${Date.now()}-${state.requests.length}`;
      const entry = {
        at: new Date().toISOString(),
        path: url.pathname,
        model,
        stream,
        messageCount: Array.isArray(body.messages) ? body.messages.length : 0,
        hasTools: Array.isArray(body.tools) && body.tools.length > 0,
        user: Array.isArray(body.messages)
          ? String(body.messages.filter((message) => message.role === 'user').at(-1)?.content || '').slice(0, 200)
          : '',
      };
      state.chatCompletions += 1;
      state.requests.push(entry);
      if (state.requests.length > 200) state.requests.shift();

      if (state.config.errorStatus) {
        return json(res, Number(state.config.errorStatus), {
          error: { message: 'mock model failure', type: 'mock_error' },
          mock: true,
        });
      }

      const wantsTool = state.config.forceToolCall || model.includes('tool');
      const long = model.includes('long');
      const reply = long ? LONG_TEXT : String(state.config.reply || FAST_REPLY);
      const usage = {
        prompt_tokens: 12,
        completion_tokens: long ? Math.ceil(LONG_TEXT.length / 2) : 8,
        total_tokens: long ? Math.ceil(LONG_TEXT.length / 2) + 12 : 20,
      };

      if (!stream) {
        const message = wantsTool
          ? { role: 'assistant', content: null, tool_calls: [toolCall()] }
          : { role: 'assistant', content: reply };
        return json(res, 200, completion(id, model, message,
                                         wantsTool ? 'tool_calls' : 'stop', usage));
      }

      res.writeHead(200, {
        'content-type': 'text/event-stream; charset=utf-8',
        'cache-control': 'no-cache',
        connection: 'keep-alive',
        'x-herdchestra-mock': 'model',
      });
      res.flushHeaders?.();
      state.active += 1;
      let closed = false;
      let timer = null;
      const stop = () => {
        if (closed) return;
        closed = true;
        state.active = Math.max(0, state.active - 1);
        state.aborted += 1;
        clearTimeout(timer);
      };
      res.on('close', stop);

      if (wantsTool) {
        sse(res, completionChunk(id, model, { role: 'assistant', content: null }));
        sse(res, completionChunk(id, model, {
          tool_calls: [{
            index: 0,
            id: 'call_mock_tool',
            type: 'function',
            function: { name: 'mock_echo', arguments: '{"ok":true,"source":"mock"}' },
          }],
        }));
        sse(res, completionChunk(id, model, {}, 'tool_calls'));
        res.write('data: [DONE]\n\n');
        res.end();
        return;
      }

      sse(res, completionChunk(id, model, { role: 'assistant', content: '' }));
      const size = Math.max(1, Number(state.config.chunkSize) || 24);
      let cursor = 0;
      const tick = () => {
        if (closed) return;
        if (cursor >= reply.length) {
          sse(res, completionChunk(id, model, {}, 'stop'));
          res.write('data: [DONE]\n\n');
          res.end();
          stop();
          return;
        }
        if (state.config.failAfterChunks !== null
            && cursor >= Number(state.config.failAfterChunks)) {
          res.write(`data: ${JSON.stringify({ error: { message: 'mock stream failure' }, mock: true })}\n\n`);
          res.end();
          stop();
          return;
        }
        sse(res, completionChunk(id, model, { content: reply.slice(cursor, cursor + size) }));
        cursor += size;
        timer = setTimeout(tick, Math.max(0, Number(state.config.chunkDelayMs) || 0));
      };
      timer = setTimeout(tick, Math.max(0, Number(state.config.chunkDelayMs) || 0));
      return;
    }
    return json(res, 404, { error: { message: 'mock model route not found' }, mock: true });
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
