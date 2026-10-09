import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  calcPassRate,
  formatRate,
  isEvidenceHit,
  getRunStatusMeta,
  formatTimestamp,
} from './regression.ts';

test('calcPassRate 正常计算通过率', () => {
  assert.equal(calcPassRate(8, 10), 0.8);
  assert.equal(calcPassRate(10, 10), 1);
});

test('calcPassRate 分母为 0 时返回 0（避免除零）', () => {
  assert.equal(calcPassRate(0, 0), 0);
  assert.equal(calcPassRate(5, 0), 0);
});

test('formatRate 保留一位小数百分比', () => {
  assert.equal(formatRate(0.8), '80.0%');
  assert.equal(formatRate(0.6666), '66.7%');
  assert.equal(formatRate(1), '100.0%');
});

test('isEvidenceHit 命中任意期望证据即为 true', () => {
  assert.equal(isEvidenceHit(['a', 'b'], ['b', 'c']), true);
  assert.equal(isEvidenceHit(['x', 'y'], ['b', 'c']), false);
});

test('isEvidenceHit 空集合返回 false', () => {
  assert.equal(isEvidenceHit([], ['b']), false);
  assert.equal(isEvidenceHit(['a'], []), false);
});

test('getRunStatusMeta 映射运行状态标签', () => {
  assert.equal(getRunStatusMeta('COMPLETED').text, '已完成');
  assert.equal(getRunStatusMeta('RUNNING').text, '运行中');
  assert.ok(getRunStatusMeta('COMPLETED').color.includes('emerald'));
});

test('formatTimestamp 处理空值返回占位符', () => {
  assert.equal(formatTimestamp(null), '—');
  assert.equal(formatTimestamp(undefined), '—');
  assert.equal(formatTimestamp(''), '—');
});
