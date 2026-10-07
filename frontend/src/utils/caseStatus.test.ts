import { test } from 'node:test';
import assert from 'node:assert/strict';
import { getCaseStatusLabel, caseStatusLabels, caseStatusStyles } from './caseStatus.ts';

test('所有状态都有中文标签', () => {
  assert.equal(caseStatusLabels.DRAFT, '草稿');
  assert.equal(caseStatusLabels.PENDING_REVIEW, '待审核');
  assert.equal(caseStatusLabels.PUBLISHED, '已发布');
  assert.equal(caseStatusLabels.REJECTED, '已退回');
  assert.equal(caseStatusLabels.DEPRECATED, '已废弃');
});

test('getCaseStatusLabel 返回正确标签', () => {
  assert.equal(getCaseStatusLabel('DRAFT'), '草稿');
  assert.equal(getCaseStatusLabel('PUBLISHED'), '已发布');
  assert.equal(getCaseStatusLabel('REJECTED'), '已退回');
});

test('所有状态都有样式定义', () => {
  assert.ok(caseStatusStyles.DRAFT.includes('bg-slate'));
  assert.ok(caseStatusStyles.PUBLISHED.includes('bg-emerald'));
  assert.ok(caseStatusStyles.DEPRECATED.includes('line-through'));
});
