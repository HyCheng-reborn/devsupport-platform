import { expect, test, type Page, type Route } from '@playwright/test';

/**
 * 阶段 2 文档中心/上传 E2E（纯 mock，无后端、无付费 API）。
 * 覆盖：上传元数据贯通、状态展示(处理中/可检索/失败/已停用/版本冲突)、失败重试、停用、版本替换关系。
 */

function ok(data: unknown): Parameters<Route['fulfill']>[0] {
  return { contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'success', data }) };
}

function kbItem(over: Record<string, unknown>) {
  return {
    id: 1, name: 'doc', category: null, service: null, environment: null,
    project: null, docType: null, source: null, versionLabel: null, documentKey: null,
    versionNo: 1, active: true, versionConflict: false,
    originalFilename: 'doc.md', fileSize: 10, contentType: 'text/markdown',
    uploadedAt: '2026-01-01T00:00:00Z', lastAccessedAt: '2026-01-01T00:00:00Z',
    accessCount: 0, questionCount: 0, vectorStatus: 'COMPLETED', vectorError: null,
    chunkCount: 1, questionGenStatus: 'NONE', questionGenError: null,
    ...over,
  };
}

async function mockDocCenterApis(page: Page) {
  await page.route('**/api/knowledgebase/stats', (r) =>
    r.fulfill(ok({ totalCount: 4, totalQuestionCount: 0, totalAccessCount: 0, completedCount: 2, processingCount: 1 })));
  await page.route('**/api/knowledgebase/categories', (r) => r.fulfill(ok([])));
  await page.route('**/api/knowledgebase/services', (r) => r.fulfill(ok(['payment-gw'])));
  await page.route('**/api/knowledgebase/environments', (r) => r.fulfill(ok(['prod'])));
  await page.route('**/api/knowledgebase/list**', (r) =>
    r.fulfill(ok([
      kbItem({ id: 10, name: '启用文档A', project: 'billing', versionNo: 2, versionLabel: 'v2' }),
      kbItem({ id: 11, name: '失败文档B', vectorStatus: 'FAILED', vectorError: '向量化失败: 解析超时' }),
      kbItem({ id: 12, name: '停用文档C', active: false, documentKey: 'dkC', versionNo: 1 }),
      kbItem({ id: 13, name: '冲突文档D', active: true, documentKey: 'dkD', versionNo: 2, versionConflict: true, conflictReason: 'SAME_VERSION_DIFFERENT_CONTENT' }),
    ])));
}

test.describe('阶段 2 文档中心', () => {
  test('展示状态/版本/冲突，支持重试与停用', async ({ page }) => {
    await mockDocCenterApis(page);

    const revectorizeReq = page.waitForRequest(
      (r) => r.method() === 'POST' && /\/api\/knowledgebase\/\d+\/revectorize/.test(r.url()));
    const retireReq = page.waitForRequest(
      (r) => r.method() === 'PUT' && /\/api\/knowledgebase\/\d+\/retire/.test(r.url()));
    await page.route('**/api/knowledgebase/*/revectorize', (r) => r.fulfill(ok(null)));
    await page.route('**/api/knowledgebase/*/retire', (r) => r.fulfill(ok(null)));

    await page.goto('/docs');

    // 状态徽标
    await expect(page.getByText('失败').first()).toBeVisible();
    await expect(page.getByText('已停用').first()).toBeVisible();
    await expect(page.getByText('版本冲突').first()).toBeVisible();
    // 版本列展示 v2（在表格行内，不在筛选下拉中）
    await expect(page.locator('tr', { hasText: '启用文档A' }).getByText('v2').first()).toBeVisible();

    // 失败文档有"重新向量化"（重试），点击 → POST revectorize
    const failedRow = page.locator('tr', { hasText: '失败文档B' });
    await expect(failedRow.getByTitle('重新向量化')).toBeVisible();
    await failedRow.getByTitle('重新向量化').click();
    const rv = await revectorizeReq;
    expect(rv.url()).toContain('/api/knowledgebase/11/revectorize');

    // 启用文档有"停用"，点击 → PUT retire
    const activeRow = page.locator('tr', { hasText: '启用文档A' });
    await expect(activeRow.getByTitle('停用（退出检索）')).toBeVisible();
    await activeRow.getByTitle('停用（退出检索）').click();
    const rt = await retireReq;
    expect(rt.url()).toContain('/api/knowledgebase/10/retire');

    // 已停用文档不再提供停用按钮
    const retiredRow = page.locator('tr', { hasText: '停用文档C' });
    await expect(retiredRow.getByTitle('停用（退出检索）')).toHaveCount(0);
  });
});

test.describe('阶段 2 上传元数据', () => {
  test('填写项目/文档类型/适用版本/来源并随上传提交', async ({ page }) => {
    await page.route('**/api/knowledgebase/services', (r) => r.fulfill(ok(['payment-gw'])));
    await page.route('**/api/knowledgebase/environments', (r) => r.fulfill(ok(['prod'])));
    await page.route('**/api/knowledgebase/categories', (r) => r.fulfill(ok([])));
    await page.route('**/api/knowledgebase/stats', (r) =>
      r.fulfill(ok({ totalCount: 0, totalQuestionCount: 0, totalAccessCount: 0, completedCount: 0, processingCount: 0 })));
    await page.route('**/api/knowledgebase/list**', (r) => r.fulfill(ok([])));

    const uploadReq = page.waitForRequest(
      (r) => r.method() === 'POST' && r.url().includes('/api/knowledgebase/upload'));
    await page.route('**/api/knowledgebase/upload', (r) =>
      r.fulfill(ok({
        knowledgeBase: { id: 99, name: '支付网关排查', category: '', service: 'payment-gw', environment: 'prod',
          project: 'billing', docType: 'runbook', source: 'wiki', versionLabel: 'v1', documentKey: 'dk99',
          versionNo: 1, active: true, fileSize: 10, contentLength: 10 },
        storage: { fileKey: 'kb/99', fileUrl: 'http://x/kb/99' },
        duplicate: false,
      })));

    await page.goto('/docs/upload');

    await page.locator('#file-upload-input').setInputFiles('e2e/fixtures/sample-runbook.md');

    await page.getByPlaceholder('如：billing、order-center').fill('billing');
    await page.getByPlaceholder('如：runbook、部署手册、错误码').fill('runbook');
    await page.getByPlaceholder('如：v2.3').fill('v1');
    await page.getByPlaceholder('如：wiki、git、工单').fill('wiki');

    await page.getByRole('button', { name: '开始上传' }).click();

    const req = await uploadReq;
    const body = req.postDataBuffer()?.toString('latin1') ?? '';
    // multipart 表单需携带阶段 2 元数据字段
    expect(body).toContain('name="project"');
    expect(body).toContain('billing');
    expect(body).toContain('name="docType"');
    expect(body).toContain('runbook');
    expect(body).toContain('name="versionLabel"');
    expect(body).toContain('name="source"');
    expect(body).toContain('wiki');
  });
});

// ========== 项目/文档类型/版本筛选控件 ==========

test.describe('阶段 2 文档中心筛选控件', () => {
  test('项目和文档类型筛选下拉可见且可筛选列表', async ({ page }) => {
    await mockDocCenterApis(page);

    await page.goto('/docs');

    // 筛选下拉可见
    const projectSelect = page.locator('select').filter({ hasText: '全部项目' });
    const docTypeSelect = page.locator('select').filter({ hasText: '全部类型' });
    await expect(projectSelect).toBeVisible();
    await expect(docTypeSelect).toBeVisible();

    // 初始状态：4 条文档
    await expect(page.locator('tr', { hasText: '启用文档A' })).toBeVisible();
    await expect(page.locator('tr', { hasText: '失败文档B' })).toBeVisible();
    await expect(page.locator('tr', { hasText: '停用文档C' })).toBeVisible();
    await expect(page.locator('tr', { hasText: '冲突文档D' })).toBeVisible();

    // 选择 project=billing → 只显示启用文档A (project='billing')
    await projectSelect.selectOption('val:billing');
    await expect(page.locator('tr', { hasText: '启用文档A' })).toBeVisible();
    await expect(page.locator('tr', { hasText: '失败文档B' })).toHaveCount(0);
    await expect(page.locator('tr', { hasText: '停用文档C' })).toHaveCount(0);
    await expect(page.locator('tr', { hasText: '冲突文档D' })).toHaveCount(0);
  });

  test('版本筛选下拉可见且可筛选列表', async ({ page }) => {
    await mockDocCenterApis(page);

    await page.goto('/docs');

    // 版本筛选下拉可见
    const versionSelect = page.locator('select').filter({ hasText: '全部版本' });
    await expect(versionSelect).toBeVisible();

    // 初始状态：4 条文档
    await expect(page.locator('tr', { hasText: '启用文档A' })).toBeVisible();
    await expect(page.locator('tr', { hasText: '失败文档B' })).toBeVisible();
    await expect(page.locator('tr', { hasText: '停用文档C' })).toBeVisible();
    await expect(page.locator('tr', { hasText: '冲突文档D' })).toBeVisible();

    // 选择 version=v2 → 只显示启用文档A (versionLabel='v2')
    await versionSelect.selectOption('val:v2');
    await expect(page.locator('tr', { hasText: '启用文档A' })).toBeVisible();
    await expect(page.locator('tr', { hasText: '失败文档B' })).toHaveCount(0);
    await expect(page.locator('tr', { hasText: '停用文档C' })).toHaveCount(0);
    await expect(page.locator('tr', { hasText: '冲突文档D' })).toHaveCount(0);

    // 选择“未分类” → 显示 versionLabel 为 null 的 3 条文档
    await versionSelect.selectOption('unclassified');
    await expect(page.locator('tr', { hasText: '启用文档A' })).toHaveCount(0);
    await expect(page.locator('tr', { hasText: '失败文档B' })).toBeVisible();
    await expect(page.locator('tr', { hasText: '停用文档C' })).toBeVisible();
    await expect(page.locator('tr', { hasText: '冲突文档D' })).toBeVisible();
  });
});

// ========== 冲突筛选与操作按钮 ==========

test.describe('阶段 2 文档中心冲突操作', () => {
  test('冲突筛选复选框：勾选后仅显示冲突文档', async ({ page }) => {
    await mockDocCenterApis(page);
    await page.goto('/docs');

    // 初始状态：4 条文档均可见
    await expect(page.locator('tr', { hasText: '启用文档A' })).toBeVisible();
    await expect(page.locator('tr', { hasText: '失败文档B' })).toBeVisible();
    await expect(page.locator('tr', { hasText: '停用文档C' })).toBeVisible();
    await expect(page.locator('tr', { hasText: '冲突文档D' })).toBeVisible();

    // 勾选“仅显示冲突”
    const conflictCheckbox = page.locator('input[type="checkbox"]');
    await conflictCheckbox.check();

    // 只有冲突文档D可见
    await expect(page.locator('tr', { hasText: '启用文档A' })).toHaveCount(0);
    await expect(page.locator('tr', { hasText: '失败文档B' })).toHaveCount(0);
    await expect(page.locator('tr', { hasText: '停用文档C' })).toHaveCount(0);
    await expect(page.locator('tr', { hasText: '冲突文档D' })).toBeVisible();

    // 取消勾选 → 恢复全部
    await conflictCheckbox.uncheck();
    await expect(page.locator('tr', { hasText: '启用文档A' })).toBeVisible();
    await expect(page.locator('tr', { hasText: '冲突文档D' })).toBeVisible();
  });

  test('冲突文档行显示“采用此版本”和“放弃”按钮，点击触发确认对话框并调用 API', async ({ page }) => {
    await mockDocCenterApis(page);

    const adoptReq = page.waitForRequest(
      (r) => r.method() === 'POST' && /\/api\/knowledgebase\/\d+\/adopt/.test(r.url()));
    await page.route('**/api/knowledgebase/*/adopt', (r) => r.fulfill(ok(null)));
    await page.route('**/api/knowledgebase/*/abandon', (r) => r.fulfill(ok(null)));

    await page.goto('/docs');

    const conflictRow = page.locator('tr', { hasText: '冲突文档D' });

    // “采用此版本”按钮可见
    const adoptBtn = conflictRow.getByTitle('采用此版本');
    await expect(adoptBtn).toBeVisible();

    // “放弃此版本”按钮可见
    const abandonBtn = conflictRow.getByTitle('放弃此版本');
    await expect(abandonBtn).toBeVisible();

    // 点击“采用此版本” → 弹出确认对话框 → 接受 → POST adopt
    page.once('dialog', (dialog) => {
      expect(dialog.message()).toContain('采用此版本');
      dialog.accept();
    });
    await adoptBtn.click();
    const adopt = await adoptReq;
    expect(adopt.url()).toContain('/api/knowledgebase/13/adopt');
  });

  test('点击“放弃”按钮触发确认对话框并调用 abandon API', async ({ page }) => {
    await mockDocCenterApis(page);

    const abandonReq = page.waitForRequest(
      (r) => r.method() === 'POST' && /\/api\/knowledgebase\/\d+\/abandon/.test(r.url()));
    await page.route('**/api/knowledgebase/*/abandon', (r) => r.fulfill(ok(null)));

    await page.goto('/docs');

    const conflictRow = page.locator('tr', { hasText: '冲突文档D' });
    const abandonBtn = conflictRow.getByTitle('放弃此版本');

    // 点击“放弃此版本” → 弹出确认对话框 → 接受 → POST abandon
    page.once('dialog', (dialog) => {
      expect(dialog.message()).toContain('放弃此版本');
      dialog.accept();
    });
    await abandonBtn.click();
    const abandon = await abandonReq;
    expect(abandon.url()).toContain('/api/knowledgebase/13/abandon');
  });
});
