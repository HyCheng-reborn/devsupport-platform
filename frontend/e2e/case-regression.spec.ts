import { expect, test, type Page, type Route } from '@playwright/test';

/**
 * 案例回归评测 E2E（纯 mock，无后端、无付费 LLM/Embedding）。
 *
 * 覆盖 EvalResultsPage 内嵌的 CaseRegressionPanel 关键用户流程与状态：
 * 回归项列表 / 运行回归（成功·失败）/ 历史运行列表 / 运行详情，
 * 以及 CaseDetailPage 的「已纳入回归评测」徽标。
 *
 * 说明：这是 Playwright + page.route 的前端 mock E2E，不连接真实后端、
 * 不代表真实端到端链路；后端 /api/eval/regression/* 由集成测试单独覆盖。
 */

// ── 类型（对齐 src/types/eval.ts） ────────────────────

interface RegressionItemDTO {
  id: number;
  caseId: number;
  caseTitle: string;
  caseStatus: string;
  query: string;
  expectedEvidence: string[];
  keyPoints: string[];
  embeddingModel: string;
  embeddingDimension: number;
  active: boolean;
}

interface TopKSnapshotEntry {
  evidenceId: string;
  content: string;
  score: number;
}

interface RegressionResultDTO {
  id: number;
  itemId: number;
  caseTitle: string;
  passed: boolean;
  retrievedEvidenceIds: string[];
  matchedKeyPoints: string[];
  missingKeyPoints: string[];
  topKSnapshot: TopKSnapshotEntry[];
  failureReason: string | null;
}

interface RegressionRunSummaryDTO {
  id: number;
  startedAt: string;
  finishedAt: string | null;
  totalItems: number;
  passed: number;
  failed: number;
  skipped: number;
  triggerSource: string;
  embeddingModel: string;
  status: 'RUNNING' | 'COMPLETED';
}

interface RegressionRunDetailDTO extends RegressionRunSummaryDTO {
  results: RegressionResultDTO[];
}

// ── Result<T> 包装工具 ────────────────────────────────

function ok(data: unknown): Parameters<Route['fulfill']>[0] {
  return { contentType: 'application/json', body: JSON.stringify({ code: 200, message: 'success', data }) };
}

function err(code: number, message: string): Parameters<Route['fulfill']>[0] {
  return { contentType: 'application/json', body: JSON.stringify({ code, message, data: null }) };
}

// ── 稳定的 mock 数据 ──────────────────────────────────

const ITEMS: RegressionItemDTO[] = [
  {
    id: 11,
    caseId: 1,
    caseTitle: '支付网关超时排查',
    caseStatus: 'PUBLISHED',
    query: '支付网关频繁超时应该如何排查',
    expectedEvidence: ['ev-pay-01', 'ev-pay-02'],
    keyPoints: ['检查连接池配置', '确认超时阈值'],
    embeddingModel: 'text-embedding-v3',
    embeddingDimension: 1024,
    active: true,
  },
  {
    id: 12,
    caseId: 2,
    caseTitle: '数据库连接池耗尽',
    caseStatus: 'PUBLISHED',
    query: '数据库连接池耗尽怎么处理',
    expectedEvidence: ['ev-db-01'],
    keyPoints: ['扩大连接池上限', '排查连接泄漏'],
    embeddingModel: 'text-embedding-v3',
    embeddingDimension: 1024,
    active: false,
  },
];

const RUNS: RegressionRunSummaryDTO[] = [
  {
    id: 101,
    startedAt: '2026-01-02T10:00:00Z',
    finishedAt: '2026-01-02T10:05:00Z',
    totalItems: 2,
    passed: 2,
    failed: 0,
    skipped: 0,
    triggerSource: 'MANUAL',
    embeddingModel: 'text-embedding-v3',
    status: 'COMPLETED',
  },
  {
    id: 100,
    startedAt: '2026-01-01T09:00:00Z',
    finishedAt: null,
    totalItems: 2,
    passed: 1,
    failed: 0,
    skipped: 1,
    triggerSource: 'MANUAL',
    embeddingModel: 'text-embedding-v3',
    status: 'RUNNING',
  },
];

// itemId=11 命中期望证据 ev-pay-01（passed）；itemId=12 未命中 ev-db-01（failed）
const RUN_DETAIL_101: RegressionRunDetailDTO = {
  ...RUNS[0],
  results: [
    {
      id: 1001,
      itemId: 11,
      caseTitle: '支付网关超时排查',
      passed: true,
      retrievedEvidenceIds: ['ev-pay-01', 'ev-noise-99'],
      matchedKeyPoints: ['检查连接池配置', '确认超时阈值'],
      missingKeyPoints: [],
      topKSnapshot: [
        { evidenceId: 'ev-pay-01', content: '支付网关超时通常由连接池耗尽引起，应先检查连接池水位。', score: 0.9123 },
      ],
      failureReason: null,
    },
    {
      id: 1002,
      itemId: 12,
      caseTitle: '数据库连接池耗尽',
      passed: false,
      retrievedEvidenceIds: ['ev-noise-77'],
      matchedKeyPoints: ['扩大连接池上限'],
      missingKeyPoints: ['排查连接泄漏'],
      topKSnapshot: [
        { evidenceId: 'ev-noise-77', content: '与数据库连接池无关的检索噪声内容。', score: 0.4211 },
      ],
      failureReason: '未命中期望证据 ev-db-01，且缺失关键要点：排查连接泄漏',
    },
  ],
};

// 运行成功时 POST /runs 返回的新一次运行详情（全通过，通过率 100%）
const POST_RUN_DETAIL: RegressionRunDetailDTO = {
  id: 200,
  startedAt: '2026-01-03T08:00:00Z',
  finishedAt: '2026-01-03T08:04:00Z',
  totalItems: 2,
  passed: 2,
  failed: 0,
  skipped: 0,
  triggerSource: 'MANUAL',
  embeddingModel: 'text-embedding-v3',
  status: 'COMPLETED',
  results: [
    {
      id: 2001,
      itemId: 11,
      caseTitle: '支付网关超时排查',
      passed: true,
      retrievedEvidenceIds: ['ev-pay-01'],
      matchedKeyPoints: ['检查连接池配置', '确认超时阈值'],
      missingKeyPoints: [],
      topKSnapshot: [{ evidenceId: 'ev-pay-01', content: '支付网关超时排查快照。', score: 0.95 }],
      failureReason: null,
    },
    {
      id: 2002,
      itemId: 12,
      caseTitle: '数据库连接池耗尽',
      passed: true,
      retrievedEvidenceIds: ['ev-db-01'],
      matchedKeyPoints: ['扩大连接池上限', '排查连接泄漏'],
      missingKeyPoints: [],
      topKSnapshot: [{ evidenceId: 'ev-db-01', content: '数据库连接池耗尽处置快照。', score: 0.88 }],
      failureReason: null,
    },
  ],
};

// ── mock 路由器 ───────────────────────────────────────

interface RegressionMockOptions {
  items?: RegressionItemDTO[];
  runs?: RegressionRunSummaryDTO[];
  /** runId -> 详情，用于 GET runs/{id} */
  runDetails?: Record<number, RegressionRunDetailDTO>;
  /** POST /runs 成功返回的详情；与 postError 二选一 */
  postRun?: RegressionRunDetailDTO;
  /** POST /runs 失败：{ code, message } */
  postError?: { code: number; message: string };
  /** POST /runs 响应延迟（毫秒），用于观察运行中禁用态 */
  postDelayMs?: number;
}

/**
 * 用单一 catch-all 路由拦截 /api/eval/regression/**，按 pathname + method 分派，
 * 避免 runs 与 runs/{id} 的 glob 歧义。
 */
async function mockRegressionApis(page: Page, opts: RegressionMockOptions = {}) {
  const items = opts.items ?? ITEMS;
  const runs = opts.runs ?? RUNS;
  const runDetails = opts.runDetails ?? { 101: RUN_DETAIL_101 };

  await page.route('**/api/eval/regression/**', async (route) => {
    const req = route.request();
    const url = new URL(req.url());
    const path = url.pathname;
    const method = req.method();

    if (path.endsWith('/items')) {
      return route.fulfill(ok(items));
    }

    if (path.endsWith('/runs')) {
      if (method === 'POST') {
        if (opts.postDelayMs) {
          await new Promise((resolve) => setTimeout(resolve, opts.postDelayMs));
        }
        if (opts.postError) {
          return route.fulfill(err(opts.postError.code, opts.postError.message));
        }
        return route.fulfill(ok(opts.postRun ?? POST_RUN_DETAIL));
      }
      // GET 列表
      return route.fulfill(ok(runs));
    }

    // GET runs/{id}
    const idMatch = path.match(/\/runs\/(\d+)$/);
    if (idMatch) {
      const id = Number(idMatch[1]);
      const detail = runDetails[id];
      if (detail) {
        return route.fulfill(ok(detail));
      }
      return route.fulfill(err(404, '运行记录不存在'));
    }

    return route.fulfill(ok(null));
  });
}

// ── 案例详情 mock（徽标验证） ─────────────────────────

function caseItem(over: Record<string, unknown>) {
  return {
    id: 1,
    title: '支付网关超时案例',
    problemDescription: '问题描述',
    rootCause: '根因',
    resolutionSteps: '解决步骤',
    resolutionResult: '已解决',
    affectedVersions: 'v1.0',
    environment: 'production',
    service: 'payment-service',
    aiGeneratedContent: null,
    userConfirmedContent: null,
    status: 'PUBLISHED',
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

// ── 测试 ──────────────────────────────────────────────

test.describe('案例回归评测 · 回归项列表', () => {
  test('有数据时正确渲染标题/状态/启用/模型/证据数/要点数', async ({ page }) => {
    await mockRegressionApis(page);
    await page.goto('/eval-results');

    // 面板标题可见（用 heading 角色，避免与页脚说明文案重名）
    await expect(page.getByRole('heading', { name: '案例回归评测' })).toBeVisible();

    // 回归项标题可见
    await expect(page.getByText('支付网关超时排查').first()).toBeVisible();
    await expect(page.getByText('数据库连接池耗尽').first()).toBeVisible();

    // 案例状态徽标（PUBLISHED -> 已发布）
    await expect(page.getByText('已发布').first()).toBeVisible();

    // active 徽标：启用 / 停用 各出现
    await expect(page.getByText('启用', { exact: true }).first()).toBeVisible();
    await expect(page.getByText('停用', { exact: true }).first()).toBeVisible();

    // embeddingModel + 维度
    await expect(page.getByText('text-embedding-v3 · 1024维').first()).toBeVisible();

    // 期望证据数 / 关键要点数（item 11：证据 2 / 要点 2）
    await expect(page.getByText('期望证据 2').first()).toBeVisible();
    await expect(page.getByText('关键要点 2').first()).toBeVisible();
  });

  test('空数据时展示友好空态提示', async ({ page }) => {
    await mockRegressionApis(page, { items: [], runs: [] });
    await page.goto('/eval-results');

    await expect(page.getByText('暂无回归项')).toBeVisible();
    await expect(page.getByText('发布案例后会自动纳入回归评测数据集')).toBeVisible();
    await expect(page.getByText('暂无运行历史')).toBeVisible();
  });
});

test.describe('案例回归评测 · 运行回归', () => {
  test('成功：运行中禁用 → 通过率提示 → 直接展示本次运行详情', async ({ page }) => {
    await mockRegressionApis(page, {
      postRun: POST_RUN_DETAIL,
      postDelayMs: 600,
      // 运行完成后历史列表刷新，展示新增的本次运行
      runs: [{ ...POST_RUN_DETAIL, results: undefined } as unknown as RegressionRunSummaryDTO, ...RUNS],
    });
    await page.goto('/eval-results');

    const idleRunButton = page.getByRole('button', { name: '运行回归', exact: true });
    await expect(idleRunButton).toBeVisible();

    await idleRunButton.click();

    // 运行中：按钮禁用且文案切换为「运行中…」（历史行的「运行中」徽标无省略号，可区分）
    const runningButton = page.getByRole('button', { name: '运行中…', exact: true });
    await expect(runningButton).toBeVisible();
    await expect(runningButton).toBeDisabled();

    // 成功后：通过率提示条
    await expect(page.getByText('回归运行完成：通过率 100.0%（2/2）')).toBeVisible();

    // 直接展示本次运行详情区块
    await expect(page.getByText('运行详情')).toBeVisible();
    await expect(page.getByText('触发来源：MANUAL')).toBeVisible();

    // 历史列表刷新后出现本次运行（通过率 100.0%）
    await expect(page.getByText('通过率 100.0%').first()).toBeVisible();
  });

  test('失败：POST 返回 code=13001 中文错误 → 展示错误提示条', async ({ page }) => {
    await mockRegressionApis(page, {
      items: [],
      postError: { code: 13001, message: '无可用回归项，请先发布案例后再运行回归评测' },
    });
    await page.goto('/eval-results');

    await page.getByRole('button', { name: '运行回归', exact: true }).click();

    // 中文错误提示条可见
    await expect(page.getByText('无可用回归项，请先发布案例后再运行回归评测')).toBeVisible();

    // 不应展示成功提示
    await expect(page.getByText(/回归运行完成/)).toHaveCount(0);
  });
});

test.describe('案例回归评测 · 历史运行列表', () => {
  test('渲染多行（状态/通过率/明细）', async ({ page }) => {
    await mockRegressionApis(page);
    await page.goto('/eval-results');

    // COMPLETED / RUNNING 状态标签
    await expect(page.getByText('已完成').first()).toBeVisible();
    await expect(page.getByText('运行中').first()).toBeVisible();

    // 通过率与 passed/total：run 101 -> 100.0% 2/2；run 100 -> 50.0% 1/2
    await expect(page.getByText('通过率 100.0%').first()).toBeVisible();
    await expect(page.getByText('2/2').first()).toBeVisible();
    await expect(page.getByText('通过率 50.0%').first()).toBeVisible();
    await expect(page.getByText('1/2').first()).toBeVisible();

    // passed/failed/skipped 明细（run 100 的 skipped=1）
    await expect(page.getByText('运行历史')).toBeVisible();
  });

  test('点击某行触发 GET runs/{id} 并加载详情、选中高亮', async ({ page }) => {
    await mockRegressionApis(page, { runDetails: { 101: RUN_DETAIL_101 } });
    await page.goto('/eval-results');

    // 捕获详情请求
    const detailReq = page.waitForRequest(
      (r) => r.method() === 'GET' && /\/api\/eval\/regression\/runs\/101$/.test(new URL(r.url()).pathname),
    );

    // 点击 run 101 行（用通过率 100.0% 定位其所在按钮）
    const runRow = page.getByRole('button').filter({ hasText: '通过率 100.0%' }).first();
    await runRow.click();

    await detailReq;

    // 详情区块加载并展示逐项结果
    await expect(page.getByText('运行详情')).toBeVisible();
    await expect(page.getByText('支付网关超时排查').first()).toBeVisible();

    // 选中高亮：该行获得 primary 边框
    await expect(runRow).toHaveClass(/border-primary-400/);
  });
});

test.describe('案例回归评测 · 运行详情', () => {
  test.beforeEach(async ({ page }) => {
    await mockRegressionApis(page, { runDetails: { 101: RUN_DETAIL_101 } });
    await page.goto('/eval-results');
    // 打开 run 101 详情
    await page.getByRole('button').filter({ hasText: '通过率 100.0%' }).first().click();
    await expect(page.getByText('运行详情')).toBeVisible();
  });

  test('逐项结果：证据命中/未命中、要点匹配、失败原因', async ({ page }) => {
    // 证据命中（item 11 命中 ev-pay-01）与未命中（item 12 未命中 ev-db-01）
    await expect(page.getByText('证据命中', { exact: true }).first()).toBeVisible();
    await expect(page.getByText('证据未命中', { exact: true }).first()).toBeVisible();

    // 检索到的证据 ID 渲染（命中项高亮由「证据命中」徽标体现）
    await expect(page.getByText('ev-pay-01').first()).toBeVisible();

    // 要点匹配计数：passed 项 2/2，failed 项 1/2
    await expect(page.getByText('要点 2/2').first()).toBeVisible();
    await expect(page.getByText('要点 1/2').first()).toBeVisible();

    // 缺失要点标签与内容
    await expect(page.getByText('缺失要点：').first()).toBeVisible();
    await expect(page.getByText('排查连接泄漏').first()).toBeVisible();

    // 失败原因告警块
    await expect(page.getByText('未命中期望证据 ev-db-01，且缺失关键要点：排查连接泄漏')).toBeVisible();
  });

  test('展开 topKSnapshot 显示 evidenceId/content/score', async ({ page }) => {
    // 展开第一个「召回快照」
    await page.getByRole('button', { name: /召回快照/ }).first().click();

    // 快照内容：evidenceId（#1 ev-pay-01）、score、content
    await expect(page.getByText('Top-K 召回快照：')).toBeVisible();
    await expect(page.getByText('#1 ev-pay-01')).toBeVisible();
    await expect(page.getByText('score 0.9123')).toBeVisible();
    await expect(page.getByText('支付网关超时通常由连接池耗尽引起，应先检查连接池水位。')).toBeVisible();
  });
});

test.describe('案例详情页 · 回归评测徽标', () => {
  test('PUBLISHED 案例展示「已纳入回归评测」徽标', async ({ page }) => {
    await page.route('**/api/cases/1', (r) =>
      r.fulfill(ok(caseItem({ id: 1, title: '支付网关超时案例', status: 'PUBLISHED' }))));

    await page.goto('/cases/1');

    await expect(page.getByText('支付网关超时案例')).toBeVisible();
    await expect(page.getByText('已纳入回归评测')).toBeVisible();
  });

  test('非 PUBLISHED 案例不展示徽标', async ({ page }) => {
    await page.route('**/api/cases/2', (r) =>
      r.fulfill(ok(caseItem({ id: 2, title: '草稿案例', status: 'DRAFT' }))));

    await page.goto('/cases/2');

    await expect(page.getByText('草稿案例')).toBeVisible();
    await expect(page.getByText('已纳入回归评测')).toHaveCount(0);
  });
});
