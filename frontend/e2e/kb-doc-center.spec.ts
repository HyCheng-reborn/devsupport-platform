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
      kbItem({ id: 13, name: '冲突文档D', vectorStatus: 'CONFLICT', active: true, documentKey: 'dkD', versionNo: 2, versionConflict: true, conflictReason: 'SAME_VERSION_DIFFERENT_CONTENT' }),
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

// ========== ADOPTING / ABANDONED 状态展示 ==========

test.describe('阶段 2 文档中心 ADOPTING/ABANDONED 状态', () => {
  test('ADOPTING 状态显示“采用中”徽标，无 adopt/abandon 按钮', async ({ page }) => {
    await page.route('**/api/knowledgebase/stats', (r) =>
      r.fulfill(ok({ totalCount: 1, totalQuestionCount: 0, totalAccessCount: 0, completedCount: 0, processingCount: 1 })));
    await page.route('**/api/knowledgebase/categories', (r) => r.fulfill(ok([])));
    await page.route('**/api/knowledgebase/services', (r) => r.fulfill(ok([])));
    await page.route('**/api/knowledgebase/environments', (r) => r.fulfill(ok([])));
    await page.route('**/api/knowledgebase/list**', (r) =>
      r.fulfill(ok([
        kbItem({ id: 20, name: '采用中文档E', vectorStatus: 'ADOPTING', active: true }),
      ])));

    await page.goto('/docs');

    const row = page.locator('tr', { hasText: '采用中文档E' });

    // “采用中”状态文本可见（使用 exact 匹配避免命中行名）
    await expect(row.getByText('采用中', { exact: true })).toBeVisible();

    // 不应有 adopt/abandon 按钮
    await expect(row.getByTitle('采用此版本')).toHaveCount(0);
    await expect(row.getByTitle('放弃此版本')).toHaveCount(0);

    // 不应有重新向量化按钮
    await expect(row.getByTitle('重新向量化')).toHaveCount(0);
  });

  test('ABANDONED 状态显示“已放弃”徽标，无 revectorize 按钮', async ({ page }) => {
    await page.route('**/api/knowledgebase/stats', (r) =>
      r.fulfill(ok({ totalCount: 1, totalQuestionCount: 0, totalAccessCount: 0, completedCount: 0, processingCount: 0 })));
    await page.route('**/api/knowledgebase/categories', (r) => r.fulfill(ok([])));
    await page.route('**/api/knowledgebase/services', (r) => r.fulfill(ok([])));
    await page.route('**/api/knowledgebase/environments', (r) => r.fulfill(ok([])));
    await page.route('**/api/knowledgebase/list**', (r) =>
      r.fulfill(ok([
        kbItem({ id: 21, name: '已放弃文档F', vectorStatus: 'ABANDONED', active: true }),
      ])));

    await page.goto('/docs');

    const row = page.locator('tr', { hasText: '已放弃文档F' });

    // “已放弃”状态文本可见（使用 exact 匹配避免命中行名）
    await expect(row.getByText('已放弃', { exact: true })).toBeVisible();

    // 不应有重新向量化按钮（仅 FAILED 状态有）
    await expect(row.getByTitle('重新向量化')).toHaveCount(0);

    // 不应有 adopt/abandon 按钮
    await expect(row.getByTitle('采用此版本')).toHaveCount(0);
    await expect(row.getByTitle('放弃此版本')).toHaveCount(0);
  });

  test('CONFLICT 状态显示 adopt/abandon 按钮', async ({ page }) => {
    await page.route('**/api/knowledgebase/stats', (r) =>
      r.fulfill(ok({ totalCount: 1, totalQuestionCount: 0, totalAccessCount: 0, completedCount: 0, processingCount: 0 })));
    await page.route('**/api/knowledgebase/categories', (r) => r.fulfill(ok([])));
    await page.route('**/api/knowledgebase/services', (r) => r.fulfill(ok([])));
    await page.route('**/api/knowledgebase/environments', (r) => r.fulfill(ok([])));
    await page.route('**/api/knowledgebase/list**', (r) =>
      r.fulfill(ok([
        kbItem({ id: 22, name: '冲突文档G', vectorStatus: 'CONFLICT', active: true, versionConflict: true, conflictReason: 'SAME_VERSION_DIFFERENT_CONTENT' }),
      ])));

    await page.goto('/docs');

    const row = page.locator('tr', { hasText: '冲突文档G' });

    // “版本冲突”状态文本可见（使用 first 避免命中徽标和状态列两处）
    await expect(row.getByText('版本冲突').first()).toBeVisible();

    // adopt/abandon 按钮可见
    await expect(row.getByTitle('采用此版本')).toBeVisible();
    await expect(row.getByTitle('放弃此版本')).toBeVisible();
  });

  test('adopt API 调用后，状态从 CONFLICT 变为 ADOPTING', async ({ page }) => {
    let listCallCount = 0;

    await page.route('**/api/knowledgebase/stats', (r) =>
      r.fulfill(ok({ totalCount: 1, totalQuestionCount: 0, totalAccessCount: 0, completedCount: 0, processingCount: 0 })));
    await page.route('**/api/knowledgebase/categories', (r) => r.fulfill(ok([])));
    await page.route('**/api/knowledgebase/services', (r) => r.fulfill(ok([])));
    await page.route('**/api/knowledgebase/environments', (r) => r.fulfill(ok([])));

    // 前两次 list 返回 CONFLICT（React strict mode 可能双调用），后续返回 ADOPTING
    await page.route('**/api/knowledgebase/list**', (r) => {
      listCallCount++;
      if (listCallCount <= 2) {
        return r.fulfill(ok([
          kbItem({ id: 23, name: '冲突转采用文档H', vectorStatus: 'CONFLICT', active: true, versionConflict: true, conflictReason: 'SAME_VERSION_DIFFERENT_CONTENT' }),
        ]));
      }
      return r.fulfill(ok([
        kbItem({ id: 23, name: '冲突转采用文档H', vectorStatus: 'ADOPTING', active: true, versionConflict: false }),
      ]));
    });

    await page.route('**/api/knowledgebase/*/adopt', (r) => r.fulfill(ok(null)));

    await page.goto('/docs');

    // 初始状态：CONFLICT
    const row = page.locator('tr', { hasText: '冲突转采用文档H' });
    await expect(row.getByText('版本冲突').first()).toBeVisible();

    // 点击 adopt
    page.once('dialog', (dialog) => {
      expect(dialog.message()).toContain('采用此版本');
      dialog.accept();
    });
    await row.getByTitle('采用此版本').click();

    // adopt 后列表刷新，状态变为 ADOPTING
    await expect(row.getByText('采用中', { exact: true })).toBeVisible({ timeout: 5000 });
    // CONFLICT 徽标不再显示
    await expect(row.getByText('版本冲突')).toHaveCount(0);
  });
});
