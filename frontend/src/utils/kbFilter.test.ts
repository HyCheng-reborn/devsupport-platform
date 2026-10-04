import { test } from 'node:test';
import assert from 'node:assert/strict';
import { applyFilters } from './kbFilter.ts';
import type { FilterState } from './kbFilter.ts';

interface TestKB {
  id: number;
  name: string;
  service: string | null;
  environment: string | null;
  project?: string | null;
  docType?: string | null;
  versionLabel?: string | null;
  conflict?: boolean;
  versionConflict?: boolean;
}

const ALL: FilterState = { type: 'all' };
const UNCLASSIFIED: FilterState = { type: 'unclassified' };
const val = (v: string): FilterState => ({ type: 'value', value: v });

const items: TestKB[] = [
  { id: 1, name: 'kb1', service: 'auth', environment: 'prod', project: 'billing', docType: 'runbook', versionLabel: 'v1' },
  { id: 2, name: 'kb2', service: 'auth', environment: null, project: null, docType: 'runbook', versionLabel: 'v2' },
  { id: 3, name: 'kb3', service: null, environment: 'prod', project: 'billing', docType: null, versionLabel: null },
  { id: 4, name: 'kb4', service: null, environment: null, project: null, docType: null, versionLabel: null },
  { id: 5, name: 'kb5', service: '\\0UNCATEGORIZED', environment: 'staging', project: 'order', docType: 'error-code', versionLabel: 'v1' },
  { id: 6, name: 'kb6', service: 'auth', environment: 'prod', project: 'billing', docType: 'runbook', versionLabel: 'v1', conflict: true, versionConflict: true },
  { id: 7, name: 'kb7', service: null, environment: null, project: null, docType: null, versionLabel: null, versionConflict: true },
];

test('all + all → 返回全部 7 项', () => {
  const result = applyFilters(items, ALL, ALL);
  assert.equal(result.length, 7);
});

test('unclassified service → 只显示 service 为 null 的项 (id=3,4,7)', () => {
  const result = applyFilters(items, UNCLASSIFIED, ALL);
  assert.deepEqual(result.map(i => i.id).sort((a, b) => a - b), [3, 4, 7]);
});

test('unclassified environment → 只显示 environment 为 null 的项 (id=2,4,7)', () => {
  const result = applyFilters(items, ALL, UNCLASSIFIED);
  assert.deepEqual(result.map(i => i.id).sort((a, b) => a - b), [2, 4, 7]);
});

test('unclassified + unclassified → 交集 (id=4,7)', () => {
  const result = applyFilters(items, UNCLASSIFIED, UNCLASSIFIED);
  assert.deepEqual(result.map(i => i.id).sort((a, b) => a - b), [4, 7]);
});

test('value:auth → 只显示 service=auth 的项 (id=1,2,6)', () => {
  const result = applyFilters(items, val('auth'), ALL);
  assert.deepEqual(result.map(i => i.id).sort((a, b) => a - b), [1, 2, 6]);
});

test('value:prod environment → 只显示 environment=prod 的项 (id=1,3,6)', () => {
  const result = applyFilters(items, ALL, val('prod'));
  assert.deepEqual(result.map(i => i.id).sort((a, b) => a - b), [1, 3, 6]);
});

test('service:auth + environment:prod → 交集 (id=1,6)', () => {
  const result = applyFilters(items, val('auth'), val('prod'));
  assert.deepEqual(result.map(i => i.id).sort((a, b) => a - b), [1, 6]);
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

// ========== project 筛选 ==========

test('project:all → 返回全部 7 项', () => {
  const result = applyFilters(items, ALL, ALL, ALL);
  assert.equal(result.length, 7);
});

test('project:unclassified → 只显示 project 为 null 的项 (id=2,4,7)', () => {
  const result = applyFilters(items, ALL, ALL, UNCLASSIFIED);
  assert.deepEqual(result.map(i => i.id).sort((a, b) => a - b), [2, 4, 7]);
});

test('project:value:billing → 只显示 project=billing 的项 (id=1,3,6)', () => {
  const result = applyFilters(items, ALL, ALL, val('billing'));
  assert.deepEqual(result.map(i => i.id).sort((a, b) => a - b), [1, 3, 6]);
});

// ========== docType 筛选 ==========

test('docType:all → 返回全部 7 项', () => {
  const result = applyFilters(items, ALL, ALL, ALL, ALL);
  assert.equal(result.length, 7);
});

test('docType:unclassified → 只显示 docType 为 null 的项 (id=3,4,7)', () => {
  const result = applyFilters(items, ALL, ALL, ALL, UNCLASSIFIED);
  assert.deepEqual(result.map(i => i.id).sort((a, b) => a - b), [3, 4, 7]);
});

test('docType:value:runbook → 只显示 docType=runbook 的项 (id=1,2,6)', () => {
  const result = applyFilters(items, ALL, ALL, ALL, val('runbook'));
  assert.deepEqual(result.map(i => i.id).sort((a, b) => a - b), [1, 2, 6]);
});

// ========== 组合筛选 ==========

test('project:billing + docType:runbook → 交集 (id=1,6)', () => {
  const result = applyFilters(items, ALL, ALL, val('billing'), val('runbook'));
  assert.deepEqual(result.map(i => i.id).sort((a, b) => a - b), [1, 6]);
});

test('service:auth + project:unclassified → 交集 (id=2)', () => {
  const result = applyFilters(items, val('auth'), ALL, UNCLASSIFIED);
  assert.deepEqual(result.map(i => i.id), [2]);
});

test('四个维度全指定 → 精确命中 (id=5)', () => {
  const result = applyFilters(items, ALL, val('staging'), val('order'), val('error-code'));
  assert.deepEqual(result.map(i => i.id), [5]);
});

// ========== version 筛选 ==========

test('version:all → 返回全部 7 项', () => {
  const result = applyFilters(items, ALL, ALL, ALL, ALL, ALL);
  assert.equal(result.length, 7);
});

test('version:unclassified → 只显示 versionLabel 为 null 的项 (id=3,4,7)', () => {
  const result = applyFilters(items, ALL, ALL, ALL, ALL, UNCLASSIFIED);
  assert.deepEqual(result.map(i => i.id).sort((a, b) => a - b), [3, 4, 7]);
});

test('version:value:v1 → 只显示 versionLabel=v1 的项 (id=1,5,6)', () => {
  const result = applyFilters(items, ALL, ALL, ALL, ALL, val('v1'));
  assert.deepEqual(result.map(i => i.id).sort((a, b) => a - b), [1, 5, 6]);
});

test('version:value:v2 → 只显示 versionLabel=v2 的项 (id=2)', () => {
  const result = applyFilters(items, ALL, ALL, ALL, ALL, val('v2'));
  assert.deepEqual(result.map(i => i.id), [2]);
});

// ========== version 组合筛选 ==========

test('version:v1 + project:billing → 交集 (id=1,6)', () => {
  const result = applyFilters(items, ALL, ALL, val('billing'), ALL, val('v1'));
  assert.deepEqual(result.map(i => i.id).sort((a, b) => a - b), [1, 6]);
});

test('version:v1 + docType:runbook → 交集 (id=1,6)', () => {
  const result = applyFilters(items, ALL, ALL, ALL, val('runbook'), val('v1'));
  assert.deepEqual(result.map(i => i.id).sort((a, b) => a - b), [1, 6]);
});

test('version:v1 + project:order + docType:error-code → 交集 (id=5)', () => {
  const result = applyFilters(items, ALL, ALL, val('order'), val('error-code'), val('v1'));
  assert.deepEqual(result.map(i => i.id), [5]);
});

test('五个维度全指定 → 精确命中 (id=5)', () => {
  const result = applyFilters(items, ALL, val('staging'), val('order'), val('error-code'), val('v1'));
  assert.deepEqual(result.map(i => i.id), [5]);
});

// ========== conflict 筛选 ==========

test('conflictFilter=all → 返回全部 7 项（含冲突和非冲突）', () => {
  const result = applyFilters(items, ALL, ALL, ALL, ALL, ALL, 'all');
  assert.equal(result.length, 7);
});

test('conflictFilter=conflict → 只返回 conflict=true 或 versionConflict=true 的项 (id=6,7)', () => {
  const result = applyFilters(items, ALL, ALL, ALL, ALL, ALL, 'conflict');
  assert.deepEqual(result.map(i => i.id).sort((a, b) => a - b), [6, 7]);
});

test('conflictFilter=conflict + project:billing → 交集 (id=6)', () => {
  const result = applyFilters(items, ALL, ALL, val('billing'), ALL, ALL, 'conflict');
  assert.deepEqual(result.map(i => i.id), [6]);
});

test('conflictFilter=conflict + docType:runbook → 交集 (id=6)', () => {
  const result = applyFilters(items, ALL, ALL, ALL, val('runbook'), ALL, 'conflict');
  assert.deepEqual(result.map(i => i.id), [6]);
});
