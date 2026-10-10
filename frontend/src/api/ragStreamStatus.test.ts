import assert from 'node:assert/strict';
import test from 'node:test';
import {
  parseDoneStatus,
  resolveFinalStatus,
  selectSourcesForStatus,
  sourcesDisplayMode,
  sourcesHeaderLabel,
  toolStatusLabel,
  toolStatusColor,
  componentStatusColor,
} from './ragStreamStatus.ts';

test('parseDoneStatus 解析服务端确认的五种最终状态（含 INSUFFICIENT_INFO）', () => {
  assert.equal(parseDoneStatus('{"status":"COMPLETED"}'), 'COMPLETED');
  assert.equal(parseDoneStatus('{"status":"NO_RESULTS"}'), 'NO_RESULTS');
  assert.equal(parseDoneStatus('{"status":"MODEL_FAILED"}'), 'MODEL_FAILED');
  assert.equal(parseDoneStatus('{"status":"CLIENT_DISCONNECTED"}'), 'CLIENT_DISCONNECTED');
  assert.equal(parseDoneStatus('{"status":"INSUFFICIENT_INFO"}'), 'INSUFFICIENT_INFO');
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

test('sourcesDisplayMode: INSUFFICIENT_INFO 返回 insufficient', () => {
  assert.equal(sourcesDisplayMode('INSUFFICIENT_INFO'), 'insufficient');
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

test('sourcesHeaderLabel: Demo 模板回答显示“检索结果快照”，避免误导为由来源生成', () => {
  const demoContent = '[Demo 模板响应 — 非真实 AI 生成]\n端口占用排查步骤……';
  assert.equal(
    sourcesHeaderLabel(demoContent, { grounded: true, degradedLabel: '降级：' }),
    '检索结果快照，未用于生成此 Demo 模板回答：',
  );
  // Demo 标识处于降级状态时仍优先显示 Demo 快照文案
  assert.equal(
    sourcesHeaderLabel(demoContent, { grounded: false, degradedLabel: '降级：' }),
    '检索结果快照，未用于生成此 Demo 模板回答：',
  );
});

test('sourcesHeaderLabel: 真实回答保持原有 grounded / degraded 文案', () => {
  const realContent = '根据知识库，连接池耗尽时需检查最大连接数。';
  assert.equal(
    sourcesHeaderLabel(realContent, { grounded: true, degradedLabel: '检索到的参考文档：' }),
    '引用来源：',
  );
  assert.equal(
    sourcesHeaderLabel(realContent, { grounded: false, degradedLabel: '检索到的参考文档：' }),
    '检索到的参考文档：',
  );
  // 空内容 / undefined 不会误判为 Demo
  assert.equal(
    sourcesHeaderLabel('', { grounded: true, degradedLabel: 'D：' }),
    '引用来源：',
  );
  assert.equal(
    sourcesHeaderLabel(undefined, { grounded: true, degradedLabel: 'D：' }),
    '引用来源：',
  );
  assert.equal(
    sourcesHeaderLabel(null, { grounded: false, degradedLabel: 'D：' }),
    'D：',
  );
});

// ========== 工具调用结果展示辅助 ==========

test('toolStatusLabel 映射四种工具调用状态', () => {
  assert.equal(toolStatusLabel('SUCCESS'), '成功');
  assert.equal(toolStatusLabel('TIMEOUT'), '超时');
  assert.equal(toolStatusLabel('ERROR'), '失败');
  assert.equal(toolStatusLabel('LIMIT_EXCEEDED'), '超出限制');
});

test('toolStatusColor 为不同状态返回不同颜色类名', () => {
  const success = toolStatusColor('SUCCESS');
  const timeout = toolStatusColor('TIMEOUT');
  const error = toolStatusColor('ERROR');
  const limit = toolStatusColor('LIMIT_EXCEEDED');

  // 成功用 emerald，失败/超时用 red，超限用 orange
  assert.ok(success.includes('emerald'));
  assert.ok(timeout.includes('red'));
  assert.ok(error.includes('red'));
  assert.ok(limit.includes('orange'));
  // 每个状态的颜色类名互不相同
  const colors = new Set([success, timeout, error, limit]);
  assert.equal(colors.size, 3, 'TIMEOUT 和 ERROR 共享颜色，其余各自独立');
});

test('componentStatusColor 根据组件状态值返回对应颜色', () => {
  assert.ok(componentStatusColor('OK').includes('emerald'));
  assert.ok(componentStatusColor('UP').includes('emerald'));
  assert.ok(componentStatusColor('UNREACHABLE').includes('red'));
  assert.ok(componentStatusColor('DEGRADED').includes('orange'));
  assert.ok(componentStatusColor('unknown').includes('slate'));
});
