# PROJECT_PROGRESS — 跨对话任务进度唯一入口

> 本项目（远程仓库 `HyCheng-reborn/devsupport-platform`，本地目录名仍为 `interview-guide`）的进度事实源。
> 新会话接手时先读本文件，再读「交接区」列出的文件。
> 最近更新：**2026-09-30 17:12（北京时间）**，作者 Qoder。

## 0. 维护规则

- 每完成一项**有证据**的任务，在同一次改动里更新本文件；状态变化必须附提交号或可复跑的验证命令。
- 状态只有三档，不得混用：`代码已写` / `离线通过` / `真实环境已验证`。未经真实环境执行的，一律不写「已验证」。
- 复核意见在被复核方确认前记为 `待复核`，即使代码已按意见改动，也不得写成 `已解决`。
- 只保留当前有效结论 + 文末简短变更记录。不写完整日志、密钥、密码、Token、未经验证的推测。
- `status.txt`（仓库根，未跟踪）是 2026-09 早前一次终端回滚残留，内容停留在 `b2d1ca0` 且把 `origin` 当作推送目标，**不要作为状态依据**。

## 1. 项目目标与当前阶段

**长期目标**：把原「AI 面试官平台」逐步改造成 **DevSupport —— 研发团队知识与故障排查平台**（文档理解、配置/接口查询、故障排查）。P1-C 检索评测只是为这个目标建立可复现的检索基线，**不是项目终态**。

**原项目基础**（保持不变，仍是主体）：Spring Boot 4.1.0 / Java 25 / Spring AI 2.0.0 / Gradle；PostgreSQL + pgvector（1024 维，COSINE）；Redis + Redisson + Redis Stream；RustFS(S3) + Tika；React 18 前端。

**改造现状（以代码为准）**：
- 唯一可直接复用的资产：`app/src/main/java/interview/guide/modules/knowledgebase/` —— 知识库 CRUD、文档上传、异步向量化、RAG 问答（`RagChatController`）、KB 出题。前端已有 `KnowledgeBaseManagePage / UploadPage / QueryPage`。
- DevSupport 语义尚不存在的能力：工单/事件（ticket/incident）、故障排查流程、runbook、值班/oncall、按服务或环境聚合的知识索引 —— **全部未实现（not found）**。
- 改造阻碍（记录备查，本轮不动）：根 Java 包名 `interview.guide`、JPA 表名 `interview_sessions` / `resumes` / `voice_interview_*` 等、库名 `interview_guide`、容器名 `interview-*`、S3 bucket `interview-guide`、`resources/prompts/` 17 个 `.st` 模板中 12 个是面试/简历/JD 用途（仅 5 个 `knowledgebase-*` 可复用）、`resources/skills/**` 是面试题库、根 `README.md` 与 `AGENTS.md` 仍以面试官平台为标题（README 无任何 DevSupport 内容，DevSupport 目标目前只出现在 `eval/datasets/devsupport-v0.1/README.md`）。

**评测三段关系**：
- **P1-A**（`d2fd5e6`）：纯离线指标核心 —— Hit@K / Recall@K / MRR@K，虚构 fixture，不碰语料与外部服务。
- **P1-B**（`2903ec2`）：冻结开发集 `devsupport-v0.1` —— 7 篇真实仓库文档 → 28 chunks → 20 题（16 ANSWERABLE + 4 NO_ANSWER）→ 38 答案要点。是**开发集草案，不是独立测试集**，不作最终基线。
- **P1-C**（当前阶段）：真实 pgvector + 真实 Embedding 的 **L1 向量检索组件基线**，明确不含查询改写、动态 topK/阈值（这三项生产里都开着，见 §3.4）。

## 2. 已完成且有证据

- **P1-A 指标核心** — 状态 `离线通过`。提交 `d2fd5e6`；文件 `eval/RetrievalMetrics.java` 等 + `RetrievalMetricsTest`、`EvaluationReportJsonTest`。验证：`GRADLE_USER_HOME=/c/temp/gradle-tmp ./gradlew :app:test --tests 'interview.guide.eval.*' --no-daemon` → exit 0（本轮 2026-09-30 复跑，eval 包 114 条全绿，其中非 P1-C 的 P1-A 指标/报告契约 33 条）。
- **P1-B 数据集与校验** — 状态 `离线通过`（按其自身报告记录）。提交 `2903ec2`；工件 `eval/datasets/devsupport-v0.1/{chunks.jsonl,candidate-gold.json,corpus-manifest.json,chunk-manifest.json,evidence-chunk-map.json}` + `validate_p1b.py`。验证：`P1B-REPORT.md` 记录 v3.1 七项检查全 PASS（含重复运行四工件一致、Tika 退出码据实记 1）。**本轮未重跑** `validate_p1b.py`，因为它会实际重跑切分管线并改写冻结工件，与「本轮只写文档」冲突。
- **P1-C 评测装配 + 离线护栏 + 评测容器定义** — 状态 `离线通过`（装配代码可编译、离线护栏用例全绿；容器与付费路径 `代码已写`）。提交 `e32be2e`、`58b9b32`；文件 `P1cRealRetrievalEvalTest.java`、`P1cEvalCallBudget.java`、`P1cEvalHttpCallCounter.java`、`docker-compose-eval.yml`、`docker/postgres/eval-init.sql`、`app/build.gradle`（`excludeTags 'real-eval'` + `evalP1cReal` 任务）。
- **P1-C 数据与失败契约** — 状态 `离线通过`。提交 `caf05cf`；文件 `P1cIngestionVerifier`、`P1cRetrievalHandler`、`P1cMultiKMetrics`、`P1cAnswerPointMetrics`、`P1cEvalRunOrchestrator`、`P1cEvalResultValidator`、`P1cEvalReport`。62 条离线契约用例。
- **P1-C 定点修复（本轮）** — 状态 `代码已写 + 离线通过`，**待复核**，见 §3。提交 `f6aa629`（已推送 `devsupport/master`）；P1-C 离线用例 62 → 81，eval 包 95 → 114，全量 `:app:test` 394 通过 / 0 失败 / 0 错误 / 50 跳过。
- **CI 配置** — 状态 `代码已写`。提交 `b2d1ca0`，`.github/workflows/ci.yml` 执行 `./gradlew :app:test --no-daemon` 与前端 `pnpm run build` + 4 组前端测试。**远程 Actions 对 `f6aa629` 的实际运行结果未核对**（本轮按边界要求未访问外部服务），不记为通过。
- **真实环境已验证** — 目前**零项**。没有任何 P1-C 环节接触过真实数据库、Embedding API 或评测容器。

## 3. 当前阻塞与待复核事项

差异说明：本地/远程 HEAD 已是 `f6aa629`，**不再是 Codex 复核的 `caf05cf`**。下列三项在 `f6aa629` 中已按复核意见改动并有离线用例，但复核方尚未确认，因此**全部记为 `待复核`，不得写成已解决**。

- **B1（P0）回退候选的隔离违例可能被过滤或降级** — 原缺陷：先按 runId/kbId/冻结 ID 过滤、再对幸存者做准入，外来行不进入违例检查，「合法+非法混合」被记成成功；且准入异常落在「回退请求失败」的 `catch` 内，被降级为单题 `RETRIEVAL_FAILED`。改动：`P1cRetrievalHandler.attributeFallback()` 对**原始**候选逐条归属判定，缺失 `eval_run_id` / 外来 runId / 本次 runId 但 `eval_chunk_id` 缺失或不在冻结 28 ID / `kb_id` 越界或非字符串 / null 候选，一律抛实验级异常中止整轮（无静默过滤）；归属与准入移到请求失败捕获之外，不再 `recordFailure()`。用例：`P1cOfflineUnitTest` 的 8 条 `retrieve_fallback*` 用例。**待复核确认。**
- **B2（P1）存在 `RETRIEVAL_FAILED` 时整轮仍可能成功退出** — 改动：新增 `P1cRoundAvailability`（`queriesCompleted < 20` 或 `failedRetrievalQueries > 0` → `NOT_USABLE`），装配在指标后抛实验级异常；失败题原始记录、错误串与「按零计入宏平均」的单题口径保留，`OK_ZERO_RESULT` 仍视为正常数据点。用例 5 条。**待复核确认，尤其需确认这个「单题口径保留 + 整轮判不可用」的双层表达是否符合预期。**
- **B3（P1）四份冻结工件只计算哈希、未与批准值比对** — 改动：新增已提交清单 `eval/datasets/devsupport-v0.1/p1c-frozen-artifacts.json`（记录来源 `2903ec2`，比对字段 `expectedSha256NormalizedLf`）与 `P1cFrozenArtifactVerifier`；Phase 0 在**凭据读取 / 客户端构建 / 数据库连接 / 任何写入之前**逐项比对，清单按超集要求校验（缺条目、重复、非 64-hex、文件缺失均为违例）。口径：因本机 `core.autocrlf=true`，比对采用行尾归一化（CRLF/CR→LF）后 UTF-8 的 SHA-256，原始字节哈希仅作参考。用例 6 条（含「计数不变、正文改写必须暴露」）。**待复核确认，尤其批准哈希本身是否被正确记录这件事仍需独立验算。**
- **B4（口径，顺手修）** 入库核对失败时上下文为空 → 五个阶段边界落 `partialResults/observe` 快照；`reportWriter` 收到「写出前快照」改为显式契约并加测试。**待复核确认。**
- **B5 无 L1 结果** — `eval/datasets/devsupport-v0.1/p1c-l1-report.json` 尚不存在，项目目前**没有任何检索质量数字**。
- **B6 前置门槛未过** — 复核通过前不进入容器验证；`.env.eval` 未创建；`eval_runner` 口令策略（设计文档 §15 #8 仍列为待确认）未决。

## 4. 下一步任务队列

**白天（免费 Qwen Flash，轻量、离线、零成本）**
- **T1 交付 `f6aa629` 给 Codex 复核并等待** — 依赖：无。验收：收到复核结论；若有新 finding，另起小提交修复并保持离线全绿。**这是 T2 之后的唯一下一步，不允许跳级。**
- **T2 维护本文件** — 依赖：任何有证据的改动。验收：状态档位与提交号/命令一一对应，无「已验证」字样出现在未验证项上。（本轮已完成初版）
- **T3 复核反馈驱动的离线小修** — 依赖：T1 结论。验收：`GRADLE_USER_HOME=/c/temp/gradle-tmp ./gradlew :app:test --tests 'interview.guide.eval.*' --no-daemon` exit 0 且用例数不减少。

**22:00–23:00（较大编码 / 验证任务）**
- **T4 独立评测容器的「无 API」验证** — 依赖：**T1 复核通过**。内容：只启动 `docker-compose-eval.yml`，核对 `eval-init.sql` 实际效果（扩展、表、HNSW 索引定义）、`eval_runner` 权限、标记表 UUID、列维度 `vector_dims()`、`vector_store` 初始为空。**不得发起任何 Embedding 调用、不得运行 `evalP1cReal`。** 验收：五项各有实测输出记录进报告，任何不符即停下报告根因，不自行改设计语义。
- **T5 DevSupport 改造范围讨论（仅出方案，不改代码）** — 依赖：T1 通过。内容：明确第一阶段能力（知识库索引 + 故障排查问答）与命名/迁移策略（包名、表名、bucket 是否动）。验收：一页方案 + 影响清单交复核。

**后续门槛（需另行授权，不在当前队列执行）**
- G1 真实 Embedding 冒泡（1–2 题，付费开关显式 `-Peval.p1c.realApi=true`）。
- G2 正式 L1 全量 20 题运行 + 报告复核。
- G3 P2 Hybrid 检索 / DevSupport 功能模块实现。

## 5. 执行边界（这些一律不得写成「通过」）

- Docker 评测容器：从未启动过，`eval-init.sql` 与 `docker-compose-eval.yml` 只经过文本审查。
- 数据库权限与身份：`eval_runner` 的最小权限授予、标记表核验、`current_user` 判定，全部未在真实实例验证。
- pgvector 实际 SQL：`vector_dims()`、余弦算子、HNSW 索引可用性、`PgVectorStore` 的 `filterExpression("kb_id in ['900001']")` 行为与 JSONB 字符串类型匹配，均未验证。
- 真实 HTTP 次数：外层操作预算与 OkHttp 拦截器观测值是否一致、`maxRetries(0)` 是否真的无隐藏重试，未验证。
- 付费评测：从未调用 Embedding API；任何 L1 质量数字都还不存在。
- 生产检索等价性：P1-C 固定 topK=10 / minScore=0 / 关闭查询改写；而生产默认 `rag.rewrite.enabled=true`（`application.yml:169-171`），检索参数按查询长度动态取 topK 20/12/8 与 minScore 0.18/0.28（`application.yml:173-177`，`KnowledgeBaseQueryService.resolveSearchParams()` :302），`KnowledgeBaseVectorService.similaritySearch()` :125 的回退 `similaritySearchFallback()` :161 是**静默本地过滤**（:178 调用 `isDocInKnowledgeBases()` :195）而非中止。因此 L1 是**组件基线，不代表生产检索端到端行为**，`P1cRetrievalHandler` 类注释也明确不声称与生产回退等价。
- 远程 CI 对当前 HEAD 的运行结果未核对。

## 6. 交接区

- **最新已知 HEAD**：本文件与进度变更一起提交，所以「最新 HEAD」请用 `git log -1 --oneline` 现场核对（本文件最后一次被提交的记录见 §7 首行）。**最后一次代码改动提交是 `f6aa629`**（`fix: P1-C 白天离线定点修复…`），已与 `devsupport/master` 同步（`git rev-list --left-right --count devsupport/master...master` = `0 0`）。推送目标是 `devsupport`（`HyCheng-reborn/devsupport-platform`），**从不推 `origin`**（`Snailclimb/interview-guide`，上游只读）。
- **工作区状态**：`gradle/wrapper/gradle-wrapper.properties` 有刻意保留的本机改动（离线分发地址），永不提交、永不还原；未跟踪残留 `devsupport-p1b-v3-review.zip`、`eval/datasets/devsupport-p1b-v3.1-review.zip`、3 个 `eval/datasets/*.tar.gz`、`status.txt`、`interview-guide_本机环境与启动关闭说明.md` —— 均不属于本轮改动，保持原样。`PROJECT_PROGRESS.md` 为本轮新增。
- **下一位助手先读什么（按顺序）**：
  1. `AGENTS.md`（工程约束）+ 本文件 §3、§5；
  2. `eval/datasets/devsupport-v0.1/P1C-L1-DESIGN.md` §7 评测流程（Phase 0 步骤 6、回退候选处置分类表、Phase 4 后整轮判定）与 §16「v1.4 实现期定点修订」；
  3. `app/src/test/java/interview/guide/eval/P1cRetrievalHandler.java`、`P1cRoundAvailability.java`、`P1cFrozenArtifactVerifier.java`；
  4. 装配 `P1cRealRetrievalEvalTest.java`（阶段边界与门控位置）；
  5. 数据集语义 `eval/datasets/devsupport-v0.1/README.md` 与 `P1B-REPORT.md`。
- **下一条具体任务**：把 `f6aa629` 的 diff 交给 Codex 复核（重点看 B1 的三分类处置、B2 的双层口径、B3 的批准哈希是否需独立验算），拿到结论前**不要启动容器、不要连数据库、不要调 API**。
- **环境提醒**：Gradle 命令必须带 `GRADLE_USER_HOME=/c/temp/gradle-tmp`（默认用户目录含非 ASCII 字符会让 test worker 启动失败）；Jackson 是 3.x（`tools.jackson.databind`）。

## 7. 变更记录（简短，倒序）

- 2026-09-30 17:12 — 新增本文件（同轮一次自引用修订：交接区改为现场核对 HEAD，避免被自身提交作废）；同步 `f6aa629` 后的真实状态（B1–B4 记为待复核，非已解决），并记录生产检索与 L1 的参数差异、CI 结果未核对、`status.txt` 陈旧。
- 2026-09-30 16:56 — `f6aa629`：三项复核 finding 的离线实现 + 81 条 P1-C 离线用例；设计文档同步至实现口径；推送 `devsupport`。
- 2026-09-30 — `caf05cf`：P1-C 数据与失败契约（62 条离线用例）；Codex 复核指出三项问题（见 §3）。
