**2026-10-02 -- DevSupport Phase 1 凭据处置最终验证**
- 改了什么：
  - application.yml 移除 POSTGRES_PASSWORD 默认值 `changeme`，改为 `${POSTGRES_PASSWORD:?POSTGRES_PASSWORD environment variable is required}`，缺少时启动失败
  - TCP 认证验证：通过容器 psql 经 localhost:5432 TCP 连接，新密码成功（exit 0），错误密码被拒绝（exit 2）
  - 核查文档表述与实际证据一致：区分 docker exec 本地连接 vs TCP 网络连接
- 为什么：凭据处置最终验收，确保 application.yml 不再有任何硬编码默认密码
- 验证：
  - TCP 连接新密码 exit 0（`SELECT 'auth_ok'`），错误密码 exit 2（`password authentication failed`）
  - `./gradlew :app:compileJava` exit 0
  - `cd frontend && pnpm run test:kb-filter` 9 pass / 0 fail / exit 0
- 尚未验证的真实行为：
  - Git 历史中的旧密码仍可通过历史提交读取（不做强推）
  - 完整浏览器端到端交互

**2026-10-02 -- DevSupport Phase 1 凭据安全收口**
- 改了什么：
  - 轮换本地 PostgreSQL 凭据（影响范围：本地开发环境）
  - 移除 docker-compose.dev.yml、docker-compose.yml、application.yml 中的硬编码密码默认值
  - 修正 PROJECT_PROGRESS.md skipped 解释
- 为什么：Git 历史中存在明文密码，需轮换并移除硬编码
- 验证：新凭据连接成功；后端测试 exit 0（485/0/57）；前端筛选测试 exit 0（9/0）
- 尚未验证的真实行为：
  - Git 历史中的旧密码仍可通过历史提交读取（不做强推）
  - 其他本地实例是否使用相同密码（已检查，仅本地开发环境）

**2026-10-02 -- DevSupport Phase 1 批次 B 最终定点收口**
- 改了什么：
  - 管理页直接调用已测试的 `applyFilters()`，移除重复 service/environment 筛选实现
  - select 编码前缀从 `value:` 改为 `val:`，避免真实标签以 `value:` 开头时误判
  - 检查 `.gitignore` 规则无误伤（`*.tar.gz`/`*.zip`/`interview-guide_*.md`/`status.txt`/`__review_diff.txt` 均合理）
  - 修正 PROJECT_PROGRESS.md skipped 解释为有证据支持的表述
- 为什么：批次 B 最终收口，确保组件调用已测试纯函数、文档表述准确
- 验证：
  - `./gradlew :app:test --no-daemon` exit 0（485 tests / 0 failures / 57 skipped）
  - `cd frontend && pnpm run test:kb-filter` 9 pass / 0 fail
  - `cd frontend && pnpm run build` exit 0
  - `git diff --check` exit 0
- 尚未验证的真实行为：
  - 前端筛选端到端交互（需启动完整服务）
  - Repository 筛选查询在真实 PostgreSQL 上的行为

**2026-10-02 -- DevSupport Phase 1 批次 B 紧急定点收尾**
- 改了什么：
  - 改进“未分类”状态表示：前端筛选从字符串字面量 `'\0UNCATEGORIZED'` 改为类型安全的 `FilterState` 联合类型（`'all' | 'unclassified' | { type: 'value'; value: string }`），彻底消除哨兵值与真实标签冲突的可能
  - 抽取筛选纯函数 `applyFilters` 到 `frontend/src/utils/kbFilter.ts`，新增 9 条 Node 测试覆盖（含 `\0UNCATEGORIZED` 真实标签不混淆、service+environment 交集、空列表）
  - 修正 `KnowledgeBaseUploadServiceTest` 中 `serviceExactly100` 和 `nullServiceAndEnvironment` 两个用例：从 try/catch 吞异常改为打桩完整上传链路 + 明确 `verify(persistenceService)` 断言
  - 新增 `rejectLongService` 用例：断言超长拒绝时 fileValidationService/fileHashService/storageService/persistenceService/vectorizeStreamProducer 均无交互
- 为什么：批次 B 最终收尾，消除哨兵值语义隐患、确保测试用例真实验证成功路径
- 验证：
  - `./gradlew :app:test --no-daemon` exit 0（485 tests / 0 failures / 57 skipped）
  - `cd frontend && pnpm run build` exit 0
  - `node --test src/utils/kbFilter.test.ts` 9 pass / 0 fail
  - `git diff --check` exit 0
- 尚未验证的真实行为：
  - 前端筛选端到端交互（需启动完整服务）
  - Repository 筛选查询在真实 PostgreSQL 上的行为

**2026-10-02 -- DevSupport Phase 1 批次 B 最终定点修复**
- 改了什么：
  - 后端 `KnowledgeBaseUploadService.uploadKnowledgeBase` 在 S3/fileHash/Redis 调用之前校验 service≤100/environment≤50，超长抛 BusinessException
  - 前端 `FileUploadCard.tsx` service/environment 输入框添加 maxLength={100}/maxLength={50}
  - 管理页搜索改为同时匹配 name 和 originalFilename（不区分大小写）
  - “未分类”哨兵值从 `__uncategorized__` 改为 `\0UNCATEGORIZED`（NULL 字符前缀，避免用户输入冲突）
  - 新增 `KnowledgeBaseUploadServiceTest` 4 个测试用例（service超长/environment超长/边界100/null兼容）
- 为什么：批次 B 最终补证，确保上传前校验、搜索语义完整、哨兵值安全
- 验证：
  - `./gradlew :app:test --no-daemon` exit 0（485 tests / 0 failures / 57 skipped）
  - `cd frontend && pnpm run build` exit 0
  - `git diff --check` exit 0
- 尚未验证的真实行为：
  - 前端筛选端到端交互（需启动完整服务）
  - Repository 筛选查询在真实 PostgreSQL 上的行为

**2026-10-02 -- DevSupport Phase 1 批次 B 定点修复**
- 改了什么：
  - 修复管理页 service/environment 筛选组合逻辑（“未分类”哨兵值 `__uncategorized__` 不入数据库，纯前端过滤 NULL）
  - 确保搜索/category/sortBy 与 service/environment 可组合（所有筛选改为客户端 useMemo 组合，不再互斥清除）
  - 新增 updateLabels 测试 6 个（正常保存/空白转NULL/清空/不存在ID/service超长/environment超长）
  - 后端 updateLabels 新增长度验证（service≤100, environment≤50）
  - 前端标签输入框添加 maxLength 属性 + 提交前长度检查
- 为什么：批次 B 补证，确保筛选逻辑正确、测试覆盖完整
- 验证：
  - `./gradlew :app:test --no-daemon` exit 0（482/0/46）
  - `cd frontend && pnpm run build` exit 0
  - `git diff --check` 无尾随空格错误
- 尚未验证的真实行为：
  - 前端筛选端到端交互（需启动完整服务）
  - Repository 筛选查询在真实 PostgreSQL 上的行为

**2026-10-02 -- DevSupport Phase 1 批次 B 实施（上传 API + 管理页面标签）**
- 改了什么：
  - 后端：上传 API 增加 service/environment 可选参数；列表 API 增加筛选；新增 GET /services、GET /environments、PUT /{id}/labels 端点；重复上传响应包含已有 KB 的 service/environment
  - 前端：管理页面增加服务/环境筛选下拉和行内编辑；上传页面增加标签输入；问答页知识库选择列表显示标签
  - 测试：新增 14 个测试（PersistenceService 6 + ListService 8），全量 461 tests / 0 failures
- 为什么：Phase 1 批次 B 落地，贯通 service/environment 标签的上传/展示/筛选/编辑
- 验证：`./gradlew :app:test --no-daemon` exit 0（461/0/0）；`cd frontend && pnpm run build` exit 0
- 尚未验证的真实行为：
  - Repository 筛选查询在真实 PostgreSQL 上的行为（H2 已验证）
  - 前端标签编辑端到端交互（需启动完整服务）
  - 批次 C：来源快照 service/environment 字段、前端来源面板标签展示

**2026-10-02 -- DevSupport Phase 1 批次 A 定点补证（代码+文档）**
- 改了什么：
  - 修复 KnowledgeBaseRepositoryTest：删除 mock 非空断言，改为集成测试
  - 修正设计文档：重复上传 accessCount 行为、来源快照删除后展示规则
- 为什么：批次 A 补证，确保测试真实运行、设计文档与实现一致
- 验证：
  - `./gradlew :app:test --no-daemon` exit 0（GRADLE_USER_HOME=C:\GradleHome）
  - 全量 462 tests / 0 failures / 46 skipped
  - Repository 集成测试 5/5 通过（@DataJpaTest + H2）
  - Flyway V20261001 在隔离 PostgreSQL（pgvector/pgvector:pg16 临时容器 5433 端口）验证通过：7/7 迁移成功、列/索引正确、JPA validate 通过、新行写入正常
- 尚未验证的真实行为：
  - Repository JPQL 查询在真实 PostgreSQL 上的行为（H2 已验证，PostgreSQL 未单独验证）
  - 重复上传响应扩展（批次 B 待办）

**2026-10-01 -- DevSupport Phase 1 批次 A 实施 + 设计文档修正（代码+文档）**
- 改了什么：
  - 新增 Flyway 迁移 V20261001（service/environment 列 + 索引）
  - 更新 KnowledgeBaseEntity 映射 + KnowledgeBaseRepository 查询
  - 新增 Entity 和 Repository 测试
  - 修正设计文档：批次验收表述、来源快照降级、fallback 假设标注
- 为什么：Phase 1 批次 A 落地，同步修正设计文档与实现一致
- 验证：`./gradlew :app:compileJava` exit 0；`./gradlew :app:test` exit 1（GradleWorkerMain 启动故障；设置纯 ASCII 的 GRADLE_USER_HOME 后 worker 能启动、测试能执行；具体作用因素尚未隔离确认）
- 尚未验证的真实行为：Flyway 迁移（需真实 PostgreSQL）、单元测试（需修复 Gradle 环境）、重复上传响应扩展（批次 B）

**2026-10-01 -- DevSupport Phase 1 批次 A：service/environment 数据模型**
- 改了什么：(1) 新增 Flyway 迁移 `V20261001__add_service_environment.sql`，为 `knowledge_bases` 表添加 `service VARCHAR(100)` 和 `environment VARCHAR(50)` 列及索引；(2) `KnowledgeBaseEntity` 新增 `service`/`environment` 字段及 getter/setter，`@Table(indexes=...)` 加入新索引；(3) `KnowledgeBaseRepository` 新增 4 个查询方法（findByService、findByServiceAndEnvironment、findAllServices、findAllEnvironments）；(4) 新增 `KnowledgeBaseEntityTest` 和 `KnowledgeBaseRepositoryTest` 单元测试
- 为什么：DevSupport Phase 1 批次 A，为知识库新增服务/环境组织标签的数据模型层，不涉及业务逻辑变更
- 验证：`./gradlew :app:compileJava` 退出码 0（BUILD SUCCESSFUL）；`./gradlew :app:test --no-daemon` 退出码 1（GradleWorkerMain 启动故障；设置纯 ASCII 的 GRADLE_USER_HOME 后 worker 能启动、测试能执行；具体作用因素尚未隔离确认）
- 尚未验证的真实行为：(1) Flyway 迁移需真实 PostgreSQL 验证（当前无可用实例）；(2) 测试因 Gradle worker 崩溃无法执行，需修复 Gradle/Java 环境后重试

**2026-10-01 -- DevSupport Phase 1 设计文档第三轮修订（仅文档）**
- 改了什么：按 Codex 第二轮复核 5 个阻塞点修订 `devsupport-phase1-design.md`：(1) service/environment 定位为组织标签非检索隔离；(2) 迁移不设 DEFAULT，旧行 NULL = 未分类；(3) 不支持同文件跨服务，重复上传返回提示；(4) 来源标签 = 提问时快照，SourceReference 新增字段；(5) fallback 允许存在，如实描述语义限制
- 为什么：消除过度承诺，确保每项决策可实现、可验证
- 验证：纯文档改动
- 尚未验证的真实行为：所有设计均未经真实环境验证

**2026-10-01 -- DevSupport Phase 1 设计文档第二轮修订（仅文档）**
- 改了什么：按 Codex 复核 5 点意见修订 `devsupport-phase1-design.md`：补全服务/环境筛选请求链、确定方案 C（显式通用范围）、明确 NULL 检索语义、定义来源快照策略、修正 Flyway 回退/S3 bucket/Controller 注入事实错误
- 为什么：方案尚不能进入批次 A，需补齐决策依据
- 验证：纯文档改动
- 尚未验证的真实行为：所有设计均未经真实环境验证

**2026-10-01 -- DevSupport Phase 1 设计文档（仅文档，不改代码）**
- 改了什么：新增 `devsupport-phase1-design.md`，覆盖可复用能力/缺口、用户流程、后端分层、RAG 路径、前端改动范围、命名决策、开发批次、P1-C 定位、反例审查
- 为什么：基于 HEAD 2cc5834 源码核实，为 Codex 复核提供设计依据
- 验证：纯文档，无编译/测试影响
- 尚未验证的真实行为：设计中的所有实现方案均未经过真实数据库/容器/API 验证

# 改动报告（增量记录）

> 本文件是「每次完成改动后」的简洁增量报告，按时间倒序追加。
> 与根目录 `PROJECT_PROGRESS.md`（跨对话任务进度事实源）互补：本文件只记「改了什么、为什么、验证结果与未验证项」，不复述完整方案。
> 说明：`/docs` 目录被 `.gitignore` 忽略，故本报告放在仓库根目录以确保被跟踪。
> 推送目标：远程 `devsupport`（`HyCheng-reborn/devsupport-platform`）；本地目录 `interview-guide`。

---

## 2026-10-01 — 复核状态同步：Codex 六次定点源码复核通过 db036fe（仅文档状态，不改代码/测试/配置/规则）

**基线**：`416c064`（本任务前 HEAD）
**状态**：仅同步 `PROJECT_PROGRESS.md` §17 与 `CHANGES.md` 的复核当前状态描述；未改业务代码/测试/构建配置/规则，未重跑测试。
**本轮核实的当前状态**（逐项）：
- P1 源码复核通过（Codex 六次定点源码复核通过 `db036fe`）。
- 独立干净 worktree 全量回归通过：**455 tests / 0 failures / 0 errors / 50 skipped**（分离 worktree + 清空 `test-results` + `--rerun` 真实执行，非 UP-TO-DATE，未用旧 XML 充数）。
- **同机同工具链**（hostname `MSI`、同一 `~/.gradle`、Gradle 9.6.1 + Temurin 25.0.4.1+1-LTS），**不是跨机对照**。
- 原工作副本 `GradleWorkerMain` 执行器启动故障**根因未定**（干净检出不复现仅提示与特定工作副本/守护态相关，不足以定性）。
- 真实 LLM / Embedding / 生产库及端到端 SSE **未验证**。
- 已知文本规则边界保留；后续如要改为小型二分类需**单独授权**。
**本轮改动**：同步 §17 四处“当前状态”描述（顶部状态行、“尚未验证”首条与末条、文末待办闸门），及 CHANGES 内旧条目的“待 Codex 六次复核/待办闸门”现状描述；消除与“六次复核已通过”的矛盾。`git diff --check` exit 0。

---

## 2026-10-01 — db036fe 独立干净检出全量回归补证（不改代码，仅验证记录）

**对象提交**：`db036fe498a3b3c03ca23ea87215467ce8062d19`（= §17 / HEAD）
**状态**：db036fe 定点源码复核通过；独立干净检出全量 `:app:test` 重新执行并通过；原机主工作副本执行器启动故障根因仍未确定。
**范围**：仅补齐验证证据与更新进度文档/CHANGES（本轮不改任何代码/构建配置、不禁用测试、不扩展 P1 文本规则）。

### 验证方式与环境（诚实区分）
- **独立干净检出**：对 db036fe 新建分离 `git worktree`（工作树 clean，HEAD=完整哈希），在该副本上重新编译与跑全量测试；事先清空 `app/build/test-results/test` 并用 `--rerun` 强制真实执行。
- **同机、非同“跨机”**：本验证与故障会话在同一物理机（hostname `MSI`）、同一 `~/.gradle`、同一工具链（Gradle 9.6.1 + Temurin 25.0.4.1+1-LTS）上完成，差异仅在工作副本。不宣称严格跨机对照；未替换 Gradle 发行版、未复制故障机缓存。
- **外部依赖**：未启动任何外部服务；不调真实 LLM / 付费 Embedding / 真实 L1 / 生产库（`:app:test` 默认排除 `real-eval`，H2 内存库，Redis/S3 打桩；Docker 不可用时 `RateLimitIntegrationTest` 自动禁用）。

### 结果（PowerShell 直取 `$LASTEXITCODE`）
- `:app:compileJava :app:compileTestJava --no-daemon` → COMPILE_EXIT=0，BUILD SUCCESSFUL。
- `:app:test --no-daemon --rerun` → FULL_EXIT=0，BUILD SUCCESSFUL；`:app:test` 实际执行（非 UP-TO-DATE）。
- 新生成 XML：94 个套件，**tests=455 / failures=0 / errors=0 / skipped=50**；日志中 `FAILED`/`GradleWorkerMain`/`ClassNotFoundException` 0 次 → 原 worker 启动故障在干净检出上**未复现**。
- 定点类与 §17 一致：`KnowledgeBaseQueryServiceTest` ResolveFinalStatus=31/0/0/0、AnswerQuestionStream=3/0/0/0；`RagChatControllerTest`=3/0/0/0。

### 尚未验证
- 未连真实 PostgreSQL / LLM / 付费 Embedding / L1 / 生产库跑端到端 SSE；本轮不宣称任何真实环境端到端已验证。
- 原机主工作副本执行器（`GradleWorkerMain`）故障根因仍未确定（干净检出不复现仅提示与特定工作副本/守护态相关，不足以定性）。Codex 六次定点源码复核已通过 `db036fe`（P1 源码复核通过）。

---

## 2026-10-01 — P1 定点修复：引用占位不作子句边界（修复 5b1d88b 确定性回归，Codex 六次源码复核通过）

**基线**：`5b1d88b`（本任务前 HEAD）
**状态**：P1 源码复核通过（Codex 六次定点源码复核通过 `db036fe`）；本轮全量 `:app:test` 在原始工作副本因 `GradleWorkerMain` 执行器启动故障未取得结果（根因未定），已在同机同工具链的独立干净检出上重新执行并通过（455 / 0 / 0 / 50）；真实 LLM / Embedding / 生产库 / 端到端 SSE 未验证。
**范围**：仅最终拒答判定（`isExplicitRefusal`/`maskQuotedSpans`/`CLAUSE_SPLIT`）+ 相关后端测试 + 进度文档。未动 `answerQuestionStream`、共享 `isNoResultLike`、探测窗口 `normalizeStreamOutput`、前端/P2、SSE 协议/事务/schema/来源组装/事件顺序补强；不调真实 LLM/付费 Embedding/真实 L1/生产库。

### 改了什么
- **把占位符 `□` 从 `CLAUSE_SPLIT` 移除**：§16 让 `□` 既作引用内联替换又被当子句终止符，在“引号内是资料名、拒答在引号外”时把 `无法根据□回答` / `未找到关于□的信息` 从中间切断→误判 COMPLETED。现在占位符仍为内联中性标记（切分前替换整段引用、屏蔽引用内拒答词、吸收引用内句号/换行、两侧不拼接），但不再作子句边界。未加关键词、未改窗口与否定/条件/时请护栏。
- 重新审视 §16 歧义测试 `无法结合“某些字段”回答也不奇怪`（原文本就含 `无法…回答`）：前提不成立，已换为无歧义反例 `“无法回答”这个提示通常表示模型连接异常…`（=COMPLETED）。
- 测试：`KnowledgeBaseQueryServiceTest` 新增 3 条失败回归（引用为资料名的外部 `无法…回答`/外部 `未找到…信息`/引用内含换行不干扰外部拒答，均 NO_RESULTS）+ 替换歧义用例；`RagChatControllerTest` 新增 `quotedResourceNameRefusalWithRealStatusJudgingClearsSources`（真实 resolveFinalStatus → NO_RESULTS、sources=[]、done 一致）。

### 为什么
Codex 五次复核：`5b1d88b` 两条确定性反例（有检索文档时，不命中共享 `isNoResultLike`）被误判 COMPLETED；占位符不应同时充当子句终止符。

### 验证（区分两件事，PowerShell 直取 `$LASTEXITCODE`）
- 定点（此前一次、执行器健康时）：compileJava+compileTestJava exit 0；`:app:test`（两测试类）GRADLE_EXIT=0、**BUILD SUCCESSFUL in 23s**；resolveFinalStatus 套件 tests=31/0/0/0、成功路径 6/0、answerQuestionStream 契约 3/0、顶层控制器 3/0（日志 `build\p1r4-postfix.log`）。`git diff --check` exit 0。
- 全量 `:app:test --no-daemon`：**本轮未取到有效结果（执行器启动故障，非通过/失败）**。多次尝试 worker JVM 在 bootstrap 即报 `ClassNotFoundException: ...GradleWorkerMain`、退出 1；受控恢复（`--stop` 掉残留 daemon，内存回到空闲 3.5GB/提交余量 8.86GB）后最后一次单跑（19:24:03–19:25:45，102s，FULL_EXIT=1）仍复现，`Task :app:test` 已执行但本次 XML 数为 0。排查：`gradle-worker.jar` 完好且含该类、失败/成功 worker 的 `-cp` argfile MD5 相同、无资源耗尽事件/hs_err、堆仅 512m → **释放内存后仍复现，尚无证据认定内存不足是直接原因**；也不能用 jar 缺类解释，**根因未定**，已按指示停止重试。

### 尚未验证的真实行为
- 本轮修复后的全量回归未在本机执行器上重新取得 BUILD SUCCESSFUL；只有定点/相关套件此前离线通过。
- 未连真实 LLM/付费 Embedding/真实 L1/生产库跑端到端 SSE；引用外拒答跨更多子句、多语言撇号、内联占位压缩间隔导致的过度识别等变体未经样本验证。
- 跨子句无紧邻“时”的条件仍可能判拒答；ASCII 单引号成对可能误屏蔽；否定词远离情态词可能误判；建议后续用极小二分类替代（需单独授权）。

---

## 2026-10-01 — P1 定点修复：引用屏蔽前置 / ASCII 单引号 / “……时请……”条件作用域（修复 8d6f367 确定性回归，待 Codex 五次复核）

**基线**：`8d6f367`（本任务前 HEAD）
**状态**：代码已写 + 本地/离线测试通过；**待 Codex 五次复核**（未提前写成复核通过）。
**范围**：仅改 `KnowledgeBaseQueryService.isExplicitRefusal` 最终拒答判定 + 相关后端测试 + 进度文档。未动共享 `answerQuestionStream`、`isNoResultLike`、探测窗口 `normalizeStreamOutput`、前端/P2、SSE 协议/事务/schema/来源组装/事件顺序补强；Controller 未重新注入 Repository。

### 改了什么
- 引用屏蔽前置：新增 `maskQuotedSpans`，在句子/子句切分之前用占位符 `□`（`QUOTE_MASK`，已加入 `CLAUSE_SPLIT` 作边界）替换整段引用；引用内句号/换行不再提前截断句子，也不会拼接出原文不存在的拒答。`containsGenuineRefusal` 不再在内部做 replaceAll。
- `QUOTED_SPAN` 扩充：新增 ASCII 单引号 `'[^'\n\r]*'`（不跨换行），ASCII 双引号改为允许跨换行 `"[^"]*"`；未闭合引号不匹配。
- 新增 `isConditionalTroubleshooting` + `CONDITIONAL_REQUEST_MARKER`：拒答后紧邻 `时` 且同子句内有请求/指令词时视为“……时请……”条件排查，作用域只到该处拒答，不整句一刀切。
- 测试：`KnowledgeBaseQueryServiceTest` 新增三条失败反例（ASCII 单引号/引用内句号/跨子句条件，均 COMPLETED）及五条对偶（引用内换行、“无法”与“回答”分别引用、占位不拼接=COMPLETED；ASCII引用后真实拒答、条件后独立拒答=NO_RESULTS）。Controller 协作保留两条真实状态用例。

### 为什么
- `8d6f367` 仍：引用屏蔽在句子切分之后、不支持 ASCII 单引号、不识别跨子句“如果…，…时请…”。导致三条确定性反例误判 NO_RESULTS。需把引用屏蔽前置并用占位边界，扩充引号类型，按“……时请……”识别条件。

### 验证（均为离线/本地；PowerShell 直取 `$LASTEXITCODE`）
- 先加失败用例→对 `8d6f367` 确认 FAIL（共 4 项：A2 行368 / B2 行377 / C2 行386 / “占位不拼接”对偶行413，GRADLE_EXIT=1）；修复后转绿。
- `:app:compileJava :app:compileTestJava` → COMPILE_EXIT=0。
- 定点 `:app:test`（两测试类）→ GRADLE_EXIT=0；resolveFinalStatus 套件 tests=28/failures=0/errors=0/skipped=0，成功路径 tests=5，answerQuestionStream 契约 tests=3，顶层控制器 tests=3。
- 全量 `:app:test --no-daemon` → GRADLE_EXIT=0，BUILD SUCCESSFUL，无 FAILED。
- `git diff --check` → exit 0（已消除新常量块间行尾空白）。

### 尚未验证 / 保留边界
- 未连真实 LLM / 付费 Embedding / 真实 L1 / 生产库跑端到端 SSE；线上多样措辞、嵌套引用/条件/否定未经样本验证。
- 跨子句且拒答后无紧邻“时”的条件仍可能判拒答；ASCII 单引号靠成对匹配，英文撇号与中文拒答同句成对时可能误屏蔽（已“不跨换行”降低）；未做跨子句条件传播。

---

## 2026-10-01 — P1 定点修复：条件 / 引用 / 否定的作用范围限定（修复 7801762 确定性回归，待 Codex 四次复核）

**基线**：`7801762`（本任务前 HEAD）
**状态**：代码已写 + 本地/离线测试通过；**待 Codex 四次复核**（未提前写成复核通过）。
**范围**：仅改 `KnowledgeBaseQueryService.isExplicitRefusal` 最终拒答判定 + 相关后端测试 + 进度文档。未动共享 `answerQuestionStream`、`isNoResultLike`、探测窗口 `normalizeStreamOutput`、前端/P2、SSE 协议/事务/schema/来源组装/事件顺序补强；Controller 未重新注入 Repository。

### 改了什么
- 删除整句一刀切的 `REFUSAL_NEGATION`，新增分作用域常量：`QUOTED_SPAN`（成对引号内提及文本，判定前剔除）、`CLAUSE_SPLIT`（子句作用域）、`NEGATION_BEFORE_MODAL`（否定只紧邻情态词）、`CONDITIONAL_CONNECTIVE`（条件词在同子句且位于拒答前才生效）。`isExplicitRefusal` 拆为 `containsGenuineRefusal` + `hasUnguardedRefusal(Matcher,clause)`：逐子句遍历两处拒答构造，只要存在一处未被就地否定/非同子句条件/不在引用内的匹配即判拒答。新增 `import java.util.regex.Matcher`。
- 测试：`KnowledgeBaseQueryServiceTest` 新增三条失败反例（条件句/引用句=COMPLETED；否定作用错位的真实拒答=NO_RESULTS）及三条对偶用例（否定拒答本身=COMPLETED；引用后真实拒答=NO_RESULTS；条件+独立拒答=NO_RESULTS）。`RagChatControllerTest` 新增 `realRefusalWithRealStatusJudgingClearsSources`（不 mock 状态，真实 resolveFinalStatus 判拒答，断言落库 NO_RESULTS + sources 清空 `[]`），与已有正常回答保留来源用例配对。

### 为什么
- `7801762` 仍把整句一刀切：`REFUSAL_INABILITY.find()` 命中条件/引用里的“无法回答”误判 NO_RESULTS；`REFUSAL_NEGATION` 命中即整句放行，把“并非无法连接…”后面的真实拒答也误判 COMPLETED。需把条件/引用/否定各自限定到对应表达；不能“整句含如果/引号/否定词就放行”，也不能只删词/扩表/靠长度。

### 验证（均为离线/本地；PowerShell 直取 `$LASTEXITCODE`）
- 先加失败用例→对 `7801762` 确认 FAIL（31 tests completed, 3 failed：反例 A 行315 / B 行324 / C 行333，GRADLE_EXIT=1）；修复后转绿。
- `./gradlew :app:compileJava :app:compileTestJava --no-daemon` → COMPILE_EXIT=0。
- 定点 `:app:test --tests '...KnowledgeBaseQueryServiceTest' --tests '...RagChatControllerTest'` → GRADLE_EXIT=0（resolveFinalStatus 套件 tests=20/failures=0/errors=0/skipped=0；成功路径 tests=5/failures=0；answerQuestionStream 契约 tests=3；顶层控制器 tests=3）。
- `./gradlew :app:test --no-daemon`（全量）→ GRADLE_EXIT=0，BUILD SUCCESSFUL，无 FAILED。
- `git diff --check` → exit 0。

### 尚未验证 / 保留边界
- 未连真实 LLM / 付费 Embedding / 真实 L1 / 生产库跑端到端 SSE；线上多样措辞、引用/条件/否定复杂嵌套未经样本验证。
- 否定词与情态词被较多文字隔开、或条件标记出现在拒答之后，仍属已知规则边界；未引入额外 LLM 调用/大型解析/架构改造。

---

## 2026-10-01 — P1 定点修复：收窄最终拒答判定（修复 1ba2a57 确定性回归，待 Codex 复核）

**基线**：`1ba2a57`（本任务前 HEAD，= Codex 二次复核通过并确认保留 P2/事件顺序的版本）
**状态**：代码已写 + 本地/离线测试通过；**待 Codex 复核**（未提前写成复核通过）。
**范围**：仅改 `KnowledgeBaseQueryService.isExplicitRefusal` 的最终拒答判定 + 相关后端测试 + 进度文档。未动共享 `answerQuestionStream`、`isNoResultLike`、探测窗口 `normalizeStreamOutput`、前端/SSE 协议/事务/schema/来源组装；Controller 未重新注入 Repository。

### 改了什么
- 删除 `STRONG_REFUSAL_MARKERS` 宽泛子串列表，新增三个构造级正则常量 `REFUSAL_INABILITY`（无法/不能…回答）、`REFUSAL_EMPTY_RETRIEVAL`（未/没有…检索|找到…信息|内容|资料…）、`REFUSAL_NEGATION`（并非/不是…无法/不能）。`isExplicitRefusal` 保留固定模板 equals/startsWith，非模板部分只看起始句，先过否定护栏再匹配两个拒答构造。新增 `import java.util.regex.Pattern`。
- 测试：`KnowledgeBaseQueryServiceTest` 新增两条 Codex 反例回归用例（“找不到配置文件…”、“知识库中未配置索引版本字段…”）及反例矩阵（条件句/引用错误文本/否定式拒答/信息不足描述句=COMPLETED；固定模板与非模板明确拒答=NO_RESULTS）。`RagChatControllerTest` 新增 `normalAnswerWithRealStatusJudgingPersistsCompletedWithSources`（不 mock 状态，走真实 resolveFinalStatus，断言落库 COMPLETED + sources 非空）。

### 为什么
- `1ba2a57` 仍用“第一句 contains 宽泛子串”，导致含“找不到”/“知识库中未”的正常排查答案被误判 NO_RESULTS 并清空来源（确定性回归）。需只将“无法依据资料回答当前问题”的整句式拒答判为拒答，且不能仅删两个词/继续扩词表/靠长度放行。

### 验证（均为离线/本地；PowerShell 直取 `$LASTEXITCODE`，已消除 `2>&1 | Select-String` 对 stderr 的 NativeCommandError 干扰）
- 先写回归用例→对 `1ba2a57` 确认 FAIL（failures=2，行 252/261）；修复后转绿。
- `./gradlew :app:compileJava :app:compileTestJava --no-daemon` → GRADLE_EXIT=0。
- `./gradlew :app:test --tests '...KnowledgeBaseQueryServiceTest' --tests '...RagChatControllerTest' --no-daemon` → BUILD SUCCESSFUL（resolveFinalStatus 套件 tests=14/failures=0/errors=0/skipped=0；成功路径 tests=4/failures=0；顶层 tests=3/failures=0）。
- `./gradlew :app:test --no-daemon`（全量）→ GRADLE_EXIT=0，BUILD SUCCESSFUL，无 FAILED。
- `git diff --check` → exit 0。
- 退出码不一致排查：旧写法显示 exit 1 系 `2>&1 | Select-String` 把 Gradle 的 stderr 进度行当作错误记录；`*>` 重定向到文件后 `$LASTEXITCODE` 真实为 0（已据此排查，非笼统归因 stderr 告警）。

### 尚未验证（真实环境）
- 未连真实 LLM / 付费 Embedding / 真实 L1 / 生产数据库跑端到端 SSE；真实模型输出的多样拒答措辞与正则覆盖面未经线上样本验证。
- 规则边界：拒答句仅出现在第一句之后、或有效回答起始句本身肯定式断言“无法…回答”，仍可能误判（见 PROJECT_PROGRESS.md §14）。

---

## 2026-10-01 — Codex 复核后的 P1/P2 定点修复与事件顺序测试补强

**基线**：`1c3d4c7`（本任务前 HEAD，= Codex 复核的 master）
**范围**：两处定点修复 + 一项测试补强。保持第 3 轮端点兜底与第 4 轮来源下沉的分层/兼容契约；不改 schema、不做无关重构、不让 Controller 重新注入 Repository；维持 `data… → 持久化成功 → sources → done(status)` 与来源字段/顺序/score/截断/未知回退。未调真实 LLM / 付费 Embedding / L1 / 生产库。

### 改了什么
- **P1**（`KnowledgeBaseQueryService`）：`resolveFinalStatus` 由全文 `isNoResultLike`（contains）改判新增的 `isExplicitRefusal`（只认“等于/起始于固定无结果模板”或“第一句含 `STRONG_REFUSAL_MARKERS`”）。`isNoResultLike`、探测窗口 `normalizeStreamOutput`（`STREAM_PROBE_CHARS=120`）与 `answerQuestionStream` 均不动。
- **P2**（`frontend/src/api/stream.ts`）：`flushEventBuffer` 在 `!done` 且尾部为孤立 `\r` 时先扣留（`heldCR`）、处理完再接回，修复跨网络块 CRLF 拆分导致的 `done` 丢失/状态 JSON 混入正文/误回退 MODEL_FAILED；line/event 两模式语义等价。
- 测试可达性：`request.ts` axios 类型改 `import type` + `import.meta.env?.`；`stream.ts`/`ragChat.ts` 内部 import 加 `.ts` 扩展；`package.json` 新增 `test:sse-stream` 并加入 CI。
- 测试：`KnowledgeBaseQueryServiceTest` 新增 3 条 resolveFinalStatus 用例（有效长回答含“信息不足”仍 COMPLETED / 非模板明确拒答仍 NO_RESULTS / 正文描述性短语不误判）；`RagChatControllerTest` 新增 `persistenceVerifiedAtMomentSourcesAndDoneArrive`（收到 sources/done 当下断言已持久化 + 有效回答保留非空来源）；新建 `frontend/src/api/stream.test.ts`（10 条真实解析链路 + ragChat 端到端护栏）。

### 为什么
- P1：旧逻辑对整段正文做关键词 `contains`，正常长回答中途出现“信息不足”即被误判 NO_RESULTS 并清空来源。需基于结构位置区分“明确拒答”与“正常解释”，而非仅加关键词或靠长度放行。
- P2：一个 `\r\n` 被拆为“…\r”+“\n…”时，孤立尾部 `\r` 被提前归一成 `\n`，与下一块首 `\n` 拼成假空行，拆散 `event:`/`data:` 导致 done 丢失。

### 验证（均为离线/本地测试通过，非真实环境）
- `./gradlew :app:compileJava :app:compileTestJava --no-daemon` → BUILD SUCCESSFUL。
- `./gradlew :app:test --tests '...KnowledgeBaseQueryServiceTest' --tests '...RagChatControllerTest' --no-daemon` → BUILD SUCCESSFUL（resolveFinalStatus 套件 tests=7/failures=0；成功路径套件 tests=3/failures=0）。
- 反例复现：P1 临时还原 `isNoResultLike` → 2 条新用例 FAIL（failures=2），改回转绿；P2 临时禁用扣留逻辑 → 跨块用例 FAIL，改回转绿。
- `./gradlew :app:test --no-daemon`（全量）→ BUILD SUCCESSFUL（无 FAILED）。PowerShell 因 JVM stderr 告警会将退出码误报为 1，Gradle 本身报成功。
- `node --test src/api/stream.test.ts` → pass 10/fail 0；既有前端测试合计 pass 18/fail 0。
- `cd frontend && pnpm run build` → 构建成功（仅历史 CSS `:where()` 告警）。

### 尚未验证（真实环境）
- 未连真实 LLM / 付费 Embedding / 真实 L1 / 生产数据库跑端到端 SSE；真实网络上 `\r\n` 跨 TCP 段拆分时机、Spring WebFlux SSE 分帧未实测。
- `isExplicitRefusal` 强拒答词表覆盖面未经线上样本验证。

---

## 2026-10-01 — RagChatController 来源组装责任下沉到 Service（Controller 不再持有 Repository）

**基线**：`bab3a62`（本任务前 HEAD）
**范围**：只把 `buildSourceReferences` 的数据库查询与来源组装从 `KnowledgeBaseQueryService` 移入已持有 `KnowledgeBaseRepository` 的 `RagChatSessionService`，让 `RagChatController` 不再直接注入 Repository。未改数据库 schema、未改其他端点、未改来源字段/排序/SSE 序列化/状态持久化契约、未调外部 API / 数据库。

### 改了什么
- `RagChatSessionService`：新增 `buildSourceReferences(List<Document> docs)`，复用自身 `knowledgeBaseRepository` 字段批量查 `originalFilename` 组装 `SourceReference`；来源顺序、字段含义、截断与「未知文档」回退与原实现一致。
- `KnowledgeBaseQueryService`：删除 `buildSourceReferences(docs, kbRepo)` 及随之不再使用的 `KnowledgeBaseEntity`/`SourceReference`/`KnowledgeBaseRepository` import。
- `RagChatController`：移除 `KnowledgeBaseRepository` 字段与 import，改调 `sessionService.buildSourceReferences(result.sourceDocuments())`；`resolveFinalStatus` 仍走 `queryService`，SSE 事件顺序与持久化不变。
- 测试：`RagChatControllerTest` 构造函数去掉 repository 参数、来源打桩改为 `sessionService.buildSourceReferences(anyList())`；`KnowledgeBaseQueryServiceTest` 删除已迁移的来源用例与无用 import；来源提取用例迁到 `RagChatSessionServiceTest`（新增 4 条，含未知 kb 回退）。

### 为什么
- 原设计中 `buildSourceReferences` 放在 `KnowledgeBaseQueryService` 却需要 `KnowledgeBaseRepository`，导致 Controller 仅为把 Repository 传进去而直接持有 Repository，违反 Controller 不碰数据访问的分层职责。`RagChatSessionService` 本就持有该 Repository 且已是 Controller 协作者，是来源组装的自然归属。

### 验证
- `./gradlew :app:compileJava :app:compileTestJava --no-daemon` → exit 0
- `./gradlew :app:test --tests '...RagChatControllerTest' --tests '...RagChatSessionServiceTest' --tests '...KnowledgeBaseQueryServiceTest' --tests '...SourceReferenceTest' --no-daemon --rerun` → exit 0（四套件 failures=0 errors=0，含迁移后的来源用例）

### 尚未验证（真实环境）
- 未连真实 LLM/数据库跑端到端 SSE；来源组装仅由单元测试与 Controller 打桩覆盖，真实 `findAllById` 命中/缺行路径未做集成验证。

---

## 2026-10-01 — KnowledgeBaseController 流式错误兜底语义定点恢复

**基线**：`376ef5a`（本任务前 HEAD）
**范围**：只恢复 `queryKnowledgeBaseStream()`（`/api/knowledgebase/query/stream`，纯文本 `Flux<String>`）的流式错误兜底；不改共享 Service、不改 RagChatController 的 SSE 协议、不调外部 API/数据库。

### 改了什么
- `KnowledgeBaseController.queryKnowledgeBaseStream`：新增 (A) 建立 RetrievalResult 时同步抛错的 try/catch 兜底，(B) Flux 订阅后异步出错的 `.onErrorResume` 兜底；用 `AtomicBoolean emitted` 区分“未输出即失败”（整串【错误】+真实原因）与“已输出部分后中断”（追加固定【错误】标记）；新增常量 `STREAM_ERROR_PREFIX`/`STREAM_UNAVAILABLE_FALLBACK` 与 `resolveErrorReason`。
- 新增 `KnowledgeBaseControllerStreamTest`（7 条）。

### 为什么（回归）
- Phase 1（`da1c744`）为支持 RagChatController 把共享 `answerQuestionStream` 的两处降级改为 `Flux.error(e)`；而本端点直接返回 `.contentStream()` 且无错误处理，导致失败裸传播、前端把被中断的回答当正常完成（伪装 + 丢失【错误】兜底）。端点级修复不影响依赖 `Flux.error` 的 RagChatController。

### 验证
- `./gradlew :app:compileJava :app:compileTestJava --no-daemon` → exit 0
- `./gradlew :app:test --tests '...KnowledgeBaseControllerStreamTest' --tests '...RagChatControllerTest' --tests '...KnowledgeBaseQueryServiceTest' --no-daemon --rerun` → exit 0（新类 7、RagChatControllerTest / QueryServiceTest 无回归）

### 尚未验证（真实环境）
- 未连真实后端跑端到端 SSE；(A) 同步抛错分支在当前服务形态下几乎不触发（Service 内部已 catch 为 `Flux.error`），主要真实路径为 (B) 中 `emitted=false` 分支。

---

## 2026-10-01 — RAG 流式回答前端状态处理定点修复

**基线**：`b85890f`（本任务前 HEAD）
**范围**：只修前端流式回答的最终状态与来源展示，消费上一任务后端已带出的 `done.status` 契约；不改后端、不调外部 API / 数据库、不动无关页面。

### 改了什么
- `frontend/src/api/ragStreamStatus.ts`（新增纯逻辑模块）：`parseDoneStatus`（解析 done data，缺失/非法/未知→`undefined`）、`resolveFinalStatus`（无确认回退 `MODEL_FAILED`，绝不当 COMPLETED）、`sourcesDisplayMode`（COMPLETED→grounded / NO_RESULTS→none / MODEL_FAILED·CLIENT_DISCONNECTED→degraded / 生成中→pending）、`selectSourcesForStatus`（NO_RESULTS 一律清空来源）。
- `frontend/src/api/stream.ts`：`onDone` 改为 `(status?: string) => void`，`done` 分支把 data 透传。
- `frontend/src/api/ragChat.ts`：`MessageStatus` 收敛到纯模块再导出；`sendMessageStream` 用 `finalized` 单次护栏协调 onDone（解析服务端状态）/ onComplete（流结束却无 done→`MODEL_FAILED`）/ onError；`onComplete` 回传最终状态。
- `frontend/src/pages/KnowledgeBaseQueryPage.tsx`：完成回调按服务端状态写定、用 `selectSourcesForStatus` 定来源；渲染用 `sourcesDisplayMode` 门控（仅 grounded 作“引用来源”，degraded 降为“参考文档·本条未成功生成仅供参考”，none/pending 不渲染）；非 COMPLETED 显状态徽标。刷新恢复沿用 `m.status` + `m.sourcesJson` 经同一渲染管线。
- `frontend/src/api/ragStreamStatus.test.ts`（新增 5 条）、`package.json` 新增 `test:rag-stream`、`.github/workflows/ci.yml` frontend 任务加入该脚本。

### 为什么（根因）
- 原 `stream.ts` 的 done 分支丢弃 data；页面在 onDone 里无条件写 `COMPLETED`，不看服务端确认状态。
- “无 done 确认”与 onError 未收敛为失败态；有文档却拒答（NO_RESULTS）时仍带来源呈现为有依据的回答。

### 验证
- `node --test src/api/ragStreamStatus.test.ts` → 5 pass / 0 fail
- `pnpm run build`（`tsc && vite build`）→ exit 0（仅既有 CSS `:where()` 告警与 chunk 体积告警，与改动无关）

### 尚未验证（真实环境）
- 未连真实后端跑过一次端到端 SSE；done/error/断流实际时序与无 done 回退路径仅由单元级纯函数 + 代码走查保证。
- 刷新恢复依赖后端确已持久化 `status` / `sources_json`，本轮未对真实 DB 做端到端核对。

---

## 2026-10-01 — RAG 流式回答服务端最终状态与事件顺序定点修复

**提交**：`88b4017`（已推送 `devsupport/master`，基线 `67baebd`）
**范围**：只修 Phase 1 流式回答（`RagChatController.sendMessageStream`）的服务端最终状态与事件顺序；未做无关重构、未改生产数据、未调付费 API / Docker / 真实 L1。

### 改了什么
- `KnowledgeBaseQueryService.java`：新增 `resolveFinalStatus(实际输出, 检索文档)` —— 空检索 / 输出为空 / 命中 `isNoResultLike` 拒答 → `NO_RESULTS`；有文档且实质性回答 → `COMPLETED`。判定收敛到 Service 层。
- `RagChatController.java`：链重构为 `data… → Flux.defer{ 判最终状态 → 先落库 → 成功才发 sources + done }`；落库失败返回 `Flux.error(BusinessException)` 且**不发 done**；`AtomicBoolean` 单次护栏协调 成功 / `MODEL_FAILED` / `CLIENT_DISCONNECTED` 三条终止路径；`NO_RESULTS` 一律以 `[]` 存来源；`done` 事件 data 补 `{"status":"..."}`。
- `RagChatControllerTest.java`（新增 5 条）、`KnowledgeBaseQueryServiceTest.java`（新增 `resolveFinalStatus` 4 条）。

### 为什么（根因）
- 原实现 `done` 先于持久化发出，落库失败仍向客户端宣告成功。
- 原 `status` 在请求时按检索条数预定，不看模型实际输出 —— 检索命中却输出拒答文本时会被误存为有依据的 COMPLETED。
- 原错误 / 取消 / 落库异常三条终止回调无护栏，可能重复写入并互相覆盖。

### 验证
- `./gradlew :app:compileJava :app:compileTestJava --no-daemon` → exit 0
- `./gradlew :app:test --tests 'interview.guide.modules.knowledgebase.RagChatControllerTest' --tests 'interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryServiceTest' --no-daemon --rerun` → exit 0（RagChatControllerTest 5、resolveFinalStatus 4，全绿）
- 环境：PowerShell，`$env:GRADLE_USER_HOME="C:\temp\gradle-tmp"`

### 尚未验证（真实环境）
- 未连真实 LLM / 数据库跑过一次端到端 SSE 顺序与落库；`@Transactional` 在真实 DB 的回滚表现未实测。
- 真实模型多样拒答措辞能否全部落入 `isNoResultLike`，未经样本验证。
- 前端据 `done.status` 区分展示属下一项任务，本任务仅保证后端已带出该字段。

---
