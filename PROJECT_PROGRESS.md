# PROJECT_PROGRESS — 跨对话任务进度唯一入口

> 本项目（远程仓库 `HyCheng-reborn/devsupport-platform`，本地目录名仍为 `interview-guide`）的进度事实源。
> 新会话接手时先读本文件，再读「交接区」列出的文件。
> 最近更新：**2026-09-30 18:48（北京时间）**，作者 Qoder。

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

- **P1-A 指标核心** — 状态 `离线通过`。提交 `d2fd5e6`；文件 `app/src/test/java/interview/guide/eval/RetrievalMetrics.java` 及同目录 `EvalQuery.java` / `RetrievalHit.java` / `QueryJudgement.java` / `RetrievalMetricsTest` / `EvaluationReportJsonTest`。验证：`GRADLE_USER_HOME=/c/temp/gradle-tmp ./gradlew :app:test --tests 'interview.guide.eval.*' --no-daemon --rerun` → exit 0（2026-09-30 17:41 强制重跑：eval 包 114 通过 / 0 失败 / 0 错误 / 0 跳过，其中 P1-C 81 条、P1-A 指标与报告契约 33 条）。
- **P1-B 数据集与校验** — 状态 `离线通过`（按其自身报告记录）。提交 `2903ec2`；工件 `eval/datasets/devsupport-v0.1/{chunks.jsonl,candidate-gold.json,corpus-manifest.json,chunk-manifest.json,evidence-chunk-map.json}` + `validate_p1b.py`。验证：`P1B-REPORT.md` 记录 v3.1 七项检查全 PASS（含重复运行四工件一致、Tika 退出码据实记 1）。**本轮未重跑** `validate_p1b.py`，因为它会实际重跑切分管线并改写冻结工件，与「本轮只写文档」冲突。
- **P1-C 评测装配 + 离线护栏 + 评测容器定义** — 状态 `离线通过`（装配代码可编译、离线护栏用例全绿；容器与付费路径 `代码已写`）。提交 `e32be2e`、`58b9b32`；文件 `P1cRealRetrievalEvalTest.java`、`P1cEvalCallBudget.java`、`P1cEvalHttpCallCounter.java`、`docker-compose-eval.yml`、`docker/postgres/eval-init.sql`、`app/build.gradle`（`excludeTags 'real-eval'` + `evalP1cReal` 任务）。
- **P1-C 数据与失败契约** — 状态 `离线通过`。提交 `caf05cf`；文件 `P1cIngestionVerifier`、`P1cRetrievalHandler`、`P1cMultiKMetrics`、`P1cAnswerPointMetrics`、`P1cEvalRunOrchestrator`、`P1cEvalResultValidator`、`P1cEvalReport`。62 条离线契约用例。
- **P1-C 定点修复（`f6aa629`）** — 状态：`代码已写 + 离线通过 + 源码定点复核通过 · 真实环境待验证`。提交 `f6aa629`（已推送 `devsupport/master`）；P1-C 离线用例 62 → 81，eval 包 95 → 114，全量 `:app:test` 394 通过 / 0 失败 / 0 错误 / 50 跳过。三项 finding 见 §3 B1–B3。
- **容器无 API 验证清单（`6c33a05` 新增、`6291a2a`/`ff486e7` 交付前自审修订的文档轮）** — 状态：`代码已写（清单本身待执行）`。文件 `eval/datasets/devsupport-v0.1/P1C-CONTAINER-CHECKLIST.md`：C1–C11 覆盖端口占用、容器/卷归属、compose 渲染、init 脚本是否真跑过、陈旧卷与角色口令不轮换、库名与身份、权限的声明式 vs 行为式两口径（含三条负向）、事务内 INSERT/SELECT/UPDATE/DELETE 探针且回滚后全表仍为 0、1024 维列与 HNSW `vector_cosine_ops`、收尾状态。**全部为期望值，零项实测。**
- **清单定点修订（本轮，2026-09-30 白天第二个文档轮）** — 状态：`离线通过（静态 + 命令可执行性）· 真实环境待验证`。按 Codex 五条意见逐条改：C2 卷归属改用 `.Labels`（`com.docker.compose.project` / `.volume` + 容器 `Mounts` + 容器 project 标签三方闭环，**取消**对卷上 `config_files` 标签的要求——那个标签属于容器/网络层，照旧判据必然空读并误报）；C8b 三条负向探针拆成**三个独立会话**、各自 `BEGIN … ROLLBACK` + 事务外残留复查，并给出 PASS / FAIL / BLOCKED 分类表（上一版三条共用事务，第一条被拒后其余语句因 `25P02` 根本不执行 → 是假 PASS 生成器）；C3 只允许 `config --quiet` 与「python 只取 ports」两条命令，禁止打印渲染后的完整配置（`environment` 段含两个口令明文）；§5 把「init 是否执行过」拆成新卷/旧卷两个命题与分支 A/B 证据要求，记录卷 `CreatedAt`、容器 `StartedAt`、up 时刻与 `--since 15m` 日志；全部 SQL 改容器内 `docker exec -T … psql`（宿主机无 `psql`/`jq`，本轮实测），凭据核验走容器内 TCP。新增 §14.1 记录本轮验证命令与退出码、§12 记录表扩到 C1–C11 逐条。
- **设计文档定点修订（本轮，B7 的落地）** — 状态：`离线通过（静态一致性）`。`P1C-L1-DESIGN.md` 移除残留的固定评测口令字面量（§3 init SQL / §4 装配 / §5 Gradle / §16 变更记录共 4 处），§15 #8 标为已解决并把真正的遗留风险改记为「旧卷复用不轮换口令 → C6/C7」；`metadata JSONB` → 交付实际的 `metadata json`（含 §7 回退归属表描述）；§2 compose 示例换成交付文件内容并列出差异（旧示例带六位弱默认口令与错误的超管变量名）；§12 文件清单纠正用户/授权归属；§13 表内命令改成目标机可直接复制执行形态；§14 方案 B 改为按 `eval_run_id` 谓词删除（不再无条件全表 DELETE）；新增 §16「v1.4 文档定点修订」小节。
- **评测配置静态核对** — 状态：`离线通过（静态）`。本轮清单内断言块扩为 **25 项，重跑 25 PASS / 0 FAIL，exit 0**（`6c33a05` 那轮的断言集是 26 项、组成不同；复跑命令见清单 §0，本轮数字以清单 §14.1 为准）。生产 `docker-compose.yml` 与 `docker-compose.dev.yml` 中不含任何 `eval` 引用；`.gitignore` 忽略 `.env.eval`，`git ls-files` 只有 `.env.eval.example`；**工作区内口令字面量扫描命中 0**（注意：`git log`/历史提交里 `P1C-L1-DESIGN.md` 的旧版本仍含该字面量，未做历史重写——该值从未被用于任何真实实例，因为 `.env.eval` 至今不存在）。命令形态核验：18 个 bash 块 `bash -n` 全部 0 失败；3 条 `python -c` 过滤器用合成假 JSON 实跑，哨兵字段未泄漏；`read -rs` 写法实跑不回显。
- **CI 配置** — 状态 `代码已写`。提交 `b2d1ca0`，`.github/workflows/ci.yml` 执行 `./gradlew :app:test --no-daemon` 与前端 `pnpm run build` + 4 组前端测试。**远程 Actions 对 `f6aa629` 的实际运行结果未核对**（本轮按边界要求未访问外部服务），不记为通过。
- **真实环境已验证** — 目前**零项**。没有任何 P1-C 环节接触过真实数据库、Embedding API 或评测容器。

## 3. 当前阻塞与待复核事项

差异说明：Codex 复核的对象是 `caf05cf`，本地/远程 HEAD 已前进到 `f6aa629`。**2026-09-30 白天收到 Codex 对 `f6aa629` 的定点复核结论：B1–B3 的离线修复通过；四份冻结工件在提交 `2903ec2` 的归一化 SHA-256 已由复核方独立验算且全部匹配。真实数据库行为仍未验证。** 因此 B1–B3 从 `待复核` 改为 `源码定点复核通过 · 真实环境待验证`，但仍不等于 `真实环境已验证`。

同日收到 Codex 对**容器验证清单（`ff486e7` 状态）**的六条定点修订意见（卷标签判据 / 负向探针可回滚且可辨因 / 不打印渲染配置 / 区分新卷初始化与旧卷复用 / 设计文档口令与 json 类型 / 目标环境命令可执行性），本轮全部落实到文档，见 §2 三条新增、清单 §14 对照表、设计文档 §16。**该轮结论待复核方确认，本轮不自称「已解决」。**

- **B1（P0）回退候选的隔离违例可能被过滤或降级** — 状态：`源码定点复核通过（依据 f6aa629）· 真实环境待验证`。原缺陷：先按 runId/kbId/冻结 ID 过滤、再对幸存者做准入，外来行不进入违例检查，「合法+非法混合」被记成成功；且准入异常落在「回退请求失败」的 `catch` 内，被降级为单题 `RETRIEVAL_FAILED`。改动：`P1cRetrievalHandler.attributeFallback()` 对**原始**候选逐条归属判定，缺失 `eval_run_id` / 外来 runId / 本次 runId 但 `eval_chunk_id` 缺失或不在冻结 28 ID / `kb_id` 越界或非字符串 / null 候选，一律抛实验级异常中止整轮（无静默过滤）；归属与准入移到请求失败捕获之外，不再 `recordFailure()`。剩余风险：真实 `PgVectorStore` 返回的 metadata 是否真带 `eval_run_id`/`eval_chunk_id`、`kb_id` 在 `metadata`（`json` 列，不是 `jsonb`）里的实际 JSON 类型（字符串 vs 数字），只有跑过一次才知道（见清单 C7–C10）。
- **B2（P1）存在 `RETRIEVAL_FAILED` 时整轮仍可能成功退出** — 状态：`源码定点复核通过（依据 f6aa629）· 真实环境待验证`。改动：新增 `P1cRoundAvailability`（`queriesCompleted < 20` 或 `failedRetrievalQueries > 0` → `NOT_USABLE`），装配在指标后抛实验级异常；失败题原始记录、错误串与「按零计入宏平均」的单题口径保留，`OK_ZERO_RESULT` 仍视为正常数据点。
- **B3（P1）四份冻结工件只计算哈希、未与批准值比对** — 状态：`源码定点复核通过（依据 f6aa629）+ 批准值已独立验算 · 真实环境待验证`。改动：新增已提交清单 `eval/datasets/devsupport-v0.1/p1c-frozen-artifacts.json`（记录来源 `2903ec2`，比对字段 `expectedSha256NormalizedLf`）与 `P1cFrozenArtifactVerifier`；Phase 0 在**凭据读取 / 客户端构建 / 数据库连接 / 任何写入之前**逐项比对，清单按超集要求校验。口径：因本机 `core.autocrlf=true`，比对采用行尾归一化（CRLF/CR→LF）后 UTF-8 的 SHA-256，原始字节哈希仅作参考。**复核方已对四个归一化哈希独立验算并全部匹配**，所以批准值本身不再是疑问；剩下的疑问只在于真实运行路径上的行为。
- **B4（口径，顺手修）** 入库核对失败时上下文为空 → 改为**在 5 个阶段边界**调用 `snapshot(ctx)`：PHASE0 完成后、写入起点（`ctx.startWrites()` 之后立即）、PHASE1 完成后、PHASE2 完成后、PHASE3 完成后，每次覆盖式写入 `ctx.partialResults(...)` + `ctx.observe(...)`。**阶段内部没有逐步快照**；阶段内部抛异常时报告仍带部分结果，是因为 `writeReport(RunState st, RunResult result)` 直接从 `RunState` 字段读已有数据（`artifactFreeze` / `ingestion` / `answerableResults` / `noAnswerDiagnostics` / 各计数器），而不是因为阶段内每步都拍了上下文。`reportWriter` 收到「写出前快照」也已改为显式契约并加测试。状态：`源码定点复核通过（依据 f6aa629）· 真实环境待验证`。
- **B5 无 L1 结果** — `eval/datasets/devsupport-v0.1/p1c-l1-report.json` 尚不存在，项目目前**没有任何检索质量数字**。
- **B6 待执行容器验证** — 源码级复核已过，容器无 API 验证解锁，计划今晚 22:00–23:00 按 `eval/datasets/devsupport-v0.1/P1C-CONTAINER-CHECKLIST.md` 执行。**`.env.eval` 目前不存在**（只有 `.env.eval.example`），需人工创建并填两个强随机口令——这一步只能由用户做，助手不生成、不保存口令。
- **B7 设计文档残留口令字面量** — 状态：`本轮已修（离线）· 待 Codex 复核`。原问题：`P1C-L1-DESIGN.md` 的 §3 init SQL / §4 装配代码 / §5 Gradle 片段 / §16 变更记录共 **4** 处写着一个固定的弱口令值（本文件此前在 B7/T3/T6 与变更记录里也把它原文引用过，现已全部改为描述性指代，不再复制该值），§15 #8 还把它列为「当前硬编码」。**2026-09-30 白天收到 Codex 的定点修订指令（六条），其中第 5 条明确要求修正这三处字面量与 §15 #8**，因此本轮按指令执行：4 处全部移除（§3 改 `<EVAL_RUNNER_PASSWORD>` 占位并说明角色/授权实际在 `eval-user.sh`；§4 改为交付版的 `requireCredential("eval.datasource.password","EVAL_RUNNER_PASSWORD")`；§5 改为「仅显式 `-P` 转发」；变更记录那条标注作废），§15 #8 标为已解决并把遗留风险改记为「旧卷复用不轮换口令 → C6/C7」。验证：`grep` 该字面量在**工作区**命中 0（历史提交未重写，见 §2）；清单 §0 断言里该搜索词用相邻字符串拼接写出，避免自检脚本自己成为命中项。

## 4. 下一步任务队列

**白天（免费 Qwen Flash，轻量、离线、零成本）**
- **T1 交付 `f6aa629` 给 Codex 复核** — 状态 `已完成`：收到定点复核结论，B1–B3 通过，四份冻结工件在 `2903ec2` 的归一化哈希已独立验算且全部匹配；结论只覆盖源码，真实 DB 行为仍未验证。
- **T2 维护本文件 + 产出今晚清单** — 状态 `本轮完成`。依赖：任何有证据的改动。验收：状态档位与提交号/命令一一对应，未验证项上不出现「已验证」。
- **T3 清单静态自检** — 状态 `已完成（本轮追加两项）`。四项防误用核查：① 是否引用生产 Compose/端口（清单只出现 `docker-compose-eval.yml`、5433，且显式禁止裸跑 `docker compose`）；② 是否要求自动删卷（删卷只在 C6 作为人工确认后的手工动作出现，无自动清理脚本）；③ 是否含真实密钥（全部为 `<EVAL_RUNNER_PASSWORD>` 占位符；设计文档 §3 那个固定弱口令字面量已随 B7 移除，本文件也不再复制它）；④ **本轮新增**：断言脚本是否会匹配到自己的搜索词（用 `kk` 剔除脚本自身 + 口令搜索词用相邻字符串拼接），以及清单里每条命令在目标机是否**形态可执行**（`bash -n` 18/18、`python -c` 过滤器实跑、`read` 写法实跑）。
- **T6 清理设计文档口令字面量（B7）** — 状态 `本轮完成`。Codex 本轮的定点修订指令第 5 条明确要求修正，因此不再是「等批准后不动」：4 处字面量移除 + §15 #8 标为已解决 + §16 json/JSONB 与文件清单纠正。验收同 B7（工作区 `grep` 命中 0）。
- **T7 清单与设计文档的定点修订（本轮）** — 状态 `本轮完成（待 Codex 复核）`。依赖：Codex 六条意见。验收：§2 本轮三条新增条目所列改动逐项可指认；清单 §14 是「意见 → 修订」对照表、§14.1 是「验证命令 → 退出码」表；设计文档 §16「v1.4 文档定点修订」表 6 行。**未启动容器、未连数据库、未调用 Embedding、未运行 `evalP1cReal`、未删任何卷、未改任何 `src/` 代码。**

**22:00–23:00（较大编码 / 验证任务）**
- **T4 独立评测容器的「无 API」验证** — 内容：按 `eval/datasets/devsupport-v0.1/P1C-CONTAINER-CHECKLIST.md` 逐条执行 C1–C11 并填 §12 记录表。**不得发起任何 Embedding 调用、不得运行 `evalP1cReal`、不得删除任何卷。** 验收：清单 §11 判据全满足（含分支 A/B 必须写明走了哪条、三个时间戳、C2 标签原文、C8b 每条探针的错误文本），实测值写回本文件 §5（把对应项从「未验证」移入「真实环境已验证」）；任何 FAIL 即停，保留原始输出交复核，不改脚本语义凑过；FAIL 与 BLOCKED 不得混记（后者是「命令没跑对」，不能写成权限结论）。
  **前置条件（本轮实测后更新，缺一就别开工）**：
  1. Codex 对**本轮文档定点修订**的复核结论已收到（清单头部就是按这条写的）。
  2. **用户手工启动 Docker Desktop** —— 本轮 `docker info` 返回 `failed to connect to the docker API at npipe:////./pipe/dockerDesktopLinuxEngine`，daemon 未运行；助手不代为启动。
  3. **用户手工创建 `.env.eval`** 并填入两个强随机口令（`EVAL_DB_PASSWORD` / `EVAL_RUNNER_PASSWORD`，例如 `openssl rand -base64 24`）。助手不生成、不代填、不写入任何提交、不出现在任何命令行或记录表里；清单 §1 已改为 `read -rs` + `export PGPASSWORD` 的取值方式。
  4. 目标机是 Windows + Git Bash：**宿主机没有 `psql`、没有 `jq`**（本轮实测），SQL 一律 `docker exec -T interview-eval-postgres psql …`、JSON 过滤一律 `python`。清单已按此改写，若执行中发现某条命令仍假设宿主机有 `psql`，那是清单缺陷 → 停下来记录，不要临场改判据。
  5. 时间窗 22:00–23:00（沿用原计划）。
- **T5 DevSupport 改造范围讨论（仅出方案，不改代码）** — 内容：明确第一阶段能力（知识库索引 + 故障排查问答）与命名/迁移策略（包名、表名、bucket 是否动）。验收：一页方案 + 影响清单交复核。

**后续门槛（需另行授权，不在当前队列执行）**
- G1 真实 Embedding 冒泡（1–2 题，付费开关显式 `-Peval.p1c.realApi=true`）。
- G2 正式 L1 全量 20 题运行 + 报告复核。
- G3 P2 Hybrid 检索 / DevSupport 功能模块实现。

## 5. 执行边界（这些一律不得写成「通过」）

以下为 2026-09-30 当前的**未验证**清单，逐项对应 `P1C-CONTAINER-CHECKLIST.md` 的条目；清单里的「预期」是期望值，执行前不构成任何证据。

- Docker 评测容器：从未启动过；`docker-compose-eval.yml`、`eval-init.sql`、`eval-user.sh` 只经过文本级静态核对（§2）。本轮 `docker info` 显示 **Docker daemon 未运行**，按边界要求没有启动它。→ C1–C5
- **命令形态核验的边界**：本轮对清单命令只做 `bash -n` 语法级检查与「合成假 JSON 喂 python 过滤器」，**没有**对任何真实容器/数据库执行过；因此「命令语法可解析 + 过滤器形状正确」不等于「今晚能跑通」。`docker inspect --format '{{json .Labels}}'` 的实际字段、`pg_hba_file_rules` 在该镜像里是否可查、`vector_dims()` 是否存在，都仍未知。→ C2、C3、C6–C9
- **C2 卷归属判据的性质**：本轮把判据从「卷上有 `config_files` 标签」改为「容器 Labels 项目名 + 容器 Mounts 卷名 + 卷 Labels 项目名」三方闭环。这个改法依据的是 Compose 的标签分层约定，属**推导**，今晚要以实测标签原文为准；如果实测发现该镜像/compose 版本连 `com.docker.compose.volume` 都不给，就如实记为判据不足，不要改成「看起来对就算过」。→ C2
- **C8b 的 PASS/FAIL/BLOCKED 分类**：错误文本判据（`permission denied for …` / `duplicate key` / `FATAL:` / `syntax error` / `current transaction is aborted`）来自 PostgreSQL 的通用报错形态与 SQLSTATE 约定（42501、25P02），**未在该镜像的 psql 上实测过具体文案**；今晚要记录错误原文，不能只写「符合预期」。→ C8b
- 初始化脚本是否真的执行过：取决于卷是否为空，从未实测；且 `eval-user.sh` 的 `CREATE ROLE ... WHERE NOT EXISTS` 意味着**复用旧卷时口令不会随 `.env.eval` 更新**，该行为未实测。清单本轮已把「对象存在」与「本次执行过 init」拆成两个命题。→ C5、C6
- 数据库权限与身份：`eval_runner` 的声明式授权与实际以该用户执行 SQL 是两个口径，两者都未在真实实例验证；负向（TRUNCATE/CREATE TABLE/INSERT 标记表应被拒）同样未验证。容器内 unix socket 的认证方式（很可能是 `trust`）未实测，所以凭据核验强制走容器内 TCP。→ C7、C8
- pgvector 实际 SQL：`vector_dims()` 是否存在于该镜像的 pgvector 版本、余弦算子、HNSW 索引可用性、`atttypmod` 是否等于 1024、`PgVectorStore` 的 `filterExpression("kb_id in ['900001']")` 行为与 `metadata`（`json` 列）里 kb_id 的实际 JSON 类型，均未验证。→ C9、C10
- 真实 HTTP 次数：外层操作预算与 OkHttp 拦截器观测值是否一致、`maxRetries(0)` 是否真的无隐藏重试，未验证。→ 不在本清单范围，属 G1
- 付费评测：从未调用 Embedding API；任何 L1 质量数字都还不存在（B5）。
- 生产检索等价性：P1-C 固定 topK=10 / minScore=0 / 关闭查询改写；而生产默认 `rag.rewrite.enabled=true`（`application.yml:169-171`），检索参数按查询长度动态取 topK 20/12/8 与 minScore 0.18/0.28（`application.yml:173-177`，`KnowledgeBaseQueryService.resolveSearchParams()` :302），`KnowledgeBaseVectorService.similaritySearch()` :125 的回退 `similaritySearchFallback()` :161 是**静默本地过滤**（:178 调用 `isDocInKnowledgeBases()` :195）而非中止。因此 L1 是**组件基线，不代表生产检索端到端行为**，`P1cRetrievalHandler` 类注释也明确不声称与生产回退等价。
- 远程 CI 对当前 HEAD 的运行结果未核对。
- **历史提交里仍有一个固定弱口令值**：B7 只清理了工作区文件（本轮改完后 `git grep -c` 在 HEAD 应命中 0），旧提交未做历史重写。分布是：`e32be2e` 把它同时写进了 `app/build.gradle`、`P1cRealRetrievalEvalTest.java`、`docker/postgres/eval-init.sql` 和设计文档（共 4 个文件）；`58b9b32` 把角色创建/口令从 `eval-init.sql` 与装配代码中移出（改由 `eval-user.sh` 的 `${EVAL_RUNNER_PASSWORD:?}` 强制、Gradle 只在显式 `-P` 时转发），三处代码与配置自此不再含该值，只剩设计文档 5 处；`6c33a05` 又在本进度文件与清单里引用了它。**当前交付物（HEAD 的工作区）三处代码/配置与两份文档都不含该值**，口令只能来自 `.env.eval` / 环境变量。该值从未用于任何真实实例（`.env.eval` 至今不存在，容器从未起过），但它作为「仓库历史里可见的示例口令」不该被照抄——今晚生成口令不要参考任何文档里的值。是否重写历史属用户决策，助手不主动做。

## 6. 交接区

- **最新已知 HEAD**：本文件与进度变更一起提交，所以「最新 HEAD」请用 `git log -1 --oneline` 现场核对（本文件最后一次被提交的记录见 §7 首行）。**最后一次代码改动提交是 `f6aa629`**（`fix: P1-C 白天离线定点修复…`），已与 `devsupport/master` 同步（`git rev-list --left-right --count devsupport/master...master` = `0 0`）。推送目标是 `devsupport`（`HyCheng-reborn/devsupport-platform`），**从不推 `origin`**（`Snailclimb/interview-guide`，上游只读）。
- **工作区状态**：`gradle/wrapper/gradle-wrapper.properties` 有刻意保留的本机改动（离线分发地址），永不提交、永不还原；未跟踪残留 `devsupport-p1b-v3-review.zip`、`eval/datasets/devsupport-p1b-v3.1-review.zip`、3 个 `eval/datasets/*.tar.gz`、`status.txt`、`interview-guide_本机环境与启动关闭说明.md` —— 均不属于进度改动，保持原样。已跟踪的进度类文件：`PROJECT_PROGRESS.md`（`9255dc9` 起）、`eval/datasets/devsupport-v0.1/P1C-CONTAINER-CHECKLIST.md`（`6c33a05` 起）。本轮**未改任何代码**（`src/main` 与 `src/test` 均无改动，`app/build.gradle` 与容器侧三个文件也未改），只动 `PROJECT_PROGRESS.md` + `P1C-CONTAINER-CHECKLIST.md` + `P1C-L1-DESIGN.md` 三份文档。
- **推送安全陷阱（重要，2026-09-30 实测确认）**：`branch.master.remote = origin`、`push.default` 未设置（即 git 默认 `simple`）、且没有配 `remote.pushDefault` / `branch.master.pushRemote`。因此**裸跑 `git push` 会把提交推到上游开源仓库 `Snailclimb/interview-guide`**，那是绝对禁止的方向。规则：推送必须写全 `git push devsupport master`；`git status -sb` 显示的 `ahead N` 是相对 `origin/master` 的，**不是**复核基线，判断是否同步只认 `git rev-list --left-right --count devsupport/master...master`（应为 `0 0`）。本仓库约定不改 git config（含不加 pushDefault），所以这个陷阱长期存在。
- **下一位助手先读什么（按顺序）**：
  1. `AGENTS.md`（工程约束）+ 本文件 §3、§5；
  2. `eval/datasets/devsupport-v0.1/P1C-CONTAINER-CHECKLIST.md`（今晚要执行的清单，含 §1 执行边界与 §12 记录表）；
  3. `eval/datasets/devsupport-v0.1/P1C-L1-DESIGN.md` §7 评测流程（Phase 0 步骤 6、回退候选处置分类表、Phase 4 后整轮判定）、§13 运行前检查清单（本轮已改为容器内可执行形态）、§16「v1.4 文档定点修订」（本轮新增小节）与「v1.4 实现期定点修订」；
  4. `app/src/test/java/interview/guide/eval/P1cRetrievalHandler.java`、`P1cRoundAvailability.java`、`P1cFrozenArtifactVerifier.java`；
  5. 装配 `P1cRealRetrievalEvalTest.java`（阶段边界与门控位置）；
  6. 容器侧配置 `docker-compose-eval.yml`、`docker/postgres/eval-init.sql`、`docker/postgres/eval-user.sh`；
  7. 数据集语义 `eval/datasets/devsupport-v0.1/README.md` 与 `P1B-REPORT.md`。
- **下一条具体任务**：等 Codex 对本轮文档定点修订（T7 / B7 / §2 新增三条）的复核结论。通过后，22:00–23:00 按 `P1C-CONTAINER-CHECKLIST.md` 执行 T4（C1→C11，逐条填 §12 实测值）——**开工前必须满足 §4 T4 列出的 5 项前置条件**，其中两项只能由用户完成：手工启动 Docker Desktop（本轮 `docker info` 显示 daemon 未运行，助手不代为启动）、手工创建 `.env.eval` 并填入两个强随机口令（助手不生成、不代填、不写入任何提交，也不允许把口令打进命令行或记录表）。执行期间不启动生产 Compose、不调用 Embedding、不运行 `evalP1cReal`、**不删除任何卷**、不打印渲染后的完整 Compose 配置；任一条目 FAIL 即停止并保留原始输出，FAIL 与 BLOCKED 分开记。
- **环境提醒**：Gradle 命令必须带 `GRADLE_USER_HOME=/c/temp/gradle-tmp`（默认用户目录含非 ASCII 字符会让 test worker 启动失败）；Jackson 是 3.x（`tools.jackson.databind`）。另两条本轮新测：**从 Windows Python 里 `subprocess.run(['bash', …])` 会解析到 `C:\WINDOWS\System32\bash.exe`（WSL 启动器）而不是 Git Bash**，会返回一堆 UTF-16 的 WSL 网络提示而把每个块都判成失败——要显式用 `C:\Program Files\Git\bin\bash.exe`；Python 读子进程输出别用 `text=True`（GBK 解码崩），用字节模式 + `errors='replace'`。宿主机无 `psql` / `jq`。

## 7. 变更记录（简短，倒序）

- 2026-09-30 18:48 — **离线文档定点修订轮（T7 / 落实 B7）**，收到 Codex 六条意见后只改三份文档（清单 / 设计文档 / 本文件），零代码改动、零容器、零网络。清单侧：C2 卷归属改用 `.Labels` 三方闭环并取消 `config_files` 判据、C8b 三条负向探针拆成独立事务 + 残留复查 + PASS/FAIL/BLOCKED 分类表、C3 限定为 `config --quiet` 与 ports-only 过滤（禁打印渲染配置）、§5 拆新卷/旧卷两命题与分支 A/B 证据、全部 SQL 改容器内 `docker exec -T … psql`（宿主机无 `psql`/`jq`，实测）、口令取值改 `read -rs` + `export`、新增 §14/§14.1 与扩展 §11/§12。设计文档侧：移除 4 处固定弱口令字面量、§15 #8 标为已解决、`metadata JSONB` → 交付的 `json`、§2 compose 示例换成实际交付内容、§12 文件清单纠正、§13 命令改为可复制执行形态、§14 方案 B 改按 `eval_run_id` 谓词删除、新增 §16 文档修订小节。验证：清单断言块 25 项重跑 **25 PASS / 0 FAIL（exit 0）**；18 个 bash 块 `bash -n` **0 失败**；3 条 python 过滤器用合成 JSON 实跑且哨兵字段未泄漏；工作区口令字面量 `grep` 命中 0（历史提交未重写，见 §5）。本轮新暴露并修正两处「今晚才会炸」的文档缺陷：`read -rs VAR?提示语` 的 ksh/zsh 语法、C7 里不存在的列 `client_host`。§5 补记命令形态核验的边界与 C2/C8b 判据的推导性质；§4 T4 前置条件改为 5 项（含 Docker daemon 未运行需用户启动）。

- 2026-09-30 17:52 — §6 新增推送安全陷阱：`branch.master.remote=origin` + `push.default` 未设 → 裸跑 `git push` 会推到上游 `Snailclimb/interview-guide`；规则改为写全 `git push devsupport master`，同步判据只认 `devsupport/master...master` 的 `0 0`。
- 2026-09-30 17:48 — 清单交付前自审，修正 4 处会让今晚执行者误判的地方：C3 的 `compose config` 是长格式（不能按 `127.0.0.1:5433:5432` 短语法 grep）、C5 明确「日志是线索、C6 对象存在性才是权威判据」、C6 补齐容器内可执行命令行、C8 说明 `CREATE TABLE` 意外成功时不要自行 DROP、C9 残留判定改用 `starts_with()`（`LIKE '__…'` 里下划线是通配符）。
- 2026-09-30 17:42 — 收到 Codex 对 `f6aa629` 的定点复核：B1–B3 通过、四个归一化哈希独立验算匹配；B1–B4 状态改为 `源码定点复核通过 · 真实环境待验证`。新增 `P1C-CONTAINER-CHECKLIST.md`（C1–C11 无 API 验证清单 + §12 记录表）并做 26 项配置静态核对（全 PASS）。修正 B4 的快照表述（阶段边界 5 次 `snapshot(ctx)`；阶段内异常时报告读 `RunState`，非逐步快照）。新发现 B7：设计文档残留固定弱口令字面量且 §15 #8 陈旧，待批准后清理。本轮无代码改动。
- 2026-09-30 17:12 — 新增本文件（同轮一次自引用修订：交接区改为现场核对 HEAD，避免被自身提交作废）；同步 `f6aa629` 后的真实状态（B1–B4 记为待复核，非已解决），并记录生产检索与 L1 的参数差异、CI 结果未核对、`status.txt` 陈旧。
- 2026-09-30 16:56 — `f6aa629`：三项复核 finding 的离线实现 + 81 条 P1-C 离线用例；设计文档同步至实现口径；推送 `devsupport`。
- 2026-09-30 — `caf05cf`：P1-C 数据与失败契约（62 条离线用例）；Codex 复核指出三项问题（见 §3）。
