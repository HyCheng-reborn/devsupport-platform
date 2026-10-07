import { expect, test, type Page, type Route } from '@playwright/test';

/**
 * 案例库 E2E（纯 mock，无后端）。
 * 覆盖：列表页访问、状态筛选、详情路由、从会话生成案例。
 */

function ok(data: unknown): Parameters<Route['fulfill']>[0] {
  return { contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'success', data }) };
}

function caseItem(over: Record<string, unknown>) {
  return {
    id: 1,
    title: '测试案例',
    problemDescription: '问题描述',
    rootCause: '根因',
    resolutionSteps: '解决步骤',
    resolutionResult: '已解决',
    affectedVersions: 'v1.0',
    environment: 'production',
    service: 'payment-service',
    aiGeneratedContent: null,
    userConfirmedContent: null,
    status: 'DRAFT',
    versionNo: 1,
    sourceSessionId: null,
    sourceMessageId: null,
    active: true,
    createdBy: 'test',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...over,
  };
}

async function mockCasesListApi(page: Page, items: Record<string, unknown>[]) {
  await page.route('**/api/cases**', (r) => {
    if (r.request().method() === 'GET') {
      return r.fulfill(ok(items.map((i) => caseItem(i))));
    }
    return r.fulfill(ok(null));
  });
}

test.describe('案例库列表页', () => {
  test('案例列表页可访问并展示标题', async ({ page }) => {
    await mockCasesListApi(page, [
      { id: 1, title: '支付网关超时', status: 'PUBLISHED' },
      { id: 2, title: '数据库连接池耗尽', status: 'DRAFT' },
    ]);

    await page.goto('/cases');

    // 页面标题可见
    await expect(page.getByText('故障案例库')).toBeVisible();

    // 案例条目可见
    await expect(page.getByText('支付网关超时')).toBeVisible();
    await expect(page.getByText('数据库连接池耗尽')).toBeVisible();
  });

  test('状态筛选按钮可见', async ({ page }) => {
    await mockCasesListApi(page, []);
    await page.goto('/cases');

    // 状态筛选按钮
    await expect(page.getByRole('button', { name: '全部状态' })).toBeVisible();
    await expect(page.getByRole('button', { name: '草稿' })).toBeVisible();
    await expect(page.getByRole('button', { name: '已发布' })).toBeVisible();
  });

  test('空列表展示提示文案', async ({ page }) => {
    await mockCasesListApi(page, []);
    await page.goto('/cases');

    await expect(page.getByText('暂无案例')).toBeVisible();
  });
});

test.describe('案例详情页', () => {
  test('案例详情页路由正确', async ({ page }) => {
    await page.route('**/api/cases/1', (r) =>
      r.fulfill(ok(caseItem({ id: 1, title: '支付网关超时案例', status: 'DRAFT' }))));

    await page.goto('/cases/1');

    // 案例标题可见
    await expect(page.getByText('支付网关超时案例')).toBeVisible();
  });

  test('不存在的案例显示友好提示', async ({ page }) => {
    await page.route('**/api/cases/999', (r) =>
      r.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ code: 404, message: 'Not found', data: null }) }));

    await page.goto('/cases/999');

    // 友好提示
    await expect(page.getByText('案例不存在或已被删除')).toBeVisible();
  });
});
