import { expect, test, type Page, type Route } from '@playwright/test';

/**
 * 阶段 1「检索范围一致性」最小 E2E（纯 mock，无后端、无付费 LLM/Embedding）。
 * 语义：service/environment 解析出的集合是限制范围，勾选只能在范围内缩小、不能扩大；
 * 展示数量 == 最终提交集合；空范围明确提示且不退回全量。
 * 「显式与范围无交集」在 UI 上不可能产生（弹窗只列范围内文档），该分支由后端
 * RagChatSessionContextTest#shouldRejectWhenExplicitIdsDisjointFromScope 覆盖。
 */

const KB_ITEMS = [
  { id: 1, name: '支付网关手册', service: 'payment', environment: '生产' },
  { id: 2, name: '支付对账文档', service: 'payment', environment: '生产' },
  { id: 3, name: '认证服务手册', service: 'auth', environment: '生产' },
];

// service -> 范围内 KB ID；empty 故意解析为空以验证空范围
const RANGE_BY_SERVICE: Record<string, number[]> = {
  payment: [1, 2],
  auth: [3],
  empty: [],
};

function ok(data: unknown): Parameters<Route['fulfill']>[0] {
  return { contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'success', data }) };
}

function kbItem(kb: (typeof KB_ITEMS)[number]) {
  return {
    ...kb,
    category: null,
    originalFilename: `${kb.name}.md`,
    fileSize: 10,
    contentType: 'text/markdown',
    uploadedAt: '2026-01-01T00:00:00Z',
    lastAccessedAt: '2026-01-01T00:00:00Z',
    accessCount: 0,
    questionCount: 0,
    vectorStatus: 'COMPLETED',
    vectorError: null,
    chunkCount: 1,
    questionGenStatus: 'NONE',
    questionGenError: null,
  };
}

async function mockApis(page: Page) {
  await page.route('**/api/knowledgebase/services**', (r) => r.fulfill(ok(['payment', 'auth', 'empty'])));
  await page.route('**/api/knowledgebase/environments**', (r) => r.fulfill(ok(['生产'])));
  await page.route('**/api/knowledgebase/list**', (r) => r.fulfill(ok(KB_ITEMS.map(kbItem))));

  await page.route('**/api/knowledgebase/resolve-context**', (r) => {
    const svc = new URL(r.request().url()).searchParams.get('service') ?? '';
    const ids = RANGE_BY_SERVICE[svc] ?? [];
    const items = KB_ITEMS.filter((kb) => ids.includes(kb.id))
      .map((kb) => ({ id: kb.id, name: kb.name, service: kb.service, environment: kb.environment }));
    return r.fulfill(ok(items));
  });

  await page.route('**/api/rag-chat/sessions**', (r) => {
    if (r.request().method() === 'POST') {
      const body = JSON.parse(r.request().postData() ?? '{}');
      return r.fulfill(ok({ id: 999, title: 'e2e', knowledgeBaseIds: body.knowledgeBaseIds ?? [], createdAt: '2026-01-01T00:00:00Z' }));
    }
    return r.fulfill(ok([]));
  });
}

test.describe('排查会话检索范围一致性（阶段 1 验收）', () => {
  test.beforeEach(async ({ page }) => {
    await mockApis(page);
  });

  test('选择服务后展示数量等于最终提交集合；缩小后只提交缩小集合、不含范围外文档', async ({ page }) => {
    await page.goto('/chat');

    // 选择 service = payment，解析范围 = {1,2}
    await page.getByRole('button', { name: '服务', exact: true }).click();
    await page.getByRole('button', { name: 'payment', exact: true }).click();

    // 展示数量 = 范围内可检索文档数 = 2
    await expect(page.getByText('2 个文档', { exact: true })).toBeVisible();

    // 打开选择弹窗
    await page.getByRole('button', { name: '新建排查会话' }).click();

    // 弹窗只列出范围内 KB；范围外的 auth(id3) 不可见 -> 无法扩大范围
    await expect(page.locator('label', { hasText: '支付网关手册' })).toBeVisible();
    await expect(page.locator('label', { hasText: '支付对账文档' })).toBeVisible();
    await expect(page.locator('label', { hasText: '认证服务手册' })).toHaveCount(0);

    // 取消勾选一个，缩小到 {1}
    await page.locator('label', { hasText: '支付对账文档' }).click();

    // 展示数量随之更新为 1（等于最终集合）
    await expect(page.getByText('已选 1 个文档', { exact: true })).toBeVisible();
    const createButton = page.getByRole('button', { name: /创建会话（1）/ });
    await expect(createButton).toBeVisible();

    // 捕获提交请求，断言只提交缩小后的 {1}，且带 service 上下文
    const reqPromise = page.waitForRequest(
      (r) => r.method() === 'POST' && r.url().includes('/api/rag-chat/sessions')
    );
    await createButton.click();
    const req = await reqPromise;
    const body = JSON.parse(req.postData() ?? '{}');

    expect([...body.knowledgeBaseIds].sort((a: number, b: number) => a - b)).toEqual([1]);
    expect(body.service).toBe('payment');
  });

  test('空范围明确提示且不扩大为全量知识库', async ({ page }) => {
    await page.goto('/chat');

    // 选择一个解析结果为空的 service
    await page.getByRole('button', { name: '服务', exact: true }).click();
    await page.getByRole('button', { name: 'empty', exact: true }).click();

    // 明确提示空范围；不显示任何"个文档"计数（绝不显示全量 3）
    await expect(page.getByText('所选服务/环境没有匹配的知识库')).toBeVisible();
    await expect(page.getByText(/个文档/)).toHaveCount(0);

    // 打开弹窗：无可勾选文档、无创建按钮，无法提交全量
    await page.getByRole('button', { name: '新建排查会话' }).click();
    await expect(page.getByText('所选上下文没有匹配的可检索知识库')).toBeVisible();
    await expect(page.getByRole('checkbox')).toHaveCount(0);
    await expect(page.getByRole('button', { name: /创建会话/ })).toHaveCount(0);
  });
});

// ========== 来源面板版本标签 ==========

test.describe('排查会话来源版本标签', () => {
  test('来源带 versionLabel 时展示紫色版本标签', async ({ page }) => {
    const sourcesWithVersion = [
      { kbId: 1, documentName: '支付网关手册', contentSnippet: '端口 8080', score: 0.9,
        service: 'payment', environment: '生产', versionLabel: 'v2.3', versionNo: 5, documentKey: 'dk1' },
      { kbId: 2, documentName: '对账文档', contentSnippet: 'T+1 结算', score: 0.7,
        service: 'payment', environment: '生产', versionLabel: null, versionNo: 3, documentKey: 'dk2' },
    ];

    const sessionDetail = {
      id: 999,
      title: 'e2e-version-test',
      knowledgeBases: KB_ITEMS.map(kbItem),
      messages: [
        { id: 1, type: 'user', content: '测试问题', createdAt: '2026-01-01T00:00:00Z' },
        { id: 2, type: 'assistant', content: '这是回答内容',
          sourcesJson: JSON.stringify(sourcesWithVersion),
          status: 'COMPLETED', createdAt: '2026-01-01T00:00:01Z' },
      ],
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:01Z',
    };

    await page.route('**/api/rag-chat/sessions/999', (r) => r.fulfill(ok(sessionDetail)));

    await page.goto('/chat/999');

    // versionLabel 优先展示
    await expect(page.getByText('v2.3').first()).toBeVisible();
    // 无 versionLabel 但有 versionNo → 展示 "v3"
    await expect(page.getByText('v3').first()).toBeVisible();
  });
});
