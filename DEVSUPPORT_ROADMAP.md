# DevSupport 项目开发路线图

> 本文件是 DevSupport 平台的主线事实源。后续 Agent 执行任何开发任务前，必须先阅读本文件了解产品定位、当前进展和执行边界。
>
> 核实基线：分支 `review/devsupport-stage1-stage2-20261004`（阶段 5 首切片提交前的父提交为 `a823dd4`），核实日期 2026-10-09。
>
> 分工声明：本文件管方向与阶段验收；`PROJECT_PROGRESS.md` 管逐轮实施证据；`CHANGES.md` 管变更流水。三者不重复记录。

---

## 1. 产品定位与用户闭环

**一句话定位**：DevSupport —— 研发团队知识与故障排查助手。

把服务文档、部署手册、配置/API 说明、错误码、脱敏故障记录和版本变更沉淀为知识库，通过带来源证据的 RAG 检索问答，帮助研发同学定位并解决故障。

**目标用户**：维护多服务项目的新成员、后端开发者、值班同学。

**完整用户闭环（5 步）**：

1. 管理员维护项目手册、配置/API 文档、错误码、脱敏故障记录和版本变更。
2. 开发者按项目、环境和版本描述故障现象。
3. 系统基于资料给出带来源证据的可能原因和排查步骤；资料不足时追问而非编造。
4. 开发者记录实际排查结果。
5. 问题解决后形成案例草稿，经人工审核后发布到案例库。

**四个主导航**：**文档中心、排查会话、案例库、评测结果**。

---

## 2. 非目标与遗留模块处理

### 非目标（首个可交付版本不做）

工单系统、复杂组织/租户权限、GraphRAG、多模态理解、全自主 Agent、微服务拆分或大规模重写。仅在实际需求和评测证据证明必要时再单独立项，不为技术名词堆组件。

### 遗留模块处理

Resume、Interview、VoiceInterview、InterviewSchedule 属于原上游项目遗留功能：

- **保留全部后端代码与端点**（不禁用、不删除）。
- 前端从主导航移除，旧路由重定向到知识库首页。
- **不在遗留模块上开新特性**，不作为 DevSupport 主线任务。
- **冻结维护面**：83 个后端 Java 文件 + 11 个前端页面 + 12 个面试用途 `.st` Prompt + `resources/skills/**`。这些文件只做必要的测试债务修复，不扩展。

---

## 3. 当前进度表

状态词七档：**未开始 / 实现中 / 代码完成 / 离线验证通过 / 本地集成通过 / 付费外部验证通过 / 完成**。

> 以下状态为核对时的本机快照（2026-10-09）。

| 阶段 | 状态 | 仓库证据 | 限制 |
|------|------|---------|------|
| 阶段 0：主线护栏 | 已完成 | 本文件落地 + 4 个 Resume demo 遗留文件已提交（`ea8fc22`）；路线图状态与工作区事实核对一致 | — |
| 阶段 1：主界面与上下文 | 已完成（DevSupport 主线相关全绿；全量后端仍非全绿，剩余为 Voice Redis 环境阻塞） | 四导航重建 + 上下文贯通 + 检索范围一致性修正 + 最终验收 + ListFiltering 漂移修复；DevSupport 主线相关测试全绿（上下文/会话/来源/**知识库列表筛选**单测 + `RagChatSseIntegrationTest`、`KnowledgeBaseRepositoryIntegrationTest`、`RateLimitIntegrationTest` 经 Testcontainers 真实 PostgreSQL 通过 + 新增 mock 浏览器 E2E `chat-scope.spec.ts` 通过）；全量后端 579 tests / 10 failed / 3 skipped（BUILD FAILED，剩余 10 全为 `VoiceInterviewIntegrationTest` 环境阻塞） | project/version 数据模型缺失，UI 已禁用；service/environment 为精确匹配（区分大小写）非 `LOWER(...)`；曾有 2 个 `KnowledgeBaseListServiceTest$ListFiltering` 属主线知识库列表筛选测试漂移（本轮已修 stub，不能称“无主线失败”）；Voice 集成 10 个为 Redis 环境阻塞，非 DevSupport 主线 |
| 阶段 2：文档中心 | 验收通过（并发安全 + 异步状态机 + ABANDONED 状态；源码实现+自动化验证已完成，真实付费模型端到端未验证） | 迁移 `V20261005` + `V20261006`（conflict 列 + normalized_version_label + 部分唯一索引 uq_kb_active_version）+ `V20261007`（ADOPTING/ABANDONED CHECK 约束）；上传→S3 对象+PG 元数据→Redis Stream 生产/消费异步 COMPLETED→按项目/环境/版本检索→新版本停用旧版本并删向量（`KnowledgeBaseUploadPipelineIntegrationTest` 真实 RustFS+Redis+pgvector+确定性 embedding 实测；`KnowledgeBaseLifecycleIntegrationTest`）；**版本冲突治理**：同 documentKey + 同规范化 versionLabel + 不同 fileHash → 标记 VERSION_CONFLICT，不投递向量化；**并发冲突归属修复**：获胜方 active 不变，失败方存为独立冲突候选（不覆盖/不丢弃）；**adopt 异步状态机**：CONFLICT→ADOPTING→向量化→promote→COMPLETED，失败回退 CONFLICT，旧版本始终 active；**ABANDONED 状态替代 FAILED**：放弃记录不显示重试按钮；冲突解决 API `POST /{id}/adopt`（采用冲突版本）/ `POST /{id}/abandon`（放弃冲突版本）；前端冲突筛选复选框 + 采用/放弃按钮（确认弹窗+loading+反馈）；PG 部分唯一索引保证同 key+label 只允许一个 active 行；文档中心 处理中/待处理/可检索/失败(重试)/已停用/版本冲突 + 停用 + 采用/放弃操作（`kbStatus` + Playwright E2E `kb-doc-center.spec.ts` mock）；**检索来源标注版本**（`SourceReference` 扩展 versionLabel/versionNo/documentKey，排查会话来源面板展示紫色版本标签）；**文档中心支持按 project/docType/version 筛选**（后端 API 支持 project/docType/version 查询参数；前端 client-side 过滤）；全量后端 **626 tests / 613 passed / 10 failed（全为 `VoiceInterviewIntegrationTest` Redis 环境阻塞）/ 3 skipped**，DevSupport 相关全绿 | 保留 fileHash 全局唯一 → 同一文件跨项目独立归属仍不支持（已知边界）；排查会话 ContextSelector 暂只暴露 service/environment；真实付费模型端到端与生产部署未验证 |
| 阶段 3：排查会话闭环 | 实现中（结构化回答骨架 + 来源章节定位 + 版本冲突提示 + INSUFFICIENT_INFO 拒答细化；源码+自动化完成，真实付费模型端到端验证待授权（挂起）） | Prompt 定义 8 段结构化回答骨架；`SourceReference` 新增 `sectionTitle` 从文档 heading 提取；`MessageStatus` 新增 `INSUFFICIENT_INFO` 拒答细化；前端来源面板显示文档名>章节标题；前端多版本来源时显示冲突提示条；前端 INSUFFICIENT_INFO 展示追问式提示；后端测试：骨架 SSE、INSUFFICIENT_INFO 判定、sectionTitle 提取；前端测试：sectionTitle 映射、insufficient 模式、冲突提示 E2E。全量后端 **650 tests / 637 passed / 10 failed（Voice Redis 遗留）/ 3 skipped**，DevSupport 相关全绿 | 结构化回答骨架已定义但 Prompt 调优未受控实验；真实付费模型端到端未验证 |
| 阶段 4：案例库审核发布 | 已完成（源码实现+自动化验证已完成，真实付费模型端到端未验证） | DB 迁移 `V20261008`+`V20261009`；CaseStatus 枚举；CaseDraftService/CaseReviewService/CaseLifecycleService；CaseLibraryController 8 端点；前端案例列表+详情+生成按钮；案例检索接入（approve 向量化、deprecate 删向量、检索合并）；范围过滤（service/environment/affected_versions 精确匹配，无上下文不全局召回）；废弃一致性（两阶段事务 + vectorCleanupPending + fail-closed + retryVectorCleanup）；X-Operator 审计标签（非身份认证）；后端 22 单测 + 20 集成测试（含真实删除失败路径、KB/案例合并走 retrieveAndMerge、affected_versions 匹配/不匹配）；前端 3 单测 + 5 E2E。集成测试 20/20 PASS（commit `353b8a8`）。全量后端 **682 tests / 669 passed / 10 failed（Voice Redis 遗留）/ 3 skipped**，DevSupport 相关全绿 | 案例范围仅支持 service/environment + affected_versions 精确匹配；project 维度暂不参与过滤（字段可空无一致性保证，延期）；affected_versions 语义对齐依赖人工维护 |
| 阶段 5：可评测检索优化 | 案例驱动回归评测确定性首版已实现并完成数据泄漏修复（来源证据持久化 + 隔离评测），离线验证通过；Hybrid/RRF/Rerank 未开始（需付费对照授权）；既有 P1-C 真实评测已完成 | 新增 `interview.guide.modules.evalregression` 模块（3 实体 + `RegressionRunStatus` + 6 record DTO + 3 repository + 4 服务 + `CaseRegressionController` + MapStruct `CaseRegressionMapper`）+ Flyway `V20261010` 三表（case_regression_items/runs/results）+ ErrorCode 13001/13002/13003 + 案例生命周期联动（approve→`upsertOnPublish`、deprecate→`deactivateOnDeprecate`）+ `/api/eval/regression/*` 端点；前端 `CaseRegressionPanel` + `EvalResultsPage` 回归区块 + `CaseDetailPage` 已纳入回归徽标。测试：后端定点 evalregression 单测 46 + 集成 4、caselibrary 单测 22 + 集成 20 = 92 全绿；前端 build exit 0、单测 regression 7/7、Playwright case-regression 10/10 + case-library 5/5；真实运行时离线闭环（bootRun + docker compose 真实 PG/pgvector+Redis+RustFS + 真实 Flyway + OpenAI SDK HTTP embedding 打到本地确定性桩 + 真实 pgvector 检索 + REST + 落库 + Vite）publish→生成回归项→运行通过→废弃→排除 走通，9 次 embedding 全命中 127.0.0.1、零付费；浏览器真实数据契约走查通过。全量后端 **741 tests / 728 passed / 10 failed（全为 `VoiceInterviewIntegrationTest` 连本机 Redis:6379 遗留，非本次）/ 3 skipped**，DevSupport 主线 343 全绿，V20261010 已在 Testcontainers pgvector/pg16 真实应用成功。P1-C L1 两次真实评测（baseline Hit@5=93.75%, MRR@5=77.81%; heading-aware MRR@5=0.8542）付费外部验证通过（heading-aware 非受控单变量 A/B，不可宣称因果提升） | **真实付费 embedding（DashScope text-embedding-v3）下语义检索质量/排序/top-K 精度未验证**（确定性常量向量使相似度并列，仅证明管线正确 + 自身证据可召回 + 判定/落库/废弃排除逻辑正确）；聊天 assistant 消息→createDraft 离线闭环未验证（需 LLM，未付费跑）；全量后端非全绿（10 个 Voice Redis 遗留失败保留）；H1/L4（approve() @Transactional 内调 EmbeddingModel 外部 HTTP，改动前既存技术债、本次仅延续，建议移出事务或改 Redis Stream 异步）；M1（回归复用全局 KB retrieveAndMerge，kbIds 传空→KB 分支不带过滤，已用默认 topK 5→20 提升 + 上限钳制 200 缓解挤出假失败）；M3（要点被物理切分到不相邻 chunk 仍可能假失败，空白归一只解决 \n 拼接）；L1（无 FAILED 终态，13003 暂未使用）；L3（逐项结果未快照 expectedEvidence，UI 经实时 items 反查，案例重发布产生新 Document UUID 会使历史高亮漂移，但 passed 布尔已落库、结论不受影响）；project 维度案例过滤延期（schema 级缺口，另立切片） |
| 阶段 6：可靠性与部署 | 未开始 | demo profile 模式已在 Resume 验证 | Compose 全栈未实测 |

---

## 4. 开发阶段详情

### 阶段 0：恢复工作区事实与主线护栏 ✅ 已完成

- **要解决的用户问题**：接手的 Agent 无法快速知道仓库状态和 DevSupport 主线。
- **工作范围**：核对本文件与 `README`、`PROJECT_PROGRESS.md`、`CHANGES.md`、Compose 配置及当前代码；清点 Resume/Voice 相关未提交改动并分类（保留复用 / 暂缓 / 冲突），不擅自丢弃；把路线图放到仓库显眼位置并从 README 链接；建立阶段状态表。
- **预期交付物**：本路线图在仓库显眼位置 + 阶段状态表 + 未提交改动处置方案。
- **验收标准**：新 Agent 阅读 README 链接和路线图即可知道产品是什么、下一阶段是什么、哪些事情不在范围内；工作区原有改动零丢失。
- **未提交改动处置**：
  - 先保存、分类并处理导航冲突（`Layout.tsx` 新增的「简历管理」与四导航方案冲突）。
  - 提交范围由实际审查决定，不作为阶段 1 的硬性前置条件。
  - Resume/Voice 改动作为独立提交保护，标注"遗留模块维护"。
- **完成记录（2026-10-04）**：
  - 4 个未跟踪的 Resume demo 文件已作为遗留维护提交（`ea8fc22`）：`DevSupport_项目开发总方案_v1.0.md`、`DemoResumeGradingService.java`、`application-demo.yml`、`app/src/test/java/interview/guide/modules/resume/`（6 个测试文件）。
  - 路线图状态表已核对，与工作区当前事实一致。
  - 阶段 0 交付物全部落地：路线图在仓库根目录可见、阶段状态表已建立、未提交改动已分类保留。

### 阶段 1：主界面与项目/环境/版本上下文 ✅ 已完成

- **要解决的用户问题**：开发者需要按项目、环境、版本提问，并从界面一眼看出这是研发支持产品。
- **工作范围**：重建前端主导航为四入口（文档中心、排查会话、案例库、评测结果）；复用已有知识库、RAG 聊天、设置能力；旧 Resume/Interview 路由从主导航移除或留在非主线入口，不删除实现；在创建排查会话和查询时明确项目/环境/版本上下文，确认上下文从前端请求传到后端服务、检索过滤与来源快照；对暂不支持的过滤条件诚实禁用/说明。
- **预期交付物**：可启动的 DevSupport 主壳、真实可用的导航入口、项目上下文在请求链路中的数据契约。
- **验收标准**：
  - 文档中心、排查会话、评测结果三个入口真实可用。
  - 案例库入口可见但明确显示"待建设"状态（阶段 4 交付完整功能）。
  - 不存在将面试/简历页面伪装成研发功能的入口。
  - 上下文参数从前端传到后端并影响检索范围，或明确显示尚未支持。
- **⚠️ 冲突提示**：与 `devsupport-phase1-design.md` §3.4 冲突。该设计决策认为 service/environment 只是管理页的组织标签，检索范围由用户显式勾选的 kbIds 决定，**服务端不做强制过滤**。阶段 1 需将其升级为影响查询范围的上下文，属于设计变更，实施前需说明迁移与影响面。
- **完成记录（2026-10-04）**：
  - **四导航重建**：文档中心 (`/docs`)、排查会话 (`/chat`)、案例库 (`/cases`)、评测结果 (`/eval-results`)，Layout 从旧五入口改为四入口。
  - **上下文贯通**：新增 `GET /api/knowledgebase/resolve-context` 端点，service/environment 选择后解析为匹配的 KB IDs；RAG Chat 会话创建以 service/environment 为**限制范围**——显式 `knowledgeBaseIds` 只能在范围内缩小（取交集，范围外 ID 被安全排除），范围为空或与显式选择无交集时报错、绝不退回全量检索，后端为最终校验方；未提供 service/environment 时保持旧的显式 `knowledgeBaseIds` 行为。
  - **ContextSelector 组件**：service + environment 双选择器（首选项标签为"服务"，非"项目/服务"），"当前检索范围：N 个文档"中 N 与最终会话实际使用的 KB 集合一致（随手动勾选缩小而更新）；project/version 禁用态标注"阶段 2 才支持"。该选择器只挂在排查会话页；文档中心改由其自带 service/environment 下拉筛选列表（复核修正：移除原先不影响列表却伪装成检索范围的无效控件）。
  - **评测结果页**：加载 heading-aware-v0/v0.1 和 v0.1 baseline 三套数据集离线评测指标（K=1/3/5/10 指标表）。
  - **来源引用增强**：展示 service（蓝色）和 environment（绿色）标签快照。
  - **案例库占位**：明确标注"待建设"，不做假数据。
  - **设计冲突处理**：service/environment 从纯组织标签升级为影响检索范围的上下文；新增 resolve-context 端点作为桥梁；project/version 因数据模型缺失在 UI 明确禁用。
  - **代码审查修复**：service/environment 过滤由 in-memory 下推到 JPA 派生查询 `findByServiceOrderByUploadedAtDesc` / `findByEnvironmentOrderByUploadedAtDesc` / `findByServiceAndEnvironmentOrderByUploadedAtDesc`，对入参 `trim()` 后做**精确匹配**（PostgreSQL 默认区分大小写，**未做 `LOWER(...)` 大小写不敏感**；如需不敏感需新增自定义查询 + repository/集成测试）；会话上下文清除联动（切换会话时清除 ContextSelector 状态）。
  - **浏览器验证**：8 项 E2E 全部通过（导航、上下文选择、RAG 问答、来源标签、评测结果展示）。
  - **测试**：后端 577 tests（新增 12+ 上下文解析测试全通过），前端 53 单元测试全通过，`pnpm run build` 通过。
  - **阶段 1 复核修正（2026-10-04）**：修正排查会话"检索范围一致性"——后端 `createSession` 由"显式 kbIds ∪ 上下文"改为"以上下文为限制范围、显式只在范围内缩小（交集），空范围/无交集报错、绝不退回全量检索"，后端为最终校验方；前端 `ChatSessionsPage` 勾选限制在范围内且显示文档数对应最终会话集合（新增 `utils/chatScope.ts` 及单测）；`ContextSelector` 文案"项目/服务"改为"服务"、project/version 明确"阶段 2 才支持"；移除文档中心无效的 `ContextSelector`；更正本文件与 `CHANGES.md` 关于环境过滤 `LOWER(...)` 的不实描述（实为精确匹配派生查询）；修复 2 个与实现漂移的既有 environment 单测（原"577 全通过"记录不准确）。**本次验证**：后端相关定点单测 + 前端 `pnpm run build` + 前端 `chatScope`/`kbFilter` 单测通过（exit 0）；**未重跑**：全量后端测试、浏览器 E2E、真实 LLM/Embedding 端到端。
  - **阶段 1 最终验收（2026-10-04）**：后端全量 `./gradlew :app:test --rerun` → 579 tests / 12 failed / 3 skipped（BUILD FAILED，exit≠ 0）。DevSupport 主线相关全绿：`RagChatSessionContextTest`（ScopeNarrowing/EmptyContextError/ContextResolution/BackwardCompatibility）、`KnowledgeBaseContextResolveTest`、`RagChatSessionServiceTest`、`SourceReferenceSnapshotTest`、`RagChatControllerTest`，及经 Testcontainers 真实 PostgreSQL 的 `RagChatSseIntegrationTest`(3/0) 与 `KnowledgeBaseRepositoryIntegrationTest`(5/0)。12 个失败分类：10 个 `VoiceInterviewIntegrationTest` 是遗留模块需本机 Redis:6379 未启动的**环境阻塞**（`RedisConnectionException`；同批 `RateLimitIntegrationTest`/`RagChatSse` 均通过，故非 Docker 问题），**不属 DevSupport 主线**；2 个 `KnowledgeBaseListServiceTest$ListFiltering` 是 **DevSupport 知识库列表筛选的既有测试桩漂移**（`PotentialStubbingProblem`：自 `5798f62` 实现改走 `findByEnvironmentOrderByUploadedAtDesc` 派生查询后，测试仍 stub `findAllByOrderByUploadedAtDesc`），属主线、**不能表述为“无主线相关失败”**——已按下方“口径修正”条目修复。新增并通过 mock 浏览器 E2E `frontend/e2e/chat-scope.spec.ts`（Playwright + Chromium，`page.route` 全量 mock `/api`，无后端/无付费 API）：选服务后展示“2 个文档”→缩小为“已选 1 个文档”→请求体 `knowledgeBaseIds=[1]` 且 `service=payment`，范围外 auth 文档不在弹窗、无法扩大；空范围明确提示、无计数、无创建按钮，绝不退回全量。**验收结论**：检索范围一致性修正通过；不将定点通过写成全量通过（全量非全绿，原因已记录）。未提交/未推送；`.env`/wrapper/评测工件/Docker 卷未改动。
  - **阶段 1 口径修正 + ListFiltering 漂移修复（2026-10-04）**：修复上述 2 个 `KnowledgeBaseListServiceTest$ListFiltering` 测试桩漂移——仅将 `environmentFilterOnly`、`nullRowsExcludedWhenEnvironmentFilter` 的 stubbing/验证改为派生查询 `findByEnvironmentOrderByUploadedAtDesc`（不改 `KnowledgeBaseListService` 生产筛选语义、不动 Context 范围修正、不处理 Voice）。定点 `./gradlew :app:test --rerun`（KnowledgeBaseListServiceTest + 相关知识库服务单测）→ BUILD SUCCESSFUL（exit 0），ListFiltering 6/0。全量重跑 `./gradlew :app:test --rerun` → **579 tests / 10 failed / 0 errors / 3 skipped**（BUILD FAILED，exit≠0），剩余 10 个全部为 `VoiceInterviewIntegrationTest` Redis 环境阻塞（非 DevSupport 主线）。修正后口径：DevSupport 主线（含知识库列表筛选单测）全绿，但全量后端仍非全绿——**不得写成全量通过**。未提交/未推送；`.env`/wrapper/评测工件/Docker 卷未动。

### 阶段 2：文档中心与知识版本生命周期

- **要解决的用户问题**：管理员需要按项目/类型/版本管理文档，并知道每份资料的解析/索引状态。
- **工作范围**：复用现有上传、Tika 解析、RustFS/S3 存储、PostgreSQL 元数据和 Redis Stream 向量化流程；扩展知识库元数据（project、docType、version、source）并贯通处理状态机；文档中心展示上传/处理中/可检索/失败/停用等状态，支持重试与错误摘要；明确新旧版本关系、默认检索版本、失效资料退出检索；提供安全的本地演示数据与 bucket 初始化方式。
- **预期交付物**：可演示的文档上传/版本管理/索引状态流程；资料元数据与索引版本可追溯。
- **验收标准**：文档可按项目/类型/版本筛选；上传后可在本地对象存储找到文件、数据库找到元数据、异步任务完成后可检索；失败可观察且可重试；新旧版本行为明确。当同一主题存在不同版本且内容冲突时，文档中心应标识冲突并在检索结果中标注版本。
- **前置依赖 / 停止条件**：`fileHash` 全局唯一约束需决策——当前同一文件不能跨项目独立归属，schema 变更前需单独说明迁移与回滚。
- **阶段 2 进展（2026-10-04）**：采用“只加列、保留 fileHash 全局唯一”方案实现元数据与版本生命周期（迁移 `V20261005`）。决策已定：不放宽为复合唯一，故“同一文件跨项目独立归属”仍不支持（已知边界，如需再走 B 方案评估迁移+回滚）。版本机制：上传按 project+service+environment+docType+名称 派生 `documentKey`，新内容→versionNo 递增→旧版本 active=false 且删除其向量→退出检索（双保险：检索范围仅 active + 旧向量已删）。验证：后端知识库相关单测 + Testcontainers 真实 PG/pgvector 生命周期集成（确定性 mock Embedding）通过；全量后端 589/10 failed（全为 Voice Redis 环境阻塞）/3 skipped，DevSupport 相关全绿。真实模型端到端、真实对象存储/浏览器手工演示未做，标为未验证。
- **阶段 2 收尾（2026-10-04）**：补齐缺口——(1) 版本冲突"标识"：`KnowledgeBaseListService` 计算同一 documentKey 下 ≥2 个启用且 fileHash 不同 → `ListItemDTO.versionConflict`，文档中心显示"版本冲突"徽标（路线图仅要求"标识冲突 + 检索结果标注版本"，未定义硬拒绝规则，故仅实现标识；拒绝策略与"检索答案逐条标注版本"作为最小建议留待 Stage 3）。(2) 真实本地集成 `KnowledgeBaseUploadPipelineIntegrationTest`：Testcontainers 起 pgvector+Redis+**RustFS（生产同款 S3）**，走 `uploadKnowledgeBase` → 真实对象存储上传 + PG 元数据 + Redis Stream 生产/消费异步 COMPLETED + 确定性 embedding 向量索引 → 检索新版命中、旧版本停用后向量删除不再召回、重复上传幂等。(3) 前端 Playwright E2E `kb-doc-center.spec.ts`（mock）：状态/失败重试/停用/版本冲突徽标 + 上传元数据随表单提交。全量后端 593/10 failed（Voice Redis 阻塞）/3 skipped，DevSupport 全绿。
- **阶段 2 验收缺口修复 + 版本筛选链路补全（2026-10-04）**：(1) **检索来源标注版本**：`SourceReference` 扩展 `versionLabel`/`versionNo`/`documentKey` 字段，排查会话来源面板展示紫色版本标签（`frontend/src/utils/sourceDisplay.ts` 映射 + 单测 14/14）。(2) **文档中心支持按 project/docType/version 筛选**：后端列表 API 支持 project/docType/version 查询参数；前端文档中心新增对应下拉筛选，前端采用 client-side 过滤（`kbFilter.ts` 的 `versionFilter` 维度在已加载数据上过滤；后端 API 亦支持 version 参数但前端当前走客户端路径）。`KnowledgeBaseManagePage` 新增版本下拉；`kbFilter.ts` 新增 `versionFilter` 维度（`kbFilter.test.ts` 26/26）。(3) 测试补齐：后端定点 36/36 全绿；前端 build exit 0；前端单测 kbFilter 26/26 + sourceDisplay 14/14；E2E kb-doc-center 4/4（含版本筛选用例）+ chat-scope 3/3。全量后端 **601 tests / 588 passed / 10 failed（全为 VoiceInterviewIntegrationTest Redis 环境阻塞）/ 3 skipped**，DevSupport 相关全绿。**阶段 2 筛选链路已补全**（project/docType/version 后端 API + 前端 UI 均可用；来源快照版本标注已完成），但**版本冲突产品语义仍待决**：选项A 标识-only vs 选项B 多版本 active 需用户决策。**仍未验证**：真实付费模型端到端、生产部署。
- **阶段 2 版本冲突治理（2026-10-05）**：版本冲突从"标识-only"升级为全流程治理。数据模型：`V20261006` 迁移新增 `conflict` 列 + `normalized_version_label` + 部分唯一索引 `uq_kb_active_version`（同 documentKey+normalizedVersionLabel 只允许一个 active 行）。上传流程：同 documentKey + 同规范化 versionLabel + 不同 fileHash → 标记 `VERSION_CONFLICT`，不投递向量化。冲突解决：`POST /{id}/adopt` 采用冲突版本（停旧+激活+重新向量化）；`POST /{id}/abandon` 放弃冲突版本。前端：冲突筛选复选框 + 采用/放弃按钮（确认弹窗+loading+反馈）。测试：后端 614 tests (601 passed, 10 Voice Redis 遗留失败, 3 skipped)，DevSupport 全绿；前端单测 43/43，E2E 10/10。**阶段 2 验收通过**：冲突可触发、可展示、可处理、不污染检索。源码实现+自动化验证已完成，真实付费模型端到端未验证。
- **阶段 2 状态一致性修复（2026-10-05）**：三轮缺陷修复——(1) **并发冲突归属**：并发上传触发唯一索引竞争时，获胜方 active 版本不变，失败方存为独立冲突候选（VERSION_CONFLICT），不覆盖/不丢弃。(2) **adopt 异步状态机**：adopt 改为异步流程 CONFLICT→ADOPTING→向量化→promote→COMPLETED，失败回退 CONFLICT（旧版本始终 active），不再同步切换状态。(3) **ABANDONED 状态替代 FAILED**：放弃的冲突记录标记为 ABANDONED，前端不显示重试按钮。迁移 `V20261007__add_adopting_abandoned_states.sql` 新增 ADOPTING/ABANDONED CHECK 约束。测试：后端 **626 tests / 613 passed / 10 failed（Voice Redis 遗留）/ 3 skipped**，DevSupport 全绿；前端单测 **49/49**，E2E **14/14**。并发测试使用 Testcontainers 真实触发部分唯一索引竞争验证。

### 阶段 3：排查会话可靠闭环

- **要解决的用户问题**：开发者需要带来源证据的排查步骤，而不是通用问答框。
- **工作范围**：输出结构化回答骨架（问题理解 / 可能原因 / 按优先级排列的检查步骤 / 验证命令与预期结果 / 引用来源 / 适用版本 / 置信边界 / 缺失信息）；来源引用定位到文档版本 + chunk/章节，保留查询时来源快照，文档更新后历史回答仍可审计；无依据时明确拒答或追问，区分「信息不足 / 检索无结果 / 模型失败 / 用户取消 / 服务错误」；保持 SSE 契约（data → 持久化 → sources → done(status)），先持久化成功再发成功终态；会话按项目/环境/版本隔离。
- **预期交付物**：从选择项目上下文、描述问题、流式排查到记录结论的端到端 UI/API 流程。
- **验收标准**：SSE 流式回答含结构化骨架；来源可追溯到具体文档章节；真实浏览器可跑通成功/追问拒答/失败/取消路径；前端不把 partial 输出显示成成功；后端持久化契约由自动化测试覆盖。当同一故障在不同环境或版本的文档中存在冲突答案时，回答应标明各引用适用的版本/环境，并提示冲突。
- **设计理由**：先完成可演示的排查→记录→案例闭环，再优化检索质量。避免陷入评测细节而延迟用户可见价值交付。
- **阶段 3 结构化回答骨架实现（2026-10-06）**：(1) **Prompt 定义 8 段结构化回答骨架**：问题理解 → 可能原因 → 排查步骤 → 验证命令 → 引用来源 → 适用版本 → 置信边界 → 缺失信息。(2) **来源章节定位**：`SourceReference` 新增 `sectionTitle` 字段，从文档 heading 提取，前端来源面板显示「文档名 > 章节标题」。(3) **INSUFFICIENT_INFO 拒答细化**：`MessageStatus` 新增 `INSUFFICIENT_INFO` 状态，前端展示追问式提示而非通用失败。(4) **多版本来源冲突提示**：前端检测到多版本来源时显示冲突提示条。(5) 测试：后端定点 `RagChatControllerTest`（骨架 SSE）+ `KnowledgeBaseQueryServiceTest`（INSUFFICIENT_INFO 判定 + sectionTitle 提取）全绿；前端 `sourceDisplay.test.ts` sectionTitle 映射 + `kbStatus.test.ts` INSUFFICIENT_INFO 模式 + E2E INSUFFICIENT_INFO 提示全绿。全量后端 **650 tests / 637 passed / 10 failed（Voice Redis 遗留）/ 3 skipped**，DevSupport 相关全绿。

### 阶段 4：案例库审核发布

- **要解决的用户问题**：问题解决后需要沉淀为可复用、有审核记录的团队知识。
- **工作范围**：排查结束后由开发者提交「实际执行步骤、结果、最终原因、影响版本、解决状态」生成案例草稿；草稿标记 AI 生成部分与用户确认部分，不得自动发布；审核者可编辑/批准/退回，批准后进入案例库并写入审计记录（case_audit_logs），按适用项目/环境/版本参与检索；支持撤回/废弃过期案例并保留审计链。
- **版本语义说明**：案例 versionNo 为简单递增计数器，每次编辑同一条行记录，无版本历史表、无回滚/diff 支持。「版本与审计记录」实际指审计日志（case_audit_logs）记录每次状态变更（SUBMITTED/APPROVED/REJECTED/DEPRECATED/EDITED），而非完整版本快照。
- **预期交付物**：案例草稿、人工审核、发布检索（PUBLISHED 案例向量化后参与 RAG 检索）、更新/废弃的完整流程。
- **验收标准**：案例可从会话生成草稿；未审核草稿不进入正式检索；审核发布后可从案例库查到且来源清楚；状态转换有权限检查和测试。
- **已知缺口**：
  - 项目维度（project）暂不参与案例范围过滤：会话关联的 KB 虽有 project 字段，但该字段可空且无一致性保证，待数据模型完善后补充。
  - affected_versions 过滤已接入：当会话 KB 携带 versionLabel 时，会按 affected_versions 精确匹配案例；但案例 affectedVersions 与 KB versionLabel 的语义对齐依赖人工维护，无自动校验。
  - 案例范围实际支持 service/environment + affected_versions 精确匹配，不是完整的 project/service/environment/version 自动过滤。
- **验收证据（commit `353b8a8`）**：
  - 集成测试 20/20 PASS（Testcontainers + 确定性 mock embedding，零付费 API）
  - 真实向量删除失败路径：`@MockitoSpyBean VectorRepository` + `doThrow` 让 `deleteByCaseId()` 失败 → 验证 DB 持久化 status=DEPRECATED/active=false/vectorCleanupPending=true → 检索不命中
  - KB/案例合并走真实应用路径：调用 `KnowledgeBaseQueryService.retrieveAndMerge()` → 断言同时包含 KB 和 CASE 来源
  - affected_versions 精确匹配：版本匹配时可检索，不匹配时不可检索
  - 待清理查询 fail-closed：`findIdsWithVectorCleanupPending()` 异常时案例检索返回空列表

### 阶段 5：可评测检索优化

- **要解决的用户问题**：检索质量需要可度量，不凭主观感受堆技术。
- **工作范围**：固定并保护可复现的 baseline、数据集、金标、Embedding 模型/维度、chunk 策略、索引配置和报告哈希；扩充符合真实排障场景的评测问题；先实现 Hybrid Retrieval（向量 + 词法/BM25）与 RRF 融合，提供开关/可复现实验参数；只有评测指出排序仍有明确缺口时才引入 Reranker，保留向量-only / hybrid / hybrid+rerank 对照。
- **已有证据**：P1-C L1 baseline（Hit@5=93.75%，MRR@5=77.81%）和 heading-aware 候选（MRR@5=0.8542）— 两次付费外部验证通过。
- **⚠️ 口径护栏**：heading-aware 结果**不是受控单变量 A/B**，不可宣称切分带来因果提升。
- **预期交付物**：稳定 benchmark、版本化索引/语料、可比较检索策略、真实报告与错误案例。
- **验收标准**：基线可重复；每项策略有单变量对照与成本/延迟记录；索引绑定 embedding_model、dimension、index_version、chunk_strategy_version；无答案题不通过编造答案提升指标。所有真实付费评测均需单独授权。
- **阶段 5 首切片完成记录（2026-10-09）：案例驱动的回归评测（确定性版）**：
  - **后端**：新增 `interview.guide.modules.evalregression`——3 实体（CaseRegressionItemEntity/RunEntity/ResultEntity）+ `RegressionRunStatus` + 6 record DTO（RegressionItemDTO/RegressionResultDTO/RegressionRunDetailDTO/RegressionRunRequest/RegressionRunSummaryDTO/TopKSnapshotEntry）+ 3 repository + 4 服务（RegressionJsonCodec/EmbeddingMetadataResolver/CaseRegressionItemService/CaseRegressionRunService）+ `CaseRegressionController` + MapStruct `CaseRegressionMapper`；Flyway `V20261010` 建 case_regression_items/runs/results 三表（case_id REFERENCES cases(id)，item_id 刻意不加 FK 以容忍回归项变动后历史结果留存）；ErrorCode 13001/13002/13003。
  - **生命周期联动**：`CaseReviewService.vectorizeCaseContent` 返回证据 ID 列表，approve→`upsertOnPublish`；`CaseLifecycleService.deprecate`→`deactivateOnDeprecate`。
  - **端点**：`GET /api/eval/regression/items`、`POST /api/eval/regression/runs`、`GET /api/eval/regression/runs`、`GET /api/eval/regression/runs/{id}`，统一 `Result<T>`。
  - **确定性判定（零付费）**：query 由 title+problemDescription 拼接；keyPoints 从 resolutionSteps/resolutionResult 派生；passed = 期望证据 ID 命中 top-K 召回 且 keyPoints 在召回内容中逐字包含（空白归一后）；查询 embedding 用应用当前配置的 EmbeddingModel（demo/test 为确定性）。审查后低风险修复：默认 topK 5→20 且上限钳制 200；keyPoint 包含判定空白归一；V20261010 补 case_id 外键、删冗余索引、item_id 注明刻意不加 FK。
  - **前端**：`CaseRegressionPanel` + `EvalResultsPage` 回归区块 + `CaseDetailPage` 的 PUBLISHED「已纳入回归评测」徽标 + `api/eval.ts` 4 函数 + `types/eval.ts` + `utils/regression.ts`；E2E `case-regression.spec.ts`(10)，并修复既有 `case-library.spec.ts` 的过宽 mock 路由。
  - **测试与验证**：后端定点 evalregression 单测 46 + 集成 4、caselibrary 单测 22 + 集成 20 = 92 全绿；compileJava/compileTestJava exit 0；全量后端 741 tests / 728 passed / 10 failed（全为 VoiceInterviewIntegrationTest 连本机 Redis:6379 遗留失败，非本次、未修复未扩大）/ 3 skipped（Voice @Disabled），DevSupport 主线 343 全绿，V20261010 已在 Testcontainers pgvector/pg16 真实应用成功。前端 build exit 0；单测 regression 7/7；Playwright case-regression 10/10 + case-library 5/5。真实运行时离线验证（bootRun + docker compose 真实 PG/pgvector+Redis+RustFS + 真实 Flyway + 真实 OpenAI SDK HTTP embedding 打到本地确定性桩 + 真实 pgvector 检索 + REST + 落库 + Vite 前端/代理）：publish→生成回归项→运行通过→废弃→排除 完整闭环走通，9 次 embedding 调用全命中 127.0.0.1、零付费；浏览器真实数据走查（回归项/历史/详情/逐项字段/召回快照/新运行落库/案例徽标条件渲染均正确，无 console/网络错误）。
  - **⚠️ 数据泄漏问题与修复（2026-10-09）**：
    - **问题**：首切片（ba9ec83）存在数据泄漏——被测案例的向量本身在搜索集合中，且查询源自案例自身内容，导致案例"自我命中"。旧的 10/10 结果只能作为**管线验证通过**，不是有效检索质量证据。
    - **修复方案**：使用来源会话的原始 KB chunk ID 作为期望证据，评测时排除被测案例自身的 CASE 向量。数据流：来源消息 → `CaseDraftService.createDraft()` 解析 chunkIds → `CaseEntity.sourceChunkIds` → `CaseRegressionItemService.upsertOnPublish()` 优先使用 `caseEntity.getSourceChunkIds()` 作为 expectedEvidence。
    - **实现细节**：`SourceReference` record 新增 `chunkId` 字段，`RagChatSessionService` 在构建来源时调用 `doc.getId()` 填入；`rag_chat_messages.source_chunk_ids`、`cases.source_chunk_ids` 新增字段（V20261011 迁移）；`CaseRegressionRunService.evaluateItem()` 排除被测案例自身 CASE 向量；回归项 Entity 新增 `evidenceSource` 字段（"SOURCE"/"MISSING"/"SELF"）。
    - **旧案例处理**：旧案例（ba9ec83 之前发布）无来源 KB chunk ID，标记为 evidenceSource = "SELF" 或 "MISSING"，不伪造证据，评测结果仅供参考。
    - **隔离集成测试**：`CaseRegressionIsolationIntegrationTest` 5/5 通过——被测案例自身向量必须被排除、原始 KB gold chunk 命中时通过、无来源 KB gold 时明确标记、废弃案例不再参与回归、旧案例兼容性。evalregression 测试合计 55/55 通过。
  - **未验证边界与已知限制**：真实付费 embedding（DashScope text-embedding-v3）下语义检索质量/排序/top-K 精度未验证（确定性常量向量使相似度并列，仅证明管线正确 + 自身证据可召回 + 判定/落库/废弃排除逻辑正确）；聊天 assistant 消息→createDraft 离线闭环未验证（需 LLM，未付费跑）；全量后端非全绿（10 个 Voice Redis 遗留保留）；H1/L4（approve() @Transactional 内调 EmbeddingModel 外部 HTTP，改动前既存技术债、本次仅延续，建议移出事务或改 Redis Stream 异步）；M1（回归复用全局 KB retrieveAndMerge，kbIds 传空→KB 分支不带过滤，已用默认 topK 提升 + 上限钳制缓解挤出假失败）；M3（要点被物理切分到不相邻 chunk 仍可能假失败，空白归一只解决 \n 拼接）；L1（无 FAILED 终态，13003 暂未使用）；L3（逐项结果未快照 expectedEvidence，UI 经实时 items 反查，案例重发布产生新 Document UUID 会使历史高亮漂移，但 passed 布尔已落库、结论不受影响）；project 维度案例过滤延期（schema 级缺口，另立切片）。

### 阶段 6：可靠性、部署与简历级交付

- **要解决的用户问题**：新用户能按文档在本地启动、看懂架构并重复演示。
- **工作范围**：把 demo profile 模式（当前只覆盖 Resume）扩展到 RAG 问答与向量化；检查 Redis Stream 的 ACK、幂等、重试、失败状态与重启恢复；Docker Compose 一键启动依赖并给出 bucket 初始化；准备脱敏样例资料与 3–5 个演示故障；更新 README（架构图、技术栈、启动方式、演示路径、数据流、安全配置和限制）。
- **预期交付物**：可复制启动的开发/演示环境、可复现演示数据、架构图、清晰 README、测试与演示证据。
- **验收标准**：新人 clone 后 30 分钟内能跑通完整 Demo；不依赖隐藏手工步骤；没有泄露凭据；demo 与真实 AI 能力的边界明确。

---

## 5. 现有改进复用分析

本轮已完成的工作对 DevSupport 新主线的复用价值：

| 现有改进 | 复用价值 | 说明 |
|----------|---------|------|
| 知识库模块（17 端点） | **高 → 文档中心** | 上传/解析/向量化/分类/标签/搜索/统计/重新向量化全部可复用 |
| RAG Chat（8 端点 + SSE 契约） | **高 → 排查会话** | 流式问答 + 来源快照 + 会话管理直接复用 |
| LLM Provider 管理（16 端点） | **高 → AI 后端** | 零改造可用 |
| Demo Profile 模式 | **中-高 → 本地演示** | 模式可推广到 RAG/向量化，但当前只覆盖 Resume |
| Testcontainers 集成测试骨架 | **高 → 测试基础设施** | pgvector:pg16 + redis:7-alpine + @DynamicPropertySource + @MockitoBean |
| P1-C 评测框架 | **中 → 评测结果** | 指标/冻结工件/预算门控高价值，但展示层为零 |
| Resume 纵向切片（demo + 测试 + 前端路由） | **低 → 遗留模块** | 代码保留，不进主导航；demo profile 模式可借鉴 |
| Voice 测试修复（3 文件 17 用例） | **低 → 遗留模块** | 测试债务清理，保留 |

---

## 6. 当前唯一的下一步

**阶段 5 首切片「案例驱动的回归评测（确定性版）」已完成（2026-10-09，离线验证通过）。** 下一步二选一，均需用户/审查确认后再启动，不擅自扩大范围：

- **阶段 5 主体：Hybrid Retrieval（向量 + BM25）/ RRF 融合 / Reranker 对照实验**——受 §8.3「未授权不跑付费 LLM/Embedding」门槛约束，需单独付费对照授权后方可执行；回归评测确定性框架已就绪，可作为检索策略变更前后对比的载体。
- **补齐 project 维度案例过滤**——schema 级缺口（cases/KB 的 project 字段可空且无一致性保证），需迁移 + 实体 + 元数据 + 过滤四处联动，另立切片。

理由：核心闭环（文档中心 → 排查会话 → 案例库 → 评测结果）四导航已连通，回归评测把「排查会话 → 案例库 → 评测结果」串成可判定通过/失败的确定性闭环；进一步检索质量优化需付费授权，或先补数据模型缺口，两者都不应擅自启动。

---

## 7. 增量能力候选与实施门槛

以下能力候选在主线核心闭环可演示后按需启动，不作为必做任务。基础观测和流式可靠性随主线做，其余扩展在有明确失败案例或演示需求时启动。每项标注解决什么问题、首次交付范围和验收门槛。

**优先顺序**：先完成 DevSupport 核心闭环 → 再做只读 Tool-use → 需要展示跨工具接入时加一个本地 MCP 实例。

### AI Agent 与只读 Tool-use（Function Calling）

- **解决什么问题**：排查时 AI 只能给文本建议，无法主动查询服务状态或读取日志来辅助定位。
- **首次交付范围**：受限的只读工具——查询服务健康、读取脱敏日志、检查配置；暂不让模型执行任意命令或写入操作。一个故障场景能完成"调用工具 → 展示执行结果 → 结合文档给出排查建议"。
- **验收门槛**：工具调用次数、超时和失败都有边界处理；执行结果回注对话上下文并可在 UI 展示；Spring AI Function Calling 与 MCP 可以衔接，不必做成两套独立业务实现。
- **引入时机**：阶段 3 排查会话闭环稳定后，是最能体现 DevSupport 区分度的扩展。
- **边界声明**：Agent/Tool-use 是有边界的辅助排查；全自主 Agent 不在首版范围（参见 §2 非目标）。

### MCP（Model Context Protocol）本地实例

- **解决什么问题**：当需要展示跨系统工具接入（如 Jira、Grafana、K8s）时，需要标准化的工具暴露协议。
- **首次交付范围**：一个本地 MCP Server 与 DevSupport Client 真正连通，将上述只读工具通过 MCP 暴露；工具结果进入会话且可追踪。不对接 Jira/Grafana/K8s 全量 API。
- **验收门槛**：本地 MCP Server 启动并可被 DevSupport 发现；至少一个工具通过 MCP 协议调用成功，结果可在会话中追溯。
- **引入时机**：接在只读 Tool-use 能力之后，在有跨工具演示需求时启动。

### 可观测性（OpenTelemetry）

- **解决什么问题**：AI 调用链（检索 → 模型 → 工具 → 响应）缺乏端到端追踪，出问题时无法定位瓶颈。
- **首次交付范围**：一次请求能串起检索、模型、工具和响应；能看到耗时、错误及可获得的 Token 用量。优先复用 Spring AI / Spring 现有观测能力，再接 OTel 展示。默认不采集敏感正文。
- **验收门槛**：一次排查请求产生可追踪的 Span 链；延迟和 Token 用量可在观测面板查看；错误 Span 有明确标记。
- **引入时机**：随阶段 3 排查闭环逐步加入，避免从头造埋点框架。参考 [Spring AI Observability](https://docs.spring.io/spring-ai/reference/observability/index.html)。

### 本地 LLM 支持（Ollama/vLLM）

- **解决什么问题**：隐私敏感场景需要本地推理能力，不依赖外部 API。
- **首次交付范围**：先验证现有 LLM Provider 能否接本地 OpenAI 兼容端点（Ollama 提供 OpenAI 兼容 API），再决定是否新增适配。Chat 与 Embedding 分开推进。
- **验收门槛**：本地 Chat 完成一次有引用的排查；Embedding 模型变更使用独立索引，不能混入现有 1024 维索引。
- **引入时机**：Provider 管理已支持多后端，增量小，可在阶段 6 可靠性工作中验证。

### Advanced RAG 模式

- **解决什么问题**：基础 RAG 在特定失败案例上检索质量不够（如复杂多步问题、检索结果不相关时无法自修正）。
- **首次交付范围**：首次最多选一个有明确失败案例的策略（Query Decomposition / Self-RAG / Corrective RAG 三选一），不全部实现。固定失败样例，比较改善幅度、回退率、延迟和请求数。
- **验收门槛**：限定最大重试/改写次数；有对照实验数据（baseline vs 新策略），证明改善且不退化；成本/延迟在可接受范围内。
- **引入时机**：保留候选，在阶段 5 检索优化中按需启动。

### 案例状态机与审计日志

- **解决什么问题**：案例从草稿到审核到发布需要追查谁在何时做了什么操作，拒绝非法状态转换。
- **首次交付范围**：案例状态机（草稿 → 待审核 → 已发布 / 已退回 / 已废弃）+ 审计日志表，记录操作人、时间、前后状态和备注。不引入完整 Event Sourcing / CQRS。
- **验收门槛**：能追查谁在何时把案例从草稿变成审核、发布或废弃；非法状态转换被拒绝并有测试覆盖。
- **引入时机**：随阶段 4 案例库开发一并实现，作为案例库的基础能力而非独立技术引入。

### 流式背压与可靠性

- **解决什么问题**：SSE 流式回答在慢客户端或断连时，服务端可能无限缓冲导致内存增长；取消操作可能不释放资源。
- **首次交付范围**：有界缓冲、超时、取消和资源释放。重点是实际链路验证，不是只添加一个 Reactor 操作符。
- **验收门槛**：慢客户端和断连测试下内存不持续增长；取消能释放资源；文本不被静默丢弃。参考 [Reactor Flux 有界缓冲](https://projectreactor.io/docs/core/release/api/reactor/core/publisher/Flux.html)。
- **引入时机**：属于排查会话的基础质量要求，随阶段 3 主线一并实现。

### 案例驱动的回归评测

- **解决什么问题**：文档或检索策略更新后，无法确认已审核发布的真实故障案例是否仍能被正确检索和回答。
- **首次交付范围**：人工审核并发布故障案例后，从中选取 3–5 个脱敏的问题、预期证据和解决要点，形成版本化回归题。文档或检索策略更新时，自动检查这些真实故障题是否仍能找到正确证据、给出可追溯回答。首版交付一份更新前后对比报告。
- **验收门槛**：至少 3 个审核过的案例转化为回归题；每次检索策略或文档变更后能自动生成对比报告；回归题包含预期证据引用，可判定通过/失败。首版可用预期证据 ID 和解决要点做确定性检查，是否增加模型评判留到实施时决定。参考 [Spring AI Evaluation Testing](https://docs.spring.io/spring-ai/reference/testing/evaluations.html)。
- **引入时机**：阶段 4 案例库审核发布完成后，作为可选候选启动。此能力将「排查会话 → 案例库 → 评测结果」三个主导航连成一条实际有用的闭环。

---

## 8. Agent 执行边界

1. 先核对仓库状态与未提交改动，不丢弃、不覆盖、不混合提交。
2. 不擅自添加 Hybrid、Reranker、GraphRAG、Agent 等高级组件——先用 benchmark 和用户流程证明需要。
3. 未授权不跑付费 LLM/Embedding 调用；默认使用隔离 demo/mock 或本地服务。
4. 不读取、不回显 `.env`/`.env.eval` 中的密钥。
5. 不删除卷、不 reset/clean/force push，保留本机 `gradle-wrapper.properties` 受保护改动。
6. 推送目标：`git push devsupport master`（两个 remote 当前指向同一 URL，但遵守文档约定，不推送 `origin`）。
7. 完成阶段先给精简结果和证据，再等用户/审查 Agent 确认；不擅自扩大下一阶段范围。

---

## 9. 仓库环境事实（供 Agent 参考）

> 以下为核对时的本机快照（2026-10-04）。

- **Remote 配置**：`origin` 和 `devsupport` 均指向 `https://github.com/HyCheng-reborn/devsupport-platform.git`。
- **分支同步**：本地 `master` 与 `devsupport/master` 同步（ahead 0 / behind 0）；与 `origin/master` 的 ahead 10 是远程跟踪引用陈旧，非真实领先。
- **Docker 容器**：`interview-postgres`(5432)、`interview-redis`(6379)、`interview-rustfs`(9000-9001) 均 healthy。
- **孤儿卷**：`interview-guide_{postgres,redis,rustfs}_data`（Compose 项目名变更后遗留），**不得删除**。
- **密钥文件**：`.env` 和 `.env.eval` 均存在（不读取内容）。
- **命名保留**：Java 根包 `interview.guide`、数据库名 `interview_guide`、S3 bucket `interview-guide`、容器名前缀 `interview-*`、本地配置目录 `~/.interview-guide/` 均保留不变（详见 `devsupport-phase1-design.md` §6）。
