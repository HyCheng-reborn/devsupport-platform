import { test } from 'node:test';
import assert from 'node:assert/strict';
import { toSourceTagView } from './sourceDisplay.ts';
import type { SourceReference } from '../api/ragChat.ts';

// 构造一条来源；service/environment 用扩展对象覆盖，模拟不同数据形态
const src = (over: Partial<SourceReference> = {}): SourceReference => ({
  kbId: 1,
  documentName: 'README.md',
  contentSnippet: '后端端口 8080',
  score: 0.9,
  ...over,
});

test('新来源带 service+environment → 两个标签都展示，不显示无标签', () => {
  const v = toSourceTagView(src({ service: '支付网关', environment: '生产' }));
  assert.equal(v.serviceName, '支付网关');
  assert.equal(v.environmentName, '生产');
  assert.equal(v.showNoLabel, false);
});

test('只有 service → 展示 service，不显示无标签', () => {
  const v = toSourceTagView(src({ service: '订单服务', environment: null }));
  assert.equal(v.serviceName, '订单服务');
  assert.equal(v.environmentName, null);
  assert.equal(v.showNoLabel, false);
});

test('只有 environment → 展示 environment，不显示无标签', () => {
  const v = toSourceTagView(src({ service: null, environment: '预发' }));
  assert.equal(v.serviceName, null);
  assert.equal(v.environmentName, '预发');
  assert.equal(v.showNoLabel, false);
});

test('两者均为 null → 显示「无标签」', () => {
  const v = toSourceTagView(src({ service: null, environment: null }));
  assert.equal(v.serviceName, null);
  assert.equal(v.environmentName, null);
  assert.equal(v.showNoLabel, true);
});

test('旧 sourcesJson 缺字段（undefined）→ 视为无标签', () => {
  // 模拟旧持久化 JSON：完全没有 service/environment 键
  const legacy = { kbId: 2, documentName: 'a.md', contentSnippet: 'x', score: 0.5 } as SourceReference;
  const v = toSourceTagView(legacy);
  assert.equal(v.serviceName, null);
  assert.equal(v.environmentName, null);
  assert.equal(v.showNoLabel, true);
});

test('空字符串标签 → 归一为 null 并显示无标签', () => {
  const v = toSourceTagView(src({ service: '', environment: '' }));
  assert.equal(v.serviceName, null);
  assert.equal(v.environmentName, null);
  assert.equal(v.showNoLabel, true);
});

test('score 保留原口径格式化为百分比；score 为 null 时不展示', () => {
  assert.equal(toSourceTagView(src({ score: 0.9 })).scoreLabel, '90%');
  assert.equal(toSourceTagView(src({ score: 0.956 })).scoreLabel, '96%'); // toFixed(0) 四舍五入
  assert.equal(toSourceTagView(src({ score: 0 })).scoreLabel, '0%');      // 0 != null，仍展示
  assert.equal(toSourceTagView(src({ score: null })).scoreLabel, null);   // null 不展示
});

test('文档名与片段原样透传（映射不改动来源正文）', () => {
  const v = toSourceTagView(src({ documentName: '部署说明.pdf', contentSnippet: '多行\n片段' }));
  assert.equal(v.documentName, '部署说明.pdf');
  assert.equal(v.contentSnippet, '多行\n片段');
});

test('对来源数组做映射保持顺序与数量不变（对应页面 slice(0,5) 逐条渲染）', () => {
  const list: SourceReference[] = [
    src({ kbId: 1, documentName: 'doc1' }),
    src({ kbId: 2, documentName: 'doc2', service: 'S2' }),
    src({ kbId: 3, documentName: 'doc3', environment: 'E3' }),
  ];
  const views = list.map(toSourceTagView);
  assert.equal(views.length, 3);
  assert.deepEqual(views.map(v => v.documentName), ['doc1', 'doc2', 'doc3']);
  assert.deepEqual(views.map(v => v.serviceName), [null, 'S2', null]);
  assert.deepEqual(views.map(v => v.environmentName), [null, null, 'E3']);
});
