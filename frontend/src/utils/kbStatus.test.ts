import { test } from 'node:test';
import assert from 'node:assert/strict';
import { deriveStatus, canRetire, DOC_STATUS_LABEL } from './kbStatus.ts';

test('COMPLETED + active → searchable（可检索）', () => {
  assert.equal(deriveStatus('COMPLETED', true), 'searchable');
});

test('COMPLETED + active=false → retired（已停用优先于索引状态）', () => {
  assert.equal(deriveStatus('COMPLETED', false), 'retired');
});

test('PROCESSING + active → processing', () => {
  assert.equal(deriveStatus('PROCESSING', true), 'processing');
});

test('PENDING + active 未定义 → pending', () => {
  assert.equal(deriveStatus('PENDING', undefined), 'pending');
});

test('FAILED + active → failed', () => {
  assert.equal(deriveStatus('FAILED', true), 'failed');
});

test('未知/空状态 → pending', () => {
  assert.equal(deriveStatus(undefined, true), 'pending');
});

test('停用操作仅对启用中文档可用', () => {
  assert.equal(canRetire(true), true);
  assert.equal(canRetire(undefined), true);
  assert.equal(canRetire(false), false);
});

test('状态文案齐全且已停用为"已停用"', () => {
  assert.equal(DOC_STATUS_LABEL.retired, '已停用');
  assert.equal(DOC_STATUS_LABEL.searchable, '可检索');
});
