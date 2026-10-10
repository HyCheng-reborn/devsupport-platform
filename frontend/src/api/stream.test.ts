// 通过真实的 streamSse 解析链路（TextDecoder + flushBuffer + processEventBlock）验证 SSE 分块处理，
// 而非只测 ragStreamStatus 纯函数。覆盖 LF、CRLF、CR/LF 跨网络块拆分、sources/done 分块、
// UTF-8 多字节跨块，以及"无 done 回退失败"。断言控制事件不会混入正文、最终回调仅触发一次。
import assert from 'node:assert/strict';
import test from 'node:test';
import { streamSse } from './stream.ts';
import { ragChatApi, type MessageStatus } from './ragChat.ts';

const encoder = new TextEncoder();

function makeResponse(chunks: (string | Uint8Array)[]): Response {
  let index = 0;
  const reader = {
    read(): Promise<{ done: boolean; value?: Uint8Array }> {
      if (index < chunks.length) {
        const c = chunks[index++];
        return Promise.resolve({ done: false, value: typeof c === 'string' ? encoder.encode(c) : c });
      }
      return Promise.resolve({ done: true });
    },
  };
  return {
    ok: true,
    status: 200,
    headers: { get: () => 'text/event-stream' },
    body: { getReader: () => reader },
  } as unknown as Response;
}

function withFetch(chunks: (string | Uint8Array)[]): () => void {
  const original = globalThis.fetch;
  globalThis.fetch = (async () => makeResponse(chunks)) as unknown as typeof fetch;
  return () => {
    globalThis.fetch = original;
  };
}

// 与 ragChat 生产配置一致的事件模式
const EVENT = {
  parseMode: 'event' as const,
  trimDataPrefixSpace: false,
  unescapeEscapedNewlines: true,
  dataJoiner: '',
};

interface Recorder {
  messages: string[];
  sources: string[];
  done: (string | undefined)[];
  completes: number;
  errors: Error[];
}

function runSse(chunks: (string | Uint8Array)[], options: Record<string, unknown>): Promise<Recorder> {
  const rec: Recorder = { messages: [], sources: [], done: [], completes: 0, errors: [] };
  const restore = withFetch(chunks);
  return streamSse({
    url: '/api/rag-chat/sessions/1/messages/stream',
    init: {},
    onMessage: (c) => rec.messages.push(c),
    onSources: (d) => rec.sources.push(d),
    onDone: (s) => rec.done.push(s),
    onComplete: () => {
      rec.completes += 1;
    },
    onError: (e) => rec.errors.push(e),
    ...options,
  }).then(
    () => {
      restore();
      return rec;
    },
    (e) => {
      restore();
      rec.errors.push(e as Error);
      return rec;
    },
  );
}

function assertNoControlLeak(messages: string[]) {
  const body = messages.join('');
  assert.ok(!body.includes('"status"'), `正文不应含状态 JSON，实际: ${body}`);
  assert.ok(!body.includes('COMPLETED'), `正文不应含 done 状态，实际: ${body}`);
  assert.ok(!body.includes('documentName'), `正文不应混入 sources，实际: ${body}`);
}

test('跨网络块把 CRLF 拆成 "…\\r" + "\\n…" 时，done 事件不丢失、状态 JSON 不混入正文', async () => {
  const rec = await runSse(['event:done\r', '\ndata:{"status":"COMPLETED"}\r\n\r\n'], EVENT);

  assert.equal(rec.done.length, 1, 'done 应恰好触发一次');
  assert.equal(rec.done[0], '{"status":"COMPLETED"}');
  assert.equal(rec.errors.length, 0);
  assert.equal(rec.messages.length, 0, 'done 事件不应产生正文');
  assertNoControlLeak(rec.messages);
});

test('LF 模式：data/sources/done 三种事件按契约分发', async () => {
  const chunks = [
    'event:data\ndata:第一段\n\n',
    'event:sources\ndata:[{"kbId":1,"documentName":"a.md"}]\n\n',
    'event:done\ndata:{"status":"COMPLETED"}\n\n',
  ];
  const rec = await runSse(chunks, EVENT);

  assert.deepEqual(rec.messages, ['第一段']);
  assert.equal(rec.sources.length, 1);
  assert.equal(rec.sources[0], '[{"kbId":1,"documentName":"a.md"}]');
  assert.deepEqual(rec.done, ['{"status":"COMPLETED"}']);
  assert.equal(rec.completes, 1);
  assertNoControlLeak(rec.messages);
});

test('CRLF 模式：全部使用 \\r\\n 分隔时结果与 LF 一致', async () => {
  const chunks = [
    'event:data\r\ndata:第一段\r\n\r\n',
    'event:sources\r\ndata:[{"kbId":2}]\r\n\r\n',
    'event:done\r\ndata:{"status":"NO_RESULTS"}\r\n\r\n',
  ];
  const rec = await runSse(chunks, EVENT);

  assert.deepEqual(rec.messages, ['第一段']);
  assert.deepEqual(rec.sources, ['[{"kbId":2}]']);
  assert.deepEqual(rec.done, ['{"status":"NO_RESULTS"}']);
  assertNoControlLeak(rec.messages);
});

test('event 头、data 行与空行分隔符的 \\r\\n 全部跨块拆分仍能正确配对', async () => {
  // 每个 \r\n 都被拆成前一块结尾的 \r + 后一块开头的 \n
  const chunks = ['event:sources\r\n', 'data:[{"kbId":7}]\r', '\n\r', '\n'];
  const rec = await runSse(chunks, EVENT);

  assert.deepEqual(rec.sources, ['[{"kbId":7}]']);
  assert.equal(rec.done.length, 0);
  assert.equal(rec.messages.length, 0);
});

test('UTF-8 多字节字符跨网络块拆分时内容不被破坏', async () => {
  const payload = encoder.encode('event:data\ndata:测试端口8080\n\n');
  // 在"端"这个三字节字符中间切开：ASCII 前缀字节数 + "测试"(6字节) + 1（进入"端"的第 1 个字节）
  const asciiPrefix = encoder.encode('event:data\ndata:').length;
  const splitAt = asciiPrefix + encoder.encode('测试').length + 1;
  assert.ok(splitAt < payload.length);
  const rec = await runSse([payload.slice(0, splitAt), payload.slice(splitAt)], EVENT);

  assert.deepEqual(rec.messages, ['测试端口8080']);
});

test('无 done 事件（异常终止/持久化失败）时只完成不伪造成功', async () => {
  const rec = await runSse(['event:data\ndata:部分回答\n\n'], EVENT);

  assert.deepEqual(rec.messages, ['部分回答']);
  assert.equal(rec.done.length, 0, '没有 done 事件不得触发 onDone');
  assert.equal(rec.completes, 1, '流正常结束仍触发一次 onComplete 供上层回退');
});

test('line 模式保持兼容：纯 data 行按行透传', async () => {
  const rec = await runSse(['data:行一\n', 'data:行二\n'], {
    parseMode: 'line',
    trimDataPrefixSpace: true,
    unescapeEscapedNewlines: true,
  });

  assert.deepEqual(rec.messages, ['行一', '行二']);
  assert.equal(rec.completes, 1);
  assert.equal(rec.done.length, 0);
});

// ========== ragChat 端到端（真实链路 + finalized 单次护栏） ==========

async function runRagChat(
  chunks: (string | Uint8Array)[],
): Promise<{ status: (MessageStatus | undefined)[]; sources: string[]; errors: Error[] }> {
  const status: (MessageStatus | undefined)[] = [];
  const sources: string[] = [];
  const errors: Error[] = [];
  const restore = withFetch(chunks);
  try {
    await ragChatApi.sendMessageStream(
      1,
      '端口是多少',
      () => {},
      (s) => sources.push(s),
      (st) => status.push(st),
      (e) => errors.push(e),
    );
  } finally {
    restore();
  }
  return { status, sources, errors };
}

test('ragChat：收到 done 后 onComplete 仅一次且携带 COMPLETED', async () => {
  const chunks = [
    'event:data\ndata:端口是8080\n\n',
    'event:sources\ndata:[{"kbId":1}]\n\n',
    'event:done\ndata:{"status":"COMPLETED"}\n\n',
  ];
  const { status, sources, errors } = await runRagChat(chunks);

  assert.deepEqual(status, ['COMPLETED'], '最终回调应恰好一次且为 COMPLETED');
  assert.deepEqual(sources, ['[{"kbId":1}]']);
  assert.equal(errors.length, 0);
});

test('ragChat：无 done 时回退 MODEL_FAILED 且只触发一次', async () => {
  const { status, errors } = await runRagChat(['event:data\ndata:部分回答\n\n']);

  assert.deepEqual(status, ['MODEL_FAILED']);
  assert.equal(errors.length, 0);
});

test('ragChat：跨块 CRLF 拆分的 done 不再误回退 MODEL_FAILED', async () => {
  const { status } = await runRagChat(['event:done\r', '\ndata:{"status":"COMPLETED"}\r\n\r\n']);

  assert.deepEqual(status, ['COMPLETED'], 'done 必须被识别，不能因分块丢失而回退失败');
});

// ========== tool_result 事件解析 ==========

interface ToolRecorder extends Recorder {
  toolResults: string[];
}

function runSseWithTool(chunks: (string | Uint8Array)[], options: Record<string, unknown>): Promise<ToolRecorder> {
  const rec: ToolRecorder = {
    messages: [], sources: [], done: [], completes: 0, errors: [], toolResults: [],
  };
  const restore = withFetch(chunks);
  return streamSse({
    url: '/api/rag-chat/sessions/1/messages/stream',
    init: {},
    onMessage: (c) => rec.messages.push(c),
    onSources: (d) => rec.sources.push(d),
    onDone: (s) => rec.done.push(s),
    onToolResult: (d) => rec.toolResults.push(d),
    onComplete: () => { rec.completes += 1; },
    onError: (e) => rec.errors.push(e),
    ...options,
  }).then(
    () => { restore(); return rec; },
    (e) => { restore(); rec.errors.push(e as Error); return rec; },
  );
}

test('tool_result 事件解析：SSE 包含 event: tool_result 时 onToolResult 回调被调用', async () => {
  const toolPayload = JSON.stringify({
    toolName: 'checkDependencyHealth', status: 'SUCCESS',
    result: { postgresql: { status: 'OK' } }, durationMs: 42, demo: true,
  });
  const chunks = [
    'event:data\ndata:回答内容\n\n',
    `event:tool_result\ndata:${toolPayload}\n\n`,
    'event:sources\ndata:[]\n\n',
    'event:done\ndata:{"status":"COMPLETED"}\n\n',
  ];
  const rec = await runSseWithTool(chunks, EVENT);

  assert.equal(rec.toolResults.length, 1, 'onToolResult 应恰好触发一次');
  const parsed = JSON.parse(rec.toolResults[0]);
  assert.equal(parsed.toolName, 'checkDependencyHealth');
  assert.equal(parsed.demo, true);
  assert.equal(rec.errors.length, 0);
  assertNoControlLeak(rec.messages);
});

test('混合事件序列：data → tool_result → sources → done 各回调按序触发', async () => {
  const toolPayload = JSON.stringify({
    toolName: 'checkDependencyHealth', status: 'SUCCESS',
    result: {}, durationMs: 10, demo: false,
  });
  const chunks = [
    'event:data\ndata:第一部分\n\n',
    'event:data\ndata:第二部分\n\n',
    `event:tool_result\ndata:${toolPayload}\n\n`,
    'event:sources\ndata:[{"kbId":1}]\n\n',
    'event:done\ndata:{"status":"COMPLETED"}\n\n',
  ];
  const rec = await runSseWithTool(chunks, EVENT);

  // data 事件
  assert.deepEqual(rec.messages, ['第一部分', '第二部分']);
  // tool_result 事件
  assert.equal(rec.toolResults.length, 1);
  // sources 事件
  assert.equal(rec.sources.length, 1);
  assert.equal(rec.sources[0], '[{"kbId":1}]');
  // done 事件
  assert.deepEqual(rec.done, ['{"status":"COMPLETED"}']);
  assert.equal(rec.completes, 1);
  assert.equal(rec.errors.length, 0);
  assertNoControlLeak(rec.messages);
});

test('无 tool_result 的向后兼容：data → sources → done 时 onToolResult 未被调用', async () => {
  const chunks = [
    'event:data\ndata:普通回答\n\n',
    'event:sources\ndata:[]\n\n',
    'event:done\ndata:{"status":"COMPLETED"}\n\n',
  ];
  const rec = await runSseWithTool(chunks, EVENT);

  assert.deepEqual(rec.messages, ['普通回答']);
  assert.equal(rec.toolResults.length, 0, '无 tool_result 事件时 onToolResult 不应被调用');
  assert.equal(rec.sources.length, 1);
  assert.deepEqual(rec.done, ['{"status":"COMPLETED"}']);
  assert.equal(rec.errors.length, 0);
});
