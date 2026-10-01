import { test } from 'node:test';
import assert from 'node:assert/strict';
import { applyFilters } from './kbFilter.ts';
import type { FilterState } from './kbFilter.ts';

interface TestKB {
  id: number;
  name: string;
  service: string | null;
  environment: string | null;
}

const ALL: FilterState = { type: 'all' };
const UNCLASSIFIED: FilterState = { type: 'unclassified' };
const val = (v: string): FilterState => ({ type: 'value', value: v });

const items: TestKB[] = [
  { id: 1, name: 'kb1', service: 'auth', environment: 'prod' },
  { id: 2, name: 'kb2', service: 'auth', environment: null },
  { id: 3, name: 'kb3', service: null, environment: 'prod' },
  { id: 4, name: 'kb4', service: null, environment: null },
  { id: 5, name: 'kb5', service: '\\0UNCATEGORIZED', environment: 'staging' },
];

test('all + all → 返回全部 5 项', () => {
  const result = applyFilters(items, ALL, ALL);
  assert.equal(result.length, 5);
});

test('unclassified service → 只显示 service 为 null 的项 (id=3,4)', () => {
  const result = applyFilters(items, UNCLASSIFIED, ALL);
  assert.deepEqual(result.map(i => i.id), [3, 4]);
});

test('unclassified environment → 只显示 environment 为 null 的项 (id=2,4)', () => {
  const result = applyFilters(items, ALL, UNCLASSIFIED);
  assert.deepEqual(result.map(i => i.id), [2, 4]);
});

test('unclassified + unclassified → 交集 (id=4)', () => {
  const result = applyFilters(items, UNCLASSIFIED, UNCLASSIFIED);
  assert.deepEqual(result.map(i => i.id), [4]);
});

test('value:auth → 只显示 service=auth 的项 (id=1,2)', () => {
  const result = applyFilters(items, val('auth'), ALL);
  assert.deepEqual(result.map(i => i.id), [1, 2]);
});

test('value:prod environment → 只显示 environment=prod 的项 (id=1,3)', () => {
  const result = applyFilters(items, ALL, val('prod'));
  assert.deepEqual(result.map(i => i.id), [1, 3]);
});

test('service:auth + environment:prod → 交集 (id=1)', () => {
  const result = applyFilters(items, val('auth'), val('prod'));
  assert.deepEqual(result.map(i => i.id), [1]);
});

test('真实标签恰好为 \\0UNCATEGORIZED → 通过 value 筛选命中，不与 unclassified 混淆', () => {
  // 选"未分类"不应包含 id=5（它的 service 是字面字符串 \0UNCATEGORIZED，不是 null）
  const unclassifiedResult = applyFilters(items, UNCLASSIFIED, ALL);
  assert.ok(!unclassifiedResult.some(i => i.id === 5));

  // 选 value:\0UNCATEGORIZED 应只命中 id=5
  const valueResult = applyFilters(items, val('\\0UNCATEGORIZED'), ALL);
  assert.deepEqual(valueResult.map(i => i.id), [5]);
});

test('空列表 → 返回空', () => {
  const result = applyFilters([], UNCLASSIFIED, UNCLASSIFIED);
  assert.equal(result.length, 0);
});
