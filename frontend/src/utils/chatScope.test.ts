import { test } from 'node:test';
import assert from 'node:assert/strict';
import { narrowScope, selectableIds } from './chatScope.ts';

// 已选 service 上下文解析到 {1,2}，作为限制范围
const RANGE = new Set([1, 2]);

test('范围内取消勾选后不会被后端加回（只保留勾选的）', () => {
  // 用户只勾选 1，取消 2
  assert.deepEqual(narrowScope([1], RANGE, true), [1]);
});

test('范围外的显式 KB ID 被安全排除，不扩大范围', () => {
  // 勾选了范围内 2 和范围外 3
  assert.deepEqual(narrowScope([2, 3], RANGE, true), [2]);
});

test('显式选择与范围无交集时得到空集合（不退回全量）', () => {
  // 只勾选了范围外的 3、4
  assert.deepEqual(narrowScope([3, 4], RANGE, true), []);
});

test('上下文范围为空时得到空集合（不退回全量检索）', () => {
  assert.deepEqual(narrowScope([1], new Set(), true), []);
});

test('不带上下文的旧请求按旧行为保留显式选择（去重）', () => {
  assert.deepEqual(narrowScope([1, 2, 2, 5], null, false).sort(), [1, 2, 5]);
});

test('selectableIds：已选上下文只允许范围内勾选', () => {
  assert.deepEqual(selectableIds([1, 2, 3, 4], RANGE, true), [1, 2]);
});

test('selectableIds：未选上下文允许全部勾选', () => {
  assert.deepEqual(selectableIds([1, 2, 3, 4], null, false), [1, 2, 3, 4]);
});

test('selectableIds：上下文范围为空时无可选项', () => {
  assert.deepEqual(selectableIds([1, 2, 3, 4], new Set(), true), []);
});
