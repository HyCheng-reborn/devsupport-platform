**2026-10-08 -- Stage 4 案例库审核发布：数据模型 + 状态机 + 前端页面 + 测试**
- DB 迁移：cases 表 + case_audit_logs 表
- CaseStatus 枚举：DRAFT/PENDING_REVIEW/PUBLISHED/REJECTED/DEPRECATED
- CaseDraftService：从会话消息生成草稿（区分 AI/人工内容）
- CaseReviewService：提交/批准/退回 + 状态机约束
- CaseLifecycleService：废弃 + 列表/详情查询
- CaseLibraryController：8 个 REST 端点
- 前端：案例列表页 + 详情页 + 从会话生成按钮
- 后端测试：22 单测 + 6 集成测试（CaseLibraryServiceTest + CaseLibraryIntegrationTest）
- 前端测试：3 单测 (caseStatus) + 5 E2E
- abandon 竞态测试 ACK 断言补强
- 全量后端 **679 tests / 666 passed / 10 failed（Voice Redis 遗留）/ 3 skipped**，DevSupport 相关全绿
- 前端单测 **80/80**，build 通过
- 主要文件：`V20261008__create_cases_tables.sql`、`CaseEntity.java`、`CaseAuditLogEntity.java`、`CaseStatus.java`、`CaseDraftService.java`、`CaseReviewService.java`、`CaseLifecycleService.java`、`CaseLibraryController.java`、`CasesPage.tsx`、`CaseDetailPage.tsx`、`caseStatus.ts`

**2026-10-07 -- sectionTitle 扩展至 h1/h2/h3 + retry 状态机条件原子迁移 + INSUFFICIENT_INFO 流式路径修正 + 测试数字校正**
- `sectionTitle` 提取范围从 h2 扩展至 h1/h2/h3，确保所有层级文档标题均可定位
- retry 状态机条件原子迁移：`resetToAdoptingForRetry` 作为原子条件 UPDATE，替代非原子的状态切换
- INSUFFICIENT_INFO 流式路径修正：确保信息不足状态在流式输出路径中正确传播
- `consumerClaim_afterAbandon` 测试改用真实 `abandonVersion()` 调用，不再手动设置状态
- 测试数字校正（JUnit XML 核实）：定点 7 个测试类合计 **115 tests / 0 failed / 0 skipped**（RagChatControllerTest 11 + KnowledgeBaseQueryServiceTest 42 + RagChatSessionServiceTest 15 + RagChatSseIntegrationTest 4 + KnowledgeBaseConflictServiceTest 14 + KnowledgeBaseUploadPipelineIntegrationTest 22 + KnowledgeBaseUploadServiceTest 7；RagChatServiceTest 不存在）；全量后端 **650 tests / 637 passed / 10 failed（Voice Redis 遗留）/ 3 skipped**；前端单测 **113/113**，E2E **17/20**（3 voice 遗留）

**2026-10-06 -- Stage 3 结构化排查回答骨架 + 来源章节定位 + 版本冲突提示 + INSUFFICIENT_INFO 拒答细化**
- Prompt 定义 8 段结构化回答骨架：问题理解 → 可能原因 → 排查步骤 → 验证命令 → 引用来源 → 适用版本 → 置信边界 → 缺失信息
- `SourceReference` 新增 `sectionTitle` 字段，从文档 heading 提取；前端来源面板显示「文档名 > 章节标题」
- `MessageStatus` 新增 `INSUFFICIENT_INFO` 拒答细化，前端展示追问式提示而非通用失败
- 前端多版本来源时显示冲突提示条
- 测试：后端全量 **644 tests**（631 passed, 10 Voice Redis 遗留失败, 3 skipped），DevSupport 全绿；前端单测 **113/113**，E2E **17/20**（3 voice-interview 遗留失败，非 DevSupport）
- 主要文件：`rag-chat.st`、`SourceReference.java`、`MessageStatus.java`、`KnowledgeBaseQueryService.java`、`RagChatController.java`、`sourceDisplay.ts`、`kbStatus.ts`、`ChatSessionsPage.tsx`

**2026-10-06 -- 消费者原子领取 + adopt 测试轮询终态**
- 消费者原子领取：`tryClaimForProcessing` 条件 UPDATE（ADOPTING/PENDING→PROCESSING），防止 abandon 时序窗口内消费者重复领取
- 消费者原子完成：`tryCompleteAdopt` 条件 UPDATE（PROCESSING→COMPLETED + promote），保证完成与 promote 原子性
- abandon 时序窗口测试：消费者初检后 abandon 抢先 → 领取必须失败，验证 abandon 在消费者处理前的安全窗口
- adopt 测试改用 `awaitStatus` 轮询等待终态（移除 `Thread.sleep`），提升测试稳定性和确定性
- 测试：后端全量 **634 tests**（621 passed, 10 Voice Redis 遗留失败, 3 skipped），DevSupport 全绿；前端单测 **99/99**，E2E **14/17**（3 voice-interview 遗留失败，非 DevSupport）
- 主要文件：`KnowledgeBaseRepository.java`、`VectorizeStreamConsumer.java`、`KnowledgeBaseConflictService.java`、`KnowledgeBaseConflictServiceTest.java`、`KnowledgeBaseUploadPipelineIntegrationTest.java`

**2026-10-05 -- DevSupport adopt/abandon 并发竞态修复 + Redis Stream 消息数断言**
- abandon 原子 CAS：`tryStartAbandon` 条件 UPDATE 确保只有 CONFLICT/ADOPTING 状态可放弃，防止并发 abandon 重复投递
- adopt/abandon 互斥验证：CountDownLatch 制造并发竞争，XOR 测试确保 adopt 和 abandon 互斥（只有一个能成功）
- Consumer promote 保护：向量化消费者 promote 前检查状态是否为 ABANDONED，防止已放弃记录被重新激活为 COMPLETED
- 并发 adopt 测试增加 Redis Stream 消息数断言：确保并发 adopt 只投递一次向量化消息（stream size = 1）
- 测试：后端 targeted KB tests **18/18**（KnowledgeBaseConflictServiceTest + KnowledgeBaseUploadPipelineIntegrationTest + KnowledgeBaseUploadServiceTest + KnowledgeBaseServiceTest + KnowledgeBaseResolveContextTest + RagChatServiceTest + RagChatControllerTest），DevSupport 全绿；前端单测 **99/99**，E2E **14/17**（3 voice-interview 遗留失败，非 DevSupport）
- 主要文件：`KnowledgeBaseConflictService.java`、`KnowledgeBasePersistenceService.java`、`VectorizeStreamConsumer.java`、`KnowledgeBaseConflictServiceTest.java`

**2026-10-05 -- DevSupport 版本冲突治理：DB 迁移 + 上传冲突检测 + adopt/abandon API + 前端冲突展示和操作**
- 数据模型：`V20261006` 迁移新增 `conflict` 列 + `normalized_version_label` + 部分唯一索引 `uq_kb_active_version`（同 documentKey+normalizedVersionLabel 只允许一个 active 行）
- 上传流程：同 documentKey + 同规范化 versionLabel + 不同 fileHash → 标记 `VERSION_CONFLICT`，不投递向量化
- 冲突解决：`POST /{id}/adopt` 采用冲突版本（停旧+激活+重新向量化）；`POST /{id}/abandon` 放弃冲突版本
- 前端：冲突筛选复选框 + 采用/放弃按钮（确认弹窗+loading+反馈）
- 测试：后端 **614 tests**（601 passed, 10 Voice Redis 遗留失败, 3 skipped），DevSupport 全绿；前端单测 **43/43**，E2E **10/10**
- 阶段 2 验收通过：冲突可触发、可展示、可处理、不污染检索。源码实现+自动化验证已完成，真实付费模型端到端未验证
- 主要文件：`V20261006__add_conflict_and_normalized_version.sql`、`KnowledgeBaseEntity.java`、`KnowledgeBaseRepository.java`、`KnowledgeBaseUploadService.java`、`KnowledgeBaseVersionService.java`、`KnowledgeBaseController.java`、`KnowledgeBaseConflictService.java`、`KnowledgeBaseManagePage.tsx`、`knowledgebase.ts`

**2026-10-05 -- DevSupport 状态一致性修复：并发冲突归属 + adopt 异步状态机 + ABANDONED 状态**
- 缺陷1修复：并发冲突时获胜 active 版本不变，失败上传存为独立冲突候选（VERSION_CONFLICT），不覆盖/不丢弃
- 缺陷2修复：adopt 改为异步状态机 CONFLICT→ADOPTING→向量化→promote→COMPLETED，失败回退 CONFLICT，旧版本始终 active
- 缺陷3修复：ABANDONED 状态替代 FAILED，放弃记录不显示重试按钮
- 迁移：`V20261007__add_adopting_abandoned_states.sql` 新增 ADOPTING/ABANDONED CHECK 约束
- 测试：后端 **626 tests**（613 passed, 10 Voice Redis 遗留失败, 3 skipped），DevSupport 全绿；前端单测 **49/49**，E2E **14/14**
- 并发测试：Testcontainers 真实触发部分唯一索引竞争验证
- 主要文件：`V20261007__add_adopting_abandoned_states.sql`、`VectorStatus.java`、`AsyncTaskStreamConstants.java`、`VectorizeStreamConsumer.java`、`VectorizeStreamProducer.java`、`KnowledgeBaseConflictService.java`、`KnowledgeBasePersistenceService.java`、`KnowledgeBaseUploadService.java`、`KnowledgeBaseManagePage.tsx`、`knowledgebase.ts`

**2026-10-04 -- DevSupport 版本筛选链路补全：KnowledgeBaseManagePage 新增版本下拉、kbFilter 新增 versionFilter 维度、测试补齐**
- 版本筛选链路补全：`KnowledgeBaseManagePage` 新增版本下拉筛选控件；`kbFilter.ts` 新增 `versionFilter` 维度，前端采用 client-side 过滤（在已加载数据上按版本号过滤；后端 API 亦支持 version 参数但前端当前走客户端路径）
- 测试补齐：`kbFilter.test.ts` 26/26（新增 versionFilter 维度用例）；E2E `kb-doc-center.spec.ts` 4/4（新增版本筛选下拉可见且可筛选列表用例）
- 阶段 2 状态更新：筛选链路已补全（project/docType/version 后端 API + 前端 UI 均可用），但**版本冲突产品语义仍待决**（选项A 标识-only vs 选项B 多版本 active 需用户决策），不称“验收通过”或“全部完成”
- 边界：真实付费模型端到端、生产部署未验证；未提交、未推送；未改 .env/wrapper/评测工件/Docker 卷
- 主要文件：`KnowledgeBaseManagePage.tsx`、`kbFilter.ts`/`.test.ts`、`kb-doc-center.spec.ts`

**2026-10-04 -- DevSupport 阶段 2 验收缺口修复：来源快照版本标注 + 文档中心 project/docType 筛选 + 测试补齐**
- 检索来源标注版本：`SourceReference` 扩展 `versionLabel`/`versionNo`/`documentKey` 字段；前端 `sourceDisplay.ts` 映射来源快照为展示视图，排查会话来源面板展示紫色版本标签；新增 `sourceDisplay.test.ts` 14/14
- 文档中心支持按 project/docType 筛选：后端列表 API 新增 project/docType 查询参数；前端文档中心新增对应下拉筛选控件；`kbFilter.test.ts` 18/18
- 测试：后端定点 36/36 全绿；前端 `pnpm run build` exit 0；前端单测 kbFilter 18/18 + sourceDisplay 14/14；E2E kb-doc-center 3/3 + chat-scope 3/3；全量 `./gradlew :app:test` → 601 tests / 588 passed / 10 failed（全为 `VoiceInterviewIntegrationTest` Redis 环境阻塞，非 DevSupport）/ 3 skipped
- 阶段 2 验收缺口已修复：版本标注 + 筛选链路 + 冲突标识（版本冲突产品语义待决：选项A 标识-only vs 选项B 多版本 active 需用户决策）
- 边界：真实付费模型端到端、生产部署未验证；未提交、未推送；未改 .env/wrapper/评测工件/Docker 卷
- 主要文件：`SourceReference.java`、`RagChatSessionService.java`、`KnowledgeBaseController.java`、`KnowledgeBaseListService.java`、`SourceReferenceTest.java`、`RagChatSessionServiceTest.java`、`KnowledgeBaseListServiceTest.java`、`RagChatControllerTest.java`、`RagChatSseIntegrationTest.java`、`sourceDisplay.ts`/`.test.ts`、`kbFilter.ts`/`.test.ts`、`KnowledgeBaseManagePage.tsx`、`KnowledgeBaseQueryPage.tsx`、`ChatSessionDetailPage.tsx`、`knowledgebase.ts`、`ragChat.ts`、`chat-scope.spec.ts`、`kb-doc-center.spec.ts`

**2026-10-04 -- DevSupport 阶段 2 收尾：版本冲突标识 + 真实 S3/Redis Stream 集成 + 前端 E2E**
- 版本冲突“标识”（路线图仅要求“文档中心标识冲突 + 检索标注版本”，未定义硬拒绝）：`KnowledgeBaseListService` 计算同 documentKey ≥2 个启用且 fileHash 不同 → `KnowledgeBaseListItemDTO.versionConflict`；文档中心“版本冲突”徒章。拒绝策略与“检索答案逐条标注版本”作为最小建议留待 Stage 3，未擅自扩大
- 真实本地集成 `KnowledgeBaseUploadPipelineIntegrationTest`（Testcontainers pgvector + Redis + **RustFS（生产同款 S3）**，确定性 mock Embedding）：`uploadKnowledgeBase` → 真实对象存储上传 + PG 元数据 + Redis Stream 生产/消费异步 COMPLETED + 向量索引 → 按项目/环境/版本检索命中；新版本替换后旧版本 active=false 且删向量→不再召回；相同内容重试幂等（duplicate=true，无新行）。独立容器 + 独立 bucket，未触碰现有 dev 卷/库
- 前端 Playwright E2E `kb-doc-center.spec.ts`（`page.route` mock，无后端）：状态徒标 失败/已停用/版本冲突、失败重试（POST revectorize）、停用（PUT retire）、上传元数据随 multipart 提交
- 测试：新增 `KnowledgeBaseUploadPipelineIntegrationTest`(2/0)、`KnowledgeBaseListServiceTest$VersionConflict`(2/0)；全量 `./gradlew :app:test --rerun` → 593 tests / 10 failed / 0 errors / 3 skipped（BUILD FAILED，10 全为 `VoiceInterviewIntegrationTest` 本机无 Redis 环境阻塞，非主线，不修无关 Voice）；前端 `pnpm run build` exit 0、kb-status 8/0、kb-filter 9/0、chat-scope 8/0、kb-doc-center E2E 2/2
- 边界：真实付费模型端到端、生产部署、检索答案逐条版本标注未做；未提交、未推送；未改 .env/wrapper/评测工件/Docker 卷；未开始 Stage 3

**2026-10-04 -- DevSupport 阶段 2：文档中心与知识版本生命周期（代码完成 · 本地集成通过）**
- 迁移 `V20261005`：`knowledge_bases` 仅 ADD 可空列 project/doc_type/source/version_label/document_key + version_no(默认1) + active(默认true) + 3 个索引；不改 file_hash 全局唯一（内容去重不变）。同一文件跨项目独立归属仍不支持（已知边界）
- 元数据贯通 UI→API→DB→异步→检索过滤：上传接收并持久化 project/docType/source/versionLabel；`resolve-context` 新增 project 且仅返回 active=true；ContextKbItem/ListItemDTO 补字段
- 版本生命周期：`KnowledgeBaseVersionService` 在新版本落库后停用同 documentKey 旧版本(active=false)+删除其向量(`VectorRepository.deleteByKnowledgeBaseId`)，双保险保证旧版退出检索；`PUT /api/knowledgebase/{id}/retire` 手动停用
- 文档中心：展示 处理中/待处理/可检索/失败/已停用 + 项目/版本列 + 停用操作（`kbStatus.deriveStatus` 纯函数 + 8 单测）；上传页新增资料元数据面板
- 测试：KnowledgeBaseVersionServiceTest(4)、KnowledgeBaseUploadServiceTest（去重/versionNo 递增/显式 documentKey）、KnowledgeBaseContextResolveTest（active+project 2）、Testcontainers 真实 PG/pgvector 的 KnowledgeBaseLifecycleIntegrationTest(2/0，确定性 mock Embedding) 全绿；DevSupport 知识库包整包 :app:test 通过 exit 0
- 全量后端：589 tests / 10 failed / 0 errors / 3 skipped（BUILD FAILED，10 全为 VoiceInterviewIntegrationTest 因本机无 Redis:6379 环境阻塞，非 DevSupport 主线）；不写成全量通过
- 边界：真实模型(Embedding/LLM) 端到端、真实 RustFS/S3 + 浏览器手工演示未做（禁用付费 API/不启停无关服务）；未提交、未推送；未改 .env/wrapper/评测工件/Docker 卷；未开始阶段3

**2026-10-04 -- DevSupport 阶段 1 口径修正 + 修复 KnowledgeBaseListServiceTest 2 个测试桩漂移（不改生产语义）**
- 更正上一轮验收口径：此前把 12 个后端失败笼统记为“与本修正无关”不完整——其中 2 个 `KnowledgeBaseListServiceTest$ListFiltering`（`environmentFilterOnly`、`nullRowsExcludedWhenEnvironmentFilter`）是 **DevSupport 知识库列表筛选的既有测试桩漂移**（自 `5798f62` 实现改走 `findByEnvironmentOrderByUploadedAtDesc` 后测试仍 stub `findAllByOrderByUploadedAtDesc`），属主线，不能表述为“无主线相关失败”
- 仅调整这两个用例的 stubbing/验证，改 stub 派生查询 `findByEnvironmentOrderByUploadedAtDesc` 返回精确匹配行；**不改** `KnowledgeBaseListService` 生产筛选语义、不动 Context 范围修正、不处理 `VoiceInterviewIntegrationTest`、不启停 Docker 卷
- 定点 `./gradlew :app:test --rerun --tests KnowledgeBaseListServiceTest + KnowledgeBaseContextResolveTest + RagChatSessionContextTest + RagChatSessionServiceTest + SourceReferenceSnapshotTest + RagChatControllerTest` → BUILD SUCCESSFUL（exit 0），`ListFiltering` 6/0
- 全量重跑 `./gradlew :app:test --rerun` → 579 tests / 10 failed / 0 errors / 3 skipped（BUILD FAILED，exit≠0）；剩余 10 个全部为 `VoiceInterviewIntegrationTest` 因本机无 Redis:6379 的环境阻塞（非 DevSupport 主线）。修正后：DevSupport 主线相关全绿，但全量后端仍非全绿，不写成全量通过
- 未提交、未推送；`.env`/`gradle-wrapper.properties`/评测工件/Docker 卷未改

**2026-10-04 -- DevSupport 阶段 1 最终验收：全量后端 + mock 浏览器 E2E + 检索范围/来源一致性核对**
- 后端全量 `./gradlew :app:test --rerun --no-daemon`：579 tests / 12 failed / 0 errors / 3 skipped，BUILD FAILED（exit≠0）
- DevSupport 主线相关全绿：RagChatSessionContextTest（ScopeNarrowing/EmptyContextError/ContextResolution/BackwardCompatibility）、KnowledgeBaseContextResolveTest、RagChatSessionServiceTest、SourceReferenceSnapshotTest、RagChatControllerTest；经 Testcontainers 真实 PostgreSQL 的 RagChatSseIntegrationTest(3/0)、KnowledgeBaseRepositoryIntegrationTest(5/0)、RateLimitIntegrationTest(4/0) 通过（Docker 可用）
- 12 个失败均非本次范围修正引入：10 个 VoiceInterviewIntegrationTest（RedisConnectionException，遗留模块需本机 Redis:6379 未启动，环境阻塞）+ 2 个 KnowledgeBaseListServiceTest$ListFiltering（PotentialStubbingProblem：environmentFilterOnly / nullRowsExcludedWhenEnvironmentFilter 仍 stub findAllByOrderByUploadedAtDesc，实现自 5798f62 起改调 findByEnvironmentOrderByUploadedAtDesc；该测试文件未被 createSession 修正提交改动，属既有漂移）。3 个 skipped 为遗留 Voice 单测
- 新增 mock 浏览器 E2E frontend/e2e/chat-scope.spec.ts：Playwright 1.62.1 + Chromium（npx playwright install chromium 补 v1234），page.route 全量 mock /api（无后端、无付费 LLM/Embedding）。2 用例通过：选 service 后展示“2 个文档”→缩小为“已选 1 个文档”→POST /api/rag-chat/sessions 请求体 knowledgeBaseIds=[1] 且 service=payment，范围外 auth 文档不在弹窗；空范围明确提示、无计数、无创建按钮、无勾选框
- 一致性核对：createSession 持久化的最终集合 = 前端提交集合（ScopeNarrowing 交集断言 + E2E 请求体双重证明）；getStreamAnswer 以 session.getKnowledgeBaseIds() 为检索范围，来源快照 service/environment 由该集合的 KB 实体映射（carriesTagsAndNullTags、SourceReferenceSnapshotTest、RagChatSseIntegrationTest）
- 停在问题上不扩大修复：ListFiltering 2 个漂移测试本轮未改；未提交、未推送；未改 .env/wrapper/评测工件/Docker 卷

**2026-10-04 -- DevSupport 阶段 1 复核修正：排查会话检索范围一致性 + 上下文文案 + 路线图口径**
- 后端 `RagChatSessionService.createSession` 语义由"显式 kbIds ∪ service/environment 解析"改为"以 service/environment 为限制范围、显式只在范围内缩小（取交集）"：范围外显式 ID 被安全排除、范围内取消勾选的不会被后端加回、上下文范围为空或与显式选择无交集时报错（BAD_REQUEST），绝不退回全量检索；未提供 service/environment 时保持旧的显式 `knowledgeBaseIds` 行为。后端为最终校验方，不只靠前端隐藏选项
- 前端 `ChatSessionsPage`：KB 勾选限制在解析范围内（只能缩小）；"当前检索范围：N 个文档"的 N 与最终会话实际使用的 KB 集合一致；新增 `utils/chatScope.ts`（`selectableIds`/`narrowScope` 纯函数，与后端交集语义一致）及 8 条 `node:test` 单测
- `ContextSelector`：首选项标签由"项目/服务"改为"服务"；project/version 禁用态文案改为"项目/版本维度：阶段 2 才支持"；空范围显示明确提示
- 文档中心（`DocsCenterPage`/`KnowledgeBaseManagePage`）：移除挂载但不影响文档列表、也不传到会话的无效 `ContextSelector`；文档列表仍由页面自带 service/environment 下拉筛选（真实行为）
- 测试：`RagChatSessionContextTest` 由并集断言改为限制范围/缩小/无交集/空范围 4 类用例；修复 2 个与实现漂移的既有 environment 单测（`KnowledgeBaseContextResolveTest` 之前 stub `findAllByOrderByUploadedAtDesc` 但实现已改用 `findByEnvironmentOrderByUploadedAtDesc`）
- 文档口径修正：`DEVSUPPORT_ROADMAP.md` 阶段 1 关于环境过滤下推 `LOWER(environment)=LOWER(:env)` 改为真实实现（派生查询 `trim()` 后精确匹配、区分大小写，非大小写不敏感）；本文件下方 2026-10-04 阶段 1 完成条目为历史快照，以本条为准
- 验证：后端相关定点单测 exit 0；`pnpm run build` exit 0；`chatScope`/`kbFilter` 前端单测 exit 0。未重跑：全量后端测试、浏览器 E2E、真实 LLM/Embedding。未推送远程；未触碰 `.env`/`.env.eval`、`gradle-wrapper.properties`、Docker 卷

**2026-10-04 -- DevSupport 阶段 1 完成：主壳交付与上下文贯通**
- 四导航重建：文档中心 (`/docs`)、排查会话 (`/chat`)、案例库 (`/cases`)、评测结果 (`/eval-results`)，Layout 从旧五入口改为四入口
- 后端新增 `GET /api/knowledgebase/resolve-context` 端点，service/environment 解析为匹配的 KB IDs 传入向量检索
- RAG Chat 会话创建支持 service/environment 参数，自动 union 显式 kbIds，旧请求兼容
- ContextSelector 组件：service + environment 双选择器，实时显示检索范围；project/version 禁用标注"阶段 2"
- 评测结果页：加载 heading-aware-v0/v0.1 和 v0.1 baseline 三套数据集离线评测指标
- 来源引用增强：展示 service（蓝色）和 environment（绿色）标签快照
- 案例库占位页：明确标注"待建设"
- 代码审查修复：环境过滤下推到 SQL `LOWER(environment) = LOWER(:env)`；会话上下文清除联动
- 设计冲突处理：service/environment 从纯组织标签升级为影响检索范围的上下文
- 后端测试：577 tests，新增 12+ 上下文解析测试全通过
- 前端：`pnpm run build` 通过，53 个单元测试全通过
- 浏览器 E2E：8 项全部通过（真实前端 + 真实后端 + 真实 PostgreSQL/Redis）
- 未推送远程；未调用真实 LLM/Embedding

**2026-10-04 -- DevSupport 路线图阶段 0 完成：遗留 Resume 文件分类保留 + 路线图状态更新**
- 4 个未跟踪的 Resume demo 遗留文件已作为独立提交保留（`ea8fc22`，标注"遗留模块维护"）：`DevSupport_项目开发总方案_v1.0.md`、`DemoResumeGradingService.java`、`application-demo.yml`、`app/src/test/java/interview/guide/modules/resume/`（6 个测试文件）
- `DEVSUPPORT_ROADMAP.md` 阶段 0 状态从"实现中"更新为"已完成"，阶段状态表与工作区事实核对一致
- `PROJECT_PROGRESS.md` 同步记录阶段 0 完成证据
- 未修改任何代码文件或配置

**2026-10-03 -- P1-C L1 heading-aware 候选真实向量对照 + 证据固化**
- 候选数据集 `eval/datasets/devsupport-heading-aware-v0/`：49 chunks（ig-readme-root 走与 baseline 相同的生产解析+清洗 `DocumentParseService`+`TextCleaningService`（cleanedSha256=`f47b6a97…`）后按 heading-aware-v1 重切为 33，其余 16 chunk 逐字节继承）；16 可答/4 NO_ANSWER/38 要点，金标闭环 0 缺失，4 工件冻结哈希逐项匹配
- 真实对照（`evalRunId=26e7f099`，仅 Embedding `text-embedding-v3`，无 LLM，独立评测库，预算硬上限保持 50）：USABLE，attempts 25/50（ingestion 5 批 + query 20，0 失败），入库校验 PASS（49/49, 1024 维, 0 重复），CLEANED（写入 49 → 清理 0）
- candidate 宏平均：MRR@5=0.8542 / APC@5=0.9125 / FC@5=0.875(14/16)；MRR@10=0.8631 / Hit@10=FC@10=APC@10=1.0；k=1 FC=0.5625(9/16)
- 对照冻结 baseline（MRR@5=0.7781/APC@5=0.8813/FC@5=0.8125(13/16)；MRR@10=0.7885；attempts 23）：本轮冻结评测中候选汇总指标优于基线（FC@5 净 +1 题，MRR@5 +7.6pp，MRR@10 +7.5pp）；成本代价 chunks 28→49、批次 3→5、attempts 23→25
- 口径限定：单轮 16 题对照，不宣称生产普遍提升或统计显著；baseline 逐题 JSON 已不可得，故仅汇总层对比、非逐题配对；Q14 FC@5 理论上限更正为 1.0（未重切文档的支撑 chunk 仍可能因候选池重排进入 Top-K）
- 新增可跟踪工件：`p1c-l1-report.json`（脱敏冻结副本）、`p1c-l1-per-query.json`/`.csv`（派生自成功报告，NO_ANSWER 指标列留空）、`P1C-L1-HEADING-AWARE-REPORT.md`
- 运行器支持：`P1cDatasetValidator`（数据集预检）、`P1cRealRetrievalEvalTest` Phase 0 路由+参数化（默认 baseline 28，可覆盖 candidate 49）、`P1cCleanedTextDump` 离线复现生产清洗、`build.gradle` evalP1cReal 支持 datasetDir/expected 覆盖（预算不擅抬）
- 离线测试 `P1cOfflineUnitTest`：92 tests / 0 failures / 0 errors / 0 skipped；全量 `:app:test`：522 tests / 10 failures（均为预先存在的 VoiceInterviewIntegrationTest Redis 连接问题，与本次变更无关）/ 3 skipped
- baseline `eval/datasets/devsupport-v0.1` 零改动；`.env.eval`/`.env`/原始日志/认证失败证据均不入库；未改任何生产业务代码

**2026-10-03 -- I-6 P1-C L1 真实向量评测 + 基础设施修复**
- `.gitattributes`：添加 `*.sh text eol=lf`，修复 Windows 上 shell 脚本 CRLF 行尾导致 Linux 容器 shebang 解析失败
- `app/build.gradle`：`evalP1cReal` 任务补全 `testClassesDirs`/`classpath`（修复 NO-SOURCE），新增 `.env`/`.env.eval` 环境变量加载逻辑（文件不存在时优雅跳过）
- P1-C L1 真实评测：20 查询全部完成，0 失败，roundAvailability: USABLE
- 预算使用：23/50（WITHIN_LIMIT），HTTP 观测 24 次
- 工件冻结校验：PASS；入库验证：PASS（28 chunks, 1024 维）；清理：CLEANED（28 行删除）
- 检索质量（宏平均 k=5）：Hit@5=93.75%, MRR@5=77.81%, APC@5=88.13%, FullCoverage@5=81.25%
- 检索质量（宏平均 k=10）：Hit@10=100%, MRR@10=78.85%, APC@10=100%, FullCoverage@10=100%
- 未改任何生产代码；未验证真实 LLM/SSE/S3/Redis Stream/生产环境 RAG 问答

**2026-10-03 -- I-4 RAG Chat SSE 集成测试（Testcontainers PostgreSQL/pgvector + Redis，Mock LLM）**
- 新增 `RagChatSseIntegrationTest.java`：3 个集成测试场景
  - SSE 事件顺序 data→sources→done 且 PostgreSQL 落库正确
  - sources 事件包含正确的 kb_id、service、environment 标签
  - PostgreSQL 读取：getSessionDetail 返回持久化的消息和来源
- Testcontainers 启动 pgvector/pgvector:pg16 + redis:7-alpine 隔离容器
- Mock LlmProviderRegistry + KnowledgeBaseVectorService，零真实 LLM/Embedding/Redis Stream/S3 调用
- 新增 `testcontainers-postgresql` 依赖（`app/build.gradle`）
- 定点：3 tests / 0 failures / 0 errors / 0 skipped
- 全量：511 tests / 10 failures / 3 skipped（10 个失败均为预先存在的 VoiceInterviewIntegrationTest Redis 连接问题，与本次变更无关）
- 未改任何生产代码

**2026-10-03 -- I-3 切片 3：VoiceInterviewIntegrationTest 恢复（YAML 缩进修复 + WebSocket 测试环境修复）**
- 修复 `application-test.yml` dashscope provider model 属性缩进（6 → 8 空格）
- `@SpringBootTest` → `@SpringBootTest(webEnvironment = RANDOM_PORT)` 支持 WebSocket
- 测试构建器 `.roleType(...)` → `.skillId(...)`（服务层用 skillId 填充 roleType）
- 定点：10 tests / 0 failures / 0 errors / 0 skipped
- 全量：508 tests / 0 failures / 0 errors / 3 skipped（较此前 13 减少 10）
- 未改任何生产代码

**2026-10-03 -- I-3 切片 3：VoiceInterviewIntegrationTest 恢复（YAML 缩进修复 + WebSocket 测试环境修复 + Redis 验证）**
- 修复 `application-test.yml` dashscope provider model 属性缩进（6 → 8 空格）
- `@SpringBootTest` → `@SpringBootTest(webEnvironment = RANDOM_PORT)` 解决 WebSocket ServerContainer 缺失
- 测试构建器 `.roleType(...)` → `.skillId(...)`
- 定点：10 tests / 0 failures / 0 errors / 0 skipped（Redis 7 容器已启动）
- 全量：508 tests / 0 failures / 0 errors / 3 skipped（较此前 13 减少 10）

**2026-10-03 -- I-3 切片 2：Provider 禁用策略实施（PROVIDER_NOT_FOUND vs PROVIDER_DISABLED）**
- 新增 `PROVIDER_DISABLED(11012, "LLM Provider 已禁用")` ErrorCode
- `LlmProviderRegistry.loadProviderOrThrow` 拆为两步判断：先查 not-found，再判 disabled
- `loadProviderFromPropertiesOrThrow` 改抛 `BusinessException(PROVIDER_NOT_FOUND)`
- 替换 `IllegalArgumentException` 为 `BusinessException`，错误消息不泄露 provider ID
- 恢复 `testGetChatClient_disabledProvider`：断言 BusinessException + PROVIDER_DISABLED + 无敏感信息泄漏
- 更新 `testGetChatClient_UnknownProvider`：期望 BusinessException(PROVIDER_NOT_FOUND)
- 定点：11 tests / 0 failures / 0 errors / 0 skipped
- 全量：508 tests / 0 failures / 0 errors / 13 skipped（较此前 14 减少 1，恢复 disabled provider 测试）
- 未改任何生产行为去迎合旧断言

**2026-10-03 -- I-3 切片 1：VoiceInterviewServiceTest mock 漂移修复（保留后端回归测试恢复）**
- 补齐 3 个缺失 @Mock：VoiceInterviewEvaluationRepository、VoiceEvaluateStreamProducer、LlmProviderRegistry
- 移除类级 @Disabled
- 5 个失败测试的 mock/stub 按生产代码实际签名更新：bucket.set(entity, Duration)、findBySessionIdAndMessageTypeNotOrderBySequenceNumAsc、countBySessionIdAndMessageTypeNot
- 定点：32 tests / 0 failures / 0 errors / 0 skipped
- 全量：508 tests / 0 failures / 0 errors / 14 skipped
- 未改测试逻辑意图、不删测试、不放宽断言、不改任何生产代码
- 不宣称语音面试功能成为 Phase 1 用户可见能力

**2026-10-03 -- I-2 RateLimitIntegrationTest Docker 验证（Testcontainers + redis:7-alpine）**
- 定点：4/4 通过（testRateLimit, testMultiRule, testExpiredPermitsRemainWhenLaterRuleRejects, testIndependentCountPerDimension）
- 全量：508 tests, 0 failures, 0 errors, 46 skipped（较此前 50 减少 4，RateLimitIntegrationTest 从跳过转为通过）
- 环境：Docker Desktop 29.7.2，Testcontainers 自动拉起 redis:7-alpine 容器，退出码 0
- 注意：Testcontainers 隔离容器验证，不是生产 Redis 实例验证
- 未修改任何业务代码、测试代码或配置文件

**2026-10-02 -- I-5 POSTGRES_PASSWORD 必填校验（方案 A）**
- 新增 `PostgresPasswordValidator`：`EnvironmentPostProcessor`，在 `prepareEnvironment` 阶段校验 `POSTGRES_PASSWORD`，仅对 `jdbc:postgresql:` URL 生效，H2 等自动跳过
- 新增 `META-INF/spring.factories`：注册 EPP
- 新增 `PostgresPasswordValidatorTest`：9 个测试（U1-U8 + I1 集成时序）
- 全量测试 508 通过，0 失败
- H2 回归：`KnowledgeBaseRepositoryIntegrationTest` 5 个测试通过
- 三角度审查（生命周期/测试隔离/变更范围）：全部 PASS
- 未修改 `application.yml`、`build.gradle`、`App.java` 等任何已有文件
- 未验证：真实 PostgreSQL 连接认证、Flyway 迁移、LLM/Embedding、SSE、生产部署

**2026-10-02 -- 文档：PROJECT_PROGRESS.md 顶部补跨机器 remote 别名映射（仅文档，不改代码/配置）**
- 改了什么：在 `PROJECT_PROGRESS.md` 顶部环境说明新增一行，明确两台机器的 remote 别名差异：本机 `devsupport`=HyCheng-reborn/devsupport-platform（推送目标）、`origin`=Snailclimb/interview-guide（上游、勿推）；另一台验证机的 `origin`=HyCheng-reborn/devsupport-platform，故批次 C/D 文档里「push 到 origin/master」等同于本机的「push 到 devsupport/master」；判断目标仓库以 remote URL 为准。
- 为什么：批次 C/D 的记录在另一台机器上把推送目标写作 `origin`，与本机别名相反，跨机器阅读易误读为推到上游。
- 验证：本轮纯文档；remote 映射依据本机 `git remote -v`（devsupport→HyCheng-reborn/devsupport-platform，origin→Snailclimb/interview-guide）与已同步的 `devsupport/master`（现 HEAD `8cd17c7`）核实；`git diff --check` 结果见本条收尾记录。
- 尚未验证：无（本条仅文档）。

**2026-10-02 -- 批次 D：文档更新（README.md + AGENTS.md 面向 DevSupport 重定位，仅文档，不改代码/配置）**
- 范围：严格按 `devsupport-phase1-design.md` §7 批次 D 白名单——`README.md` + `AGENTS.md` 面向 DevSupport 展示层重定位；`PROJECT_PROGRESS.md` / `CHANGES.md` 已随每批次同步，本条即批次 D 完成记录。**未修改** `.env.example` / `SETUP_API_KEYS.md` / `docs/voice-*` / `frontend/README.md`（stock Vite template）/ 业务代码 / 构建配置 / wrapper / docker-compose / prompts / 前端 / `.env`（本机仍不存在）。
- `README.md` 关键变更：
  - 顶部标题从“智能 AI 面试官平台” → “DevSupport · 研发团队知识与故障排查平台”；项目介绍重写为 DevSupport 定位 + fork 关系（源自 `Snailclimb/interview-guide`，Phase 1 复用基础设施、面试后端保留但前端不暴露）。
  - 新增章节：Phase 1 用户流程 / 系统架构 / 功能特性（Phase 1 已交付）/ **上游面试能力保留说明表**（面试/简历/语音/面试安排/题库面试后端保留但 Phase 1 前端已隐藏入口）/ 使用场景（研发工程师 / SRE / 技术负责人）/ 常见问题 / **未验证事项清单** / **来源与保留说明**。
  - 功能描述从“上游 6 大面试模块”重定位为 “Phase 1 已交付” 3 大块（知识库 + service/environment 标签 + RAG 流式问答与来源快照 + 多 Provider），均注明对应的实现与测试位置。
  - 诚实清单：10 项未验证事项（真实 PostgreSQL + Flyway 迁移执行 / Redis Stream / S3 上传 / DashScope LLM + Embedding 真实调用 / SSE 端到端 / Docker-based RateLimit 4 用例 / 46 @Disabled 目标行为 / `:?` 必填语法在真实容器启动时的确切呈现 / Spring 缺变量启动堆栈 / P1-C L1）**不宣称已验证**。
  - 链接处理：移除所有 `oss.javaguide.cn/...` 面试项目截图与架构图外链（本轮未核验可访问性，不宣称可用）；移除上游付费教程 `javaguide.cn/zhuanlan/interview-guide.html` 引用；保留 `HyCheng-reborn/devsupport-platform`（本轮已推送）；保留上游 `Snailclimb/interview-guide` 作为**来源说明**（本轮未主动核验 HTTP 状态）；沿用原 badges 官方域名，未新增。
  - 命名保留（§6 决策表）：Java 包 `interview.guide` / 数据库 `interview_guide` / S3 bucket / 容器前缀 `interview-*` / JPA 表名 / 本地配置目录 `~/.interview-guide/` / Gradle `rootProject.name` 与 `group` 全部保留，“来源与保留说明”章节完整列出。
- `AGENTS.md` 关键变更：
  - 标题：`AI Interview Platform Agent Rules` → `DevSupport Platform Agent Rules`；首段补充 DevSupport 定位 + fork 上游 + 保留名称列表 + 面试/简历/语音 Phase 1 后端保留但前端不暴露的诚实表述。
  - 其余全部技术规则保留（Tech Stack / Commands / Project Structure 含 `interview/guide/*` 包路径 / Architecture / Backend Rules / AI And Async / Config And Data / Frontend / Testing / Never Do / More Rules），符合“保留 interview.guide Java 包名与相关技术规则”的要求。
- 验收：本轮纯文档，未运行任何测试/构建（README/AGENTS 不进入编译产物）；`git diff --check` 无空白错误；工作区保护项完好（`.env` 仍不存在、`gradle/wrapper/gradle-wrapper.properties` 与 HEAD 无差异、untracked 无混入）；未启动容器 / 未调用真实 LLM/Embedding/生产库/SSE；普通 `git push`，不 force push。
- Phase 1 交付状态：**批次 A + B + C + D 均完成代码/文档变更**；Phase 1 用户可见范围（知识库 + service/environment 标签 + RAG 来源快照 + 多 Provider + DevSupport 展示层文档）按 design §7 交付。真实外部依赖与端到端验证 / 46 @Disabled 测试 / P1-C L1 保持独立待办（上一节 I-1..I-6）。

**2026-10-02 -- 批次 C 后端测试独立干净检出补验（不改代码，仅验证记录）**
- 对象 HEAD：`a9f6d30b043de4e754049f8e43ab0933ccea0e36`（`ls-remote` 已确认与 `origin/master` 一致；a9f6d30 直接改动含 `RagChatControllerTest` 与 `RagChatSessionServiceTest`；`SourceReferenceSnapshotTest` 由前置 `61bc503` 引入，a9f6d30 未再动，仍在三个目标类范围内）。
- 作用：本条**补齐**上一条（批次 C 收尾修正 3/3）里“后端 `:app:test` 执行被环境阻塞、实际运行通过/失败未取到”的验收缺口；不改代码/构建配置/wrapper，不重写其他条目。
- 方式（诚实区分）：对 a9f6d30 新建 `git worktree` 分离干净检出（HEAD=a9f6d30、工作树 clean，位于 git 忽略的 `build/verify-batch-c-a9f6d30`），先清空 `app/build/test-results/test` 再按用户指定命令原样执行。与故障会话在**同一物理机**（hostname `MSI`）、**同一 `~/.gradle`**、**同一工具链**（Gradle 9.6.1 + Temurin OpenJDK `25.0.4.1+1-LTS`，`JAVA_HOME=...jdk-25.0.4.101-hotspot`）上完成，不宣称严格跨机对照；未替换 Gradle 发行版、未复制故障机缓存；主仓工作树保持 clean、`.env` 不存在、`gradle-wrapper.properties` 未动。
- 外部依赖：`:app:test` 默认 excludeTags `real-eval`；H2 内存库、Flyway 关闭；Redis/S3 以 `@MockBean` 打桩；`RateLimitIntegrationTest` 标 `@Testcontainers(disabledWithoutDocker = true)`，本机 Docker Desktop 未运行时自动跳过而非失败。**本轮未启动任何外部服务、未调用真实 LLM / 付费 Embedding / 真实 L1 / 生产库、未启动任何容器**。
- 定点（用户指定命令，严格原样）：`.\gradlew.bat :app:test --no-daemon --console=plain --tests "interview.guide.modules.knowledgebase.RagChatControllerTest" --tests "interview.guide.modules.knowledgebase.service.RagChatSessionServiceTest" --tests "interview.guide.modules.knowledgebase.service.SourceReferenceSnapshotTest"` → START 16:44:47 / END 16:45:45（+08:00，57s）、**FOCUSED_EXIT=0，BUILD SUCCESSFUL**。新生成 10 个 XML 套件（timestamp `2026-10-02T08:45:42-45Z` = 本地 16:45:42-45）：**29 tests / 0 failures / 0 errors / 0 skipped**——`RagChatControllerTest` 顶层 3 + `$SuccessPath` 7（含新增 `sourceTagsAppearInSourcesEventAndPersistedJson`）、`RagChatSessionServiceTest` 顶层 4 + `$BuildSourceReferencesTests` 5（含新增 `carriesTagsAndNullTags`）、`SourceReferenceSnapshotTest` 6 个 @Nested 合计 10。日志中 `GradleWorkerMain / ClassNotFoundException / FAILED / BUILD FAILED` 0 次。
- 全量：`.\gradlew.bat :app:test --no-daemon --console=plain` → 首次 16:50:43 被 Qoder 沙箱瞬时“拒绝访问”（0.16s 未进入 Gradle、无 XML、非 Gradle 故障），5s 后重试 START 16:51:07 / END 16:53:50（2m42s）、**FULL_EXIT=0，BUILD SUCCESSFUL**。新生成 **107 个 XML 套件，tests=499 / failures=0 / errors=0 / skipped=50**。批次 C 三个目标类在全量运行里同样 0 failures / 0 errors / 0 skipped。较批次 B 时的 455 新增 44 用例（来源快照相关）。
- 50 个 skipped 归因（区分可复跑与需代码/配置修复）：Docker 依赖 4（`RateLimitIntegrationTest`，启动 Docker Desktop 即可复跑）；代码/配置待修复 46——`VoiceInterviewServiceTest` 类级 @Disabled 32（`setUp` 缺 `LlmProviderRegistry` mock → NPE）、`VoiceInterviewIntegrationTest` 类级 @Disabled 10（`application-test.yml` `app.ai.providers` 漂移）、`VoiceInterviewServicePauseTest` 1（constructor 变更）、`VoiceInterviewPromptServiceTest` 1（`RolePrompt`/`getRolePrompt` 已移除）、`DashscopeLlmServiceTest` 1（同上）、`LlmProviderRegistryTest#testGetChatClient_disabledProvider` 1（`ProviderConfig.enabled` + `PROVIDER_DISABLED` 未实现）——本轮**未改代码/配置**，@Disabled 原样保留。
- 环境诚实说明：上一条与批次 A/B 记录里 “forked 测试 worker 启动即 `ClassNotFoundException: GradleWorkerMain`” 的环境级阻塞在本机本轮干净检出上**未复现**（定点 + 全量各一次，日志 0 次）；仅说明该现象与特定工作副本/守护态相关，**不宣称跨环境根因已确定、不宣称原始主工作副本上的确切成因已定性**。
- 尚未验证：未启动 Docker 复跑 `RateLimitIntegrationTest`；未连真实 PostgreSQL/Redis/S3/LLM/Embedding/生产库跑端到端 SSE；批次 C 上传后来源面板实际显示未做集成验证；46 项 @Disabled 的目标行为未验证。
- 交付边界：本轮仅本文件与 `PROJECT_PROGRESS.md` 末尾各新增一节验证记录；不改代码/构建配置/wrapper/docker-compose/prompts/前端/.env；不 force push、不删缓存、不禁用测试、不加 tag。

**2026-10-02 -- 批次 C 收尾修正（3/3）：补充来源快照关键测试（前端已实跑，后端执行被环境阻塞）**
- 改了什么：
  - 后端 `RagChatControllerTest`（SuccessPath）新增 `sourceTagsAppearInSourcesEventAndPersistedJson`：以 `oneSource()`（service=支付网关、environment=生产）+ COMPLETED 桩，用 StepVerifier 断言事件顺序 `data → 持久化成功 → sources → done`，并在收到 sources 的瞬间捕获持久化入参，断言持久化 `sourcesJson` 与 sources 事件 `data` **同时**包含 `"service"`/`"environment"` 字段名与标签值。
  - 后端 `RagChatSessionServiceTest`（BuildSourceReferencesTests）新增 `carriesTagsAndNullTags`：两条来源，一条命中带标签 KB、一条命中 `service/environment` 为 null 的 KB，断言批量单次查询下标签透传、NULL 标签保留 null、来源顺序不变；既有批量/顺序/缺失 KB 回退/空输入测试全部保留。
  - 前端新增可单测展示映射 `frontend/src/utils/sourceDisplay.ts`（`toSourceTagView`）+ `sourceDisplay.test.ts`；并把 `KnowledgeBaseQueryPage.tsx` 来源面板改为**实际调用**该映射渲染标签（非孤立辅助函数），文档名/snippet/score/顺序与 `slice(0,5)` 展示口径不变。
- 为什么：批次 C 的验收核心是来源标签进入 SSE/持久化快照并在前端展示；需要可控测试锁定 service/environment 的端到端字段与向后兼容。
- 验证（真实命令 + 退出码）：
  - 前端 `node --test src/utils/sourceDisplay.test.ts` → 退出码 0，tests 9 / pass 9 / fail 0（覆盖新标签、null、旧 JSON 缺字段 undefined、空串归一、score 百分比与 null、文档名片段透传、数组映射保序保量）。
  - 前端 `pnpm run build`（tsc && vite build）→ 退出码 0，产出 `KnowledgeBaseQueryPage-*.js`（仅 chunk>500kB 警告，非错误）。
  - 后端 `./gradlew :app:compileJava :app:compileTestJava --no-daemon` → 退出码 0（新测试与既有测试均编译通过）。
  - 后端 `./gradlew :app:test --no-daemon --tests ...`（含 `--rerun-tasks`、daemon 模式、单类定点共 4 种调用）→ 退出码 1，**非断言失败**：forked 测试 worker 启动即 `java.lang.ClassNotFoundException: worker...GradleWorkerMain` + `Could not write standard input to Gradle Test Executor`，无任何 test-results XML 产出。经确认为本机已知环境级故障（Gradle 9.6.1 + JDK 25 worker 类加载），同一现象在批次 A 记录中已出现，且对本会话未改动的既有测试同样复现。
- 尚未验证的真实行为（据实标注，不以编译或旧 XML 冒充）：
  - 后端新增/既有单元测试的**实际执行结果**（被 Gradle worker 环境故障阻塞，未取到通过/失败）。
  - 真实上传端到端后来源面板显示、真实 LLM/Embedding、生产库 Flyway DDL、端到端 SSE。

**2026-10-02 -- 批次 C 收尾修正（2/3）：Compose 密码变量改为必填语法并验证**
- 改了什么：
  - `docker-compose.yml`（db 服务与 app 服务两处）与 `docker-compose.dev.yml` 的 `POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}` → `${POSTGRES_PASSWORD:?...（缺失或为空均拒绝启动）}`，采用 Compose 支持的必填插值语法。
  - 原裸 `${VAR}` 在变量缺失/为空时仅警告并替换为空串（容器可能以空密码启动）；改为 `:?` 后缺失与空值都明确报错退出。
- 为什么：避免数据库以空密码被拉起，落实凭据必填校验。
- 验证（`docker compose config`，全程脱敏、未打印任何真实密码）：
  - 变量缺失（把 compose 复制到无 `.env` 的目录）→ 退出码 1，报 `required variable POSTGRES_PASSWORD is missing a value`。
  - 变量存在但为空（临时 `.env` 写 `POSTGRES_PASSWORD=`）→ 退出码 1，同一必填错误。
  - 提供哑值 `dummy_batchc_config_test_not_real` → 退出码 0，正常渲染、无插值错误。
  - `docker-compose.dev.yml` 变量缺失 → 退出码 1。
- Spring 配置单独确认：`application.yml` 使用 `${POSTGRES_PASSWORD}`（无默认值），占位符不可解析时 DataSource bean 创建报错、context 启动失败（fail-closed）；本轮为源码/配置静态分析，**未实跑 bootRun 验证缺变量启动**。
- 旧密码状态：本轮未以历史泄露旧密码做 TCP 认证测试，故只记录为「错误密码被拒绝，旧密码状态未验证」，不声称「旧密码已失效」。
- 尚未验证的真实行为：真实容器启动时 `:?` 报错的确切呈现、Spring 缺变量启动的实际堆栈（未做真实启动验证）。

**2026-10-02 -- 批次 C 收尾修正（1/3）：纠正 force push 后的不实文档记录（仅文档，不改代码）**
- 背景：当前 HEAD `f4ac4b9` 是把已推送的 `d5b84bd` 经 `git commit --amend` 后覆盖而来。本地 reflog 链：`713f36b`(原始提交) → `d5b84bd`(amend) → `f4ac4b9`(amend)，三者父提交同为 `61bc503`。
- force push 影响查清：`d5b84bd` 对象在本地 reflog 中完整可取（`git cat-file -t d5b84bd` = commit）。`git diff d5b84bd f4ac4b9` 仅命中 `CHANGES.md` / `PROJECT_PROGRESS.md`，且为**纯新增、零删除**；批次 C 代码文件 `frontend/src/api/ragChat.ts`、`frontend/src/pages/KnowledgeBaseQueryPage.tsx` 在两者间**完全一致**。故 force push **未覆盖任何代码或测试**，`f4ac4b9` 的树是 `d5b84bd` 的超集，**无需恢复代码、也没有需要恢复的被覆盖内容**。
- 不实描述来源：`d5b84bd → f4ac4b9` 多出的正是下面这些夸大的文档行——把批次 C 写成「实现前端来源筛选逻辑 / 新增过滤测试 / 改 vite.config.ts」，并错误归因到已失效的 `d5b84bd`。实际批次 C 前端**只展示来源标签**，未做任何来源筛选。
- 本条作用：以当前代码为准，撤除「来源筛选已实现」的不实表述；批次 C 目标保持在来源标签展示，不自行扩展为来源筛选功能。
- 验证：本轮纯文档修正；`git diff --check` 结果见本轮收尾提交记录。
- 尚未验证：无（本条仅文档）。

**2026-10-02 -- DevSupport Phase 1 批次 C 前端：来源面板展示 service/environment 标签**
- 改了什么：
  - `frontend/src/api/ragChat.ts`：`SourceReference` 接口新增可选 `service` / `environment` 字段
  - `frontend/src/pages/KnowledgeBaseQueryPage.tsx`：来源面板渲染蓝色（service）和绿色（environment）标签
  - 两个字段均为 null/undefined 时显示「无标签」（向后兼容旧 sourcesJson）
  - SSE 事件处理不变（data/sources/done 三事件契约保持）
  - 来源排序不变
- 为什么：后端批次 C 已将 service/environment 写入 SourceReference 快照，前端需消费并展示
- 验证：
  - `pnpm run build` exit 0
  - 前端 filter 测试 9 pass / 0 fail
- 尚未验证的真实行为：
  - 真实上传端到端后来源面板实际显示效果

**2026-10-02 -- DevSupport Phase 1 批次 C 后端：SourceReference 标签快照**
- 改了什么：
  - `SourceReference.java`：新增 `service` + `environment` 字段（提问时刻快照）
  - `RagChatSessionService.buildSourceReferences()`：批量查询 KB 时一并获取 service/environment，写入来源引用
  - 批量查询仍为单次 `findAllById`，不逐条查 DB
  - 更新已有测试文件中的 SourceReference 构造器调用（4参→6参）
- 为什么：来源引用需携带 KB 的 service/environment 标签快照，前端来源面板展示
- 验证：
  - `./gradlew :app:compileJava` exit 0
  - `./gradlew :app:test --no-daemon` exit 0（497/0/46）
  - 新增 SourceReferenceSnapshotTest 覆盖：正常标签映射、NULL 标签、缺失 KB、批量一次查询、来源顺序、空输入
- 尚未验证的真实行为：
  - 真实上传端到端后来源面板显示
  - sourcesJson 序列化包含 service/environment 字段（前端批次 C 负责消费）
- 不实施：批次 C 前端（另一个 agent 负责）

**2026-10-02 -- fix: correct Spring placeholder syntax for POSTGRES_PASSWORD**
- 改了什么：
  - application.yml 将 `${POSTGRES_PASSWORD:?POSTGRES_PASSWORD environment variable is required}` 改为 `${POSTGRES_PASSWORD}`
  - 旧语法是非标准 bash 风格，Spring 无法解析，即使设置了环境变量也不会生效
  - 新语法：环境变量缺失时在数据库连接层报错（标准 Spring 行为）
- 为什么：旧语法导致凭据永远无法正确注入
- 验证：
  - TCP 认证验证：新密码 exit 0，错误密码 exit 2
  - 注意：Docker Compose 对缺失变量替换为空字符串（不报错）
- 尚未验证的真实行为：
  - Spring Boot 启动时环境变量缺失的具体报错信息

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
