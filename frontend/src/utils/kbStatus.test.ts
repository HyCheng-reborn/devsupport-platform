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

test('状态文案齐全且已停用为“已停用”', () => {
  assert.equal(DOC_STATUS_LABEL.retired, '已停用');
  assert.equal(DOC_STATUS_LABEL.searchable, '可检索');
});

// ========== conflict 状态 ==========

test('conflict=true → 无论其他字段如何，状态都是 conflict', () => {
  assert.equal(deriveStatus('COMPLETED', true, true), 'conflict');
  assert.equal(deriveStatus('FAILED', true, true), 'conflict');
  assert.equal(deriveStatus('PROCESSING', false, true), 'conflict');
});

test('conflict=true + active=false → 仍然 conflict（conflict 优先于 retired）', () => {
  assert.equal(deriveStatus('COMPLETED', false, true), 'conflict');
});

test('conflict=false → 正常状态推导', () => {
  assert.equal(deriveStatus('COMPLETED', true, false), 'searchable');
  assert.equal(deriveStatus('COMPLETED', false, false), 'retired');
  assert.equal(deriveStatus('FAILED', true, false), 'failed');
  assert.equal(deriveStatus('PROCESSING', true, false), 'processing');
});

test('conflict 未指定 → 正常状态推导', () => {
  assert.equal(deriveStatus('COMPLETED', true), 'searchable');
  assert.equal(deriveStatus('COMPLETED', false), 'retired');
});

test('DOC_STATUS_LABEL.conflict 为“版本冲突”', () => {
  assert.equal(DOC_STATUS_LABEL.conflict, '版本冲突');
});

// ========== ADOPTING / ABANDONED 状态 ==========

test('vectorStatus=ADOPTING + active → adopting（采用中）', () => {
  assert.equal(deriveStatus('ADOPTING', true), 'adopting');
});

test('vectorStatus=ABANDONED + active → abandoned（已放弃）', () => {
  assert.equal(deriveStatus('ABANDONED', true), 'abandoned');
});

test('vectorStatus=CONFLICT（无 conflict 标记）→ conflict', () => {
  assert.equal(deriveStatus('CONFLICT', true), 'conflict');
});

test('ADOPTING + active=false → retired（停用优先于采用中）', () => {
  assert.equal(deriveStatus('ADOPTING', false), 'retired');
});

test('ABANDONED + active=false → retired（停用优先于已放弃）', () => {
  assert.equal(deriveStatus('ABANDONED', false), 'retired');
});

test('DOC_STATUS_LABEL 包含 adopting/abandoned/conflict 的正确标签', () => {
  assert.equal(DOC_STATUS_LABEL.adopting, '采用中');
  assert.equal(DOC_STATUS_LABEL.abandoned, '已放弃');
  assert.equal(DOC_STATUS_LABEL.conflict, '版本冲突');
});
