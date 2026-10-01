import assert from 'node:assert/strict';
import test from 'node:test';
import {
  parseDoneStatus,
  resolveFinalStatus,
  selectSourcesForStatus,
  sourcesDisplayMode,
} from './ragStreamStatus.ts';

test('parseDoneStatus 解析服务端确认的四种最终状态', () => {
  assert.equal(parseDoneStatus('{"status":"COMPLETED"}'), 'COMPLETED');
  assert.equal(parseDoneStatus('{"status":"NO_RESULTS"}'), 'NO_RESULTS');
  assert.equal(parseDoneStatus('{"status":"MODEL_FAILED"}'), 'MODEL_FAILED');
  assert.equal(parseDoneStatus('{"status":"CLIENT_DISCONNECTED"}'), 'CLIENT_DISCONNECTED');
});

test('parseDoneStatus 缺失/非法/未知状态返回 undefined（不得据此判定成功）', () => {
  assert.equal(parseDoneStatus(undefined), undefined);
  assert.equal(parseDoneStatus(''), undefined);
  assert.equal(parseDoneStatus('   '), undefined);
  assert.equal(parseDoneStatus('not json'), undefined);
  assert.equal(parseDoneStatus('{"foo":1}'), undefined);
  assert.equal(parseDoneStatus('{"status":"WEIRD"}'), undefined);
  assert.equal(parseDoneStatus('{"status":123}'), undefined);
});

test('resolveFinalStatus_使用服务端状态_无确认回退失败', () => {
  assert.equal(resolveFinalStatus('COMPLETED'), 'COMPLETED');
  assert.equal(resolveFinalStatus('NO_RESULTS'), 'NO_RESULTS');
  assert.equal(resolveFinalStatus('CLIENT_DISCONNECTED'), 'CLIENT_DISCONNECTED');
  // done 无有效状态 / 持久化失败（服务端不发 done）→ 不能当作 COMPLETED
  assert.equal(resolveFinalStatus(undefined), 'MODEL_FAILED');
});

test('sourcesDisplayMode 使失败/无结果不显示成有依据的正常回答', () => {
  assert.equal(sourcesDisplayMode('COMPLETED'), 'grounded');
  assert.equal(sourcesDisplayMode('NO_RESULTS'), 'none');
  assert.equal(sourcesDisplayMode('MODEL_FAILED'), 'degraded');
  assert.equal(sourcesDisplayMode('CLIENT_DISCONNECTED'), 'degraded');
  // 流式生成中（尚未提交状态）不渲染来源
  assert.equal(sourcesDisplayMode(undefined), 'pending');
  assert.equal(sourcesDisplayMode(null), 'pending');
});

test('selectSourcesForStatus 无结果一律清空来源，其余保留', () => {
  const src = [{ kbId: 1, documentName: 'a.md', contentSnippet: 'x', score: 0.9 }];
  assert.deepEqual(selectSourcesForStatus(src, 'COMPLETED'), src);
  assert.deepEqual(selectSourcesForStatus(src, 'MODEL_FAILED'), src);
  assert.deepEqual(selectSourcesForStatus(src, 'CLIENT_DISCONNECTED'), src);
  assert.deepEqual(selectSourcesForStatus(src, 'NO_RESULTS'), []);
  assert.deepEqual(selectSourcesForStatus(src, undefined), src);
  assert.deepEqual(selectSourcesForStatus(undefined, 'COMPLETED'), []);
});
