# PROJECT_PROGRESS — 跨对话任务进度唯一入口

> 本项目（远程仓库 `HyCheng-reborn/devsupport-platform`，本地目录名仍为 `interview-guide`）的进度事实源。
> 新会话接手时先读本文件，再读「交接区」列出的文件。
> 最近更新：**2026-09-30 方案第二轮修订（北京时间）**，作者 Qoder。

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
- **清单第二轮定点修订（本轮，2026-09-30 白天第三个文档轮，只改清单 + 本文件）** — 状态：`离线通过（静态断言 + bash -n + 假 docker/假 psql 干跑）· 真实环境待验证`。按 Codex 本轮两处意见落实：
  - **① C2 补「卷存在但容器不存在」的分支**。上一版把归属核对写成 `docker inspect interview-eval-postgres`，容器还没起过时该命令根本无对象可查，于是分支 B（复用旧卷）在 `up` 之前没有任何归属证据可取。本轮拆成 **C2-pre**（容器侧存在性）+ **C2-pre-vol**（不依赖容器：`docker volume ls` 按「恰为 `eval_postgres_data` 或以 `_eval_postgres_data` 结尾」筛候选，再对**每个**候选卷单独 `volume inspect` 只取 `CreatedAt` 与 `com.docker.compose.project` / `.volume` 两个标签），配一张**四状态判定表**：① 无容器 + 0 卷 → 分支 A；② 无容器 + 恰 1 卷且两标签匹配 → 分支 B，`up` 前只能凭卷侧标签**初判**，C2-post 顺延到 `up` 后立即补做，并核对从容器 `Mounts` 读到的卷名与初判记录**逐字相同**（不同说明 compose 用了第三个卷 → 停）；③ 容器存在 → 可先跑完整三方核对；④ 候选卷 ≥ 2 / project 标签不符 / 两个标签缺失 → **立即停止且不 `up`**，理由是 compose 找不到匹配卷时 `up` 会新建第三个卷，而任何 `down -v` 都按项目名删卷，歧义在这个方向上扩大影响面。「预期项目名」默认取 Compose 文件所在目录名（本仓库 `interview-guide`），可被 `-p` / `COMPOSE_PROJECT_NAME` 覆盖——**这条前缀规则属推导**，今晚一律以实测标签与实测卷名为准；也明确禁止为了拿项目名再跑一次完整 `config`（那会把已解析口令打进输出）。
  - **② 消除宿主机侧口令泄漏面**。上一版每条 `eval_runner` SQL 都写 `docker exec -e PGPASSWORD="$PGPASSWORD"`：宿主机 shell 会先把变量展开成真实口令，于是口令出现在 `docker` 进程的 argv 里（进程列表 / `ps` 可见）。本轮改成让**容器内的 `/bin/sh` 展开它自己环境里的变量**——`docker exec -i -T … sh -c 'PGPASSWORD="$EVAL_RUNNER_PASSWORD"; export PGPASSWORD; exec psql -h 127.0.0.1 -p 5432 -U eval_runner -d interview_guide_eval -X -q -v ON_ERROR_STOP=1 -f -' <<'SQL'`，外层单引号保证宿主机不展开、SQL 走 here-doc（不许改成 `-c` 传 SQL：外层单引号与 SQL 里的字符串单引号必然冲突）。§1 那套「宿主机静默读取 + `export`」的录入方式整体删除，**宿主机从此不持有口令**；会话开头只做一次存在性探针 `env_present len=${#EVAL_RUNNER_PASSWORD}`（只打印长度，不打印值），输出 `env_ABSENT` 即停；新增两条硬禁令（禁 `docker inspect --format '{{json .Config.Env}}'`、禁容器内 `env` / `printenv`，两者都会把两个口令明文打出来）。C11 不再 `unset` 任何变量，改为记录「全程未在宿主机 argv / shell 历史 / 记录表出现口令值」。
  - **本轮静态核验新发现并修正的缺陷（原清单会让整份 SQL 检查静默假通过）**：`-T` 只关闭伪 TTY、**与 stdin 无关**，按 Docker CLI 文档 `-i/--interactive` 才是“Keep STDIN open even if not attached”——不加 `-i` 时 here-doc 到不了容器进程，容器内 `psql -f -` 立刻 EOF：**零输出、不报错、退出码 0**，执行者会把「无输出」写成 PASS。修法是 14 条读 stdin 的命令统一 `docker exec -i -T`，4 条不读 stdin 的（存在性探针、`pg_hba_file_rules`、两条超管 `-c`）保持 `-T`；并把这条判据写成 §0 的**逐行断言**（任何 `docker exec` 行在其后 4 行内出现 `-f -` 却没带 `-i` 即 FAIL）。另修一处判据与实现自相矛盾：§7 要求三条负向探针各带会话存活行，上一版只有 probe-1 有 → 补齐为 3 条，并在判定表新增「零输出 + exit 0 = 形态缺陷 → BLOCKED（不得写成权限结论）」。§5 末尾新增 **C5.1 形态冒烟自检**（排在任何权限/身份/维度 SQL 之前，三分支：出 `stdin_ok` 才继续；零输出即 BLOCKED；`FATAL` 走凭据分支）。
  - **验证（全部零容器、零网络、零数据库）**：清单 §0 断言 **33 项 / 33 PASS / 0 FAIL / exit 0**（其中 15 项配置与门禁断言、18 项文档一致性与清单形态断言）；**24 个 bash 块 `bash -n` 0 处语法错误**；here-doc 开闭配对 **14/14**；5 条 `python` 过滤器（C3.2 + C2-post 两条 + C2-pre-vol 两条）用合成假 JSON 实跑，形状正确且哨兵值 `SECRET` 零命中；用「假 `docker` 函数 + 假 `psql` 脚本」做对照干跑——同一条 C5.1 命令只差 `-i`：带 `-i` 时假 psql 收到 39 字节 SQL 并输出 `stdin_ok user=eval_runner`、exit 0，去掉 `-i` 时**收到 0 字节、stdout 一个字符都没有、退出码仍是 0**（缺陷复现）。断言做了**突变测试**证明非恒真：手工删掉正文的 `-i` → 该断言报 14 条违例；删一条存活行 → `1/3` FAIL；删 `-f -` → 形态断言 FAIL；改完即还原并复跑全绿（文件字节数与 sha256 与还原前一致）。
  - **本轮未做**：未启动容器、未连数据库、未调用 Embedding、未运行 `evalP1cReal`、未删任何卷、未改 `src/` 代码、未改 `P1C-L1-DESIGN.md`（其 §13 表内仍留旧 `-e PGPASSWORD="$PGPASSWORD"` 写法，当时记为已知遗留，见 §5）。**该遗留已在下一轮 R4 定点清除**（连同设计文档 §14 的同类旧形态），见下面「清单第三轮定点修订」。
- **设计文档定点修订（本轮，B7 的落地）** — 状态：`离线通过（静态一致性）`。`P1C-L1-DESIGN.md` 移除残留的固定评测口令字面量（§3 init SQL / §4 装配 / §5 Gradle / §16 变更记录共 4 处），§15 #8 标为已解决并把真正的遗留风险改记为「旧卷复用不轮换口令 → C6/C7」；`metadata JSONB` → 交付实际的 `metadata json`（含 §7 回退归属表描述）；§2 compose 示例换成交付文件内容并列出差异（旧示例带六位弱默认口令与错误的超管变量名）；§12 文件清单纠正用户/授权归属；§13 表内命令改成目标机可直接复制执行形态；§14 方案 B 改为按 `eval_run_id` 谓词删除（不再无条件全表 DELETE）；新增 §16「v1.4 文档定点修订」小节。
- **清单第三轮定点修订（本轮 R4，2026-09-30 晚，基线 `27c0afb`、文档修订提交 `42124ff`）** — 状态：`离线通过（静态断言 + bash -n + 真机旗标解析实测）· 真实环境待验证`。三件事：
  - **① 全部 `docker exec` 的 `-T` 旗标删除（并把「是否支持 `-T`」从今晚的待验证项变成本轮已测事实）**。本轮在目标机实测（**daemon 保持停止即可完成**，因为 flag 解析先于 daemon 调用）：`docker --version` = `Docker version 29.7.2, build a7dcaa6`（exit 0）；`docker exec --help` 的选项清单里**没有 `-T`**（只有 `-d`、`--detach-keys`、`-e`、`--env-file`、`-i/--interactive`、`--privileged`、`-t/--tty`、`-u`、`-w`）；`docker exec -T <name> true` → `unknown shorthand flag: 'T' in -T`、**exit 125**；`docker exec -i …` / 裸 `docker exec …` 解析通过（之后才因 npipe 连接失败，exit 1）；`docker compose exec --help` **确有** `-T, --no-tty`。上一版正文 18 条 `docker exec` 命令行全部带 `-T` → **今晚会在旗标解析层整体失败，一条 SQL 都取不到证据**。改法：14 条读 here-doc 的改 `docker exec -i`，4 条不读 stdin 的（§1 存在性探针、C6.0 读 `pg_hba`/系统目录、C6.1、C8a 超管 `-c`）改裸 `docker exec`；C4 的 `docker compose … exec -T eval-postgres pg_isready` 合法、保持不动。§1 的两处「今晚再看 CLI 是否收 `-T`」推导级存疑换成**实测事实块**；**但「不加 `-i` 时 here-doc 到不了容器」仍是文档推理**（本轮实测只覆盖旗标解析层），继续由 C5.1 证伪，见 §5。§11 加「命令行形态判据」、§12 的 C5.1 行加形态核对格（含「混入 `-T` 得 125 属抄写缺陷，不进三分支、不得写成数据库结论」）。
  - **② C4 时间记录改为窗口**。上一版在 `up -d` + `ps` + `pg_isready` **之后**才 `date` 记「up 时刻」，再要求卷与容器的创建时间「晚于该时刻」——可创建明明发生在 `up` 期间，判据在数学上不可能成立，而且执行者拿一个更晚的时间去比对，新旧卷都可能「看起来更早」而意外通过。本轮改为 `UP_START` 在 `up -d` **之前**取、`UP_END` 在健康检查与 `pg_isready` 之后取，两值都进 §12；分支 A 判据 1 改写为**区间核对**（卷 `CreatedAt` 与容器 `CreatedAt`/`StartedAt` 全部落在 [up 开始, up 结束] 内，含等号），分支 B/B2 与 §1 的「口令新鲜度」按同一窗口重述，§11/§12 的记录格由单格拆为开始/结束 + 区间核对。
  - **③ 设计文档同步（`P1C-L1-DESIGN.md`）**：§13 第 2 项（改为 `docker exec -i` + 容器内 `sh -c` 展开 + 顶格 here-doc，并写明 `docker exec` 无 `-T`）、第 6 项（超管不读 stdin → 裸 `docker exec`）、表下段落（删除宿主机 `read -rs` + `export`/`unset` 那套取值教学，标注已作废）；扫描 HEAD 后**另发现 §14「失败时清理规则」的方案 A/B 代码块才是更危险的一处**（失败应急时最会被照抄）：方案 A 两条 compose 缺 `--env-file .env.eval`、方案 B 带 `-T` 且用 `-e` 把宿主机口令透进 argv、并用 `-c "DELETE …"` 传 SQL → 全部改为清单 §1 口径（`docker exec -i` + 容器内展开 + here-doc，`DELETE` 带 `metadata->>'eval_run_id'` 谓词 + `remaining_for_run_id=` 复核），`down -v` 仍只作人工确认后的手工动作且要求把确认记进清单 §12；§16「v1.4 文档定点修订」新增第 7、8 行。**为防再次漂移，清单 §0 新增 5 条断言机器核对设计文档的「可复制面」**（严格限定为 bash 围栏 + §13 表格行，**不含 §16 历史描述**，否则会把「旧写法」的说明文字判成违例）。
  - **本轮还修掉两处取证工具自身的缺陷**（都属于「工具失效比判据更危险」那一类）：**a)** 新写的 5 条断言最初直接含「三个反引号 + bash」形式的围栏字面量，它会**截断核验脚本自己的围栏提取正则**，结果是新增断言静默不参与判定、脚本却照样报 exit 0——计数从虚报的 34 变成真实 39 才是修复后的结果；**b)** §0 的 `ok()` 原本只 `print`、**任何 FAIL 都不改变退出码**，所以此前各行记录的「exit=0」并不证明断言通过；现改为收集失败项并在块末以 exit 1 退出并逐条列出（实测：注入一条假断言 → exit 1；把那行换掉模拟旧形状 → 1 条 FAIL 仍 exit 0）。写本轮记录时把旧写法原样贴进 §14.1 的一行，重跑立刻 **38 PASS / 1 FAIL**，改成文字描述后恢复 39/39——顺带成为「引用缺陷一律用描述」这条规则确由断言在执行的真实证据。
  - **验证（零容器、零网络、零数据库）**：清单 §0 断言 **39 项 / 39 PASS / 0 FAIL / exit 0**（R4 早期 34 项，#11 的 5 条设计文档断言后 39）；清单 **24 个 bash 块 `bash -n` 0 处语法错误**；设计文档 **1 个 bash 块 0 处语法错误**、here-doc 开闭 1/1；形态清点：`docker exec` 命令行 18 条 = 带 `-i` **14** + 裸 **4**，带 `-T` **0** 条；here-doc 配对 `14/14`；`--env-file` 违例（清单与设计）= 无；反向突变对照全部非恒真——恢复旧旗标组合 → 「零 `-T`」断言报 **18 条违例**（干净 0），只删 `-i` → 「`-f -` ⇒ `-i`」报 **14 条**，设计文档 5 条各自回潜旧写法分别立刻响且互不串台。逐条命令与退出码见清单 §14.1 的 R4 行。
  - **本轮未做**：未启动 Docker Desktop、未连数据库、未调用 Embedding、未运行 `evalP1cReal`、未删任何卷、未改任何 `src/` 代码与容器侧配置文件（`docker-compose-eval.yml` / `eval-init.sql` / `eval-user.sh` / `app/build.gradle` 全部未动）。**已知未清的陈旧命令**（按边界「仅提交本轮相关文档」未动，逐条现场核对后如实列出，待批准再清）：`docker-compose-eval.yml:7`/`:8` 注释里的「停止 / 清除」示例缺 `--env-file .env.eval`，且 :8 把 `down -v`（不可逆删卷）写成常规清理步骤；`P1cRealRetrievalEvalTest.java:774` 的报错提示同样缺 `--env-file`，`:793` 更直接教人执行 `down -v 然后重新 up -d`（缺 `--env-file` 且是删卷动作），:85 的用法示例把 `EVAL_RUNNER_PASSWORD=...` 以「命令行前置赋值」形态写出（会进 shell 历史）；设计文档 :165/:177/:294/:314 的行内示例与 :178/:246（同样是 `down -v`）属早期写法。这些都不在今晚 T4 的执行路径上（T4 只跑清单正文），但属同一形态问题域。清单 §0 断言块本轮重跑为 **39 项 / 39 PASS / 0 FAIL，exit 0**（`6c33a05` 那轮 26 项、其后 25→31→33→34→39；组成各不相同，逐轮数字与复跑命令以清单 §0 / §14.1 为准）。生产 `docker-compose.yml` 与 `docker-compose.dev.yml` 中不含任何 `eval` 引用；`.gitignore` 忽略 `.env.eval`，`git ls-files` 只有 `.env.eval.example`；**工作区内口令字面量扫描命中 0**（注意：`git log`/历史提交里 `P1C-L1-DESIGN.md` 的旧版本仍含该字面量，未做历史重写——该值从未被用于任何真实实例，因为 `.env.eval` 至今不存在）。命令形态核验：**24 个 bash 块 `bash -n` 全部 0 失败**；5 条 `python` 过滤器用合成假 JSON 实跑、哨兵字段未泄漏；here-doc 命令的 stdin 转发用假 `docker`/假 `psql` 对照干跑（见上一条）。
- **CI 配置** — 状态 `代码已写`。提交 `b2d1ca0`，`.github/workflows/ci.yml` 执行 `./gradlew :app:test --no-daemon` 与前端 `pnpm run build` + 4 组前端测试。**远程 Actions 对 `f6aa629` 的实际运行结果未核对**（本轮按边界要求未访问外部服务），不记为通过。
- **真实环境已验证** — 目前**零项**。没有任何 P1-C 环节接触过真实数据库、Embedding API 或评测容器。

## 3. 当前阻塞与待复核事项

差异说明：Codex 复核的对象是 `caf05cf`，本地/远程 HEAD 已前进到 `f6aa629`。**2026-09-30 白天收到 Codex 对 `f6aa629` 的定点复核结论：B1–B3 的离线修复通过；四份冻结工件在提交 `2903ec2` 的归一化 SHA-256 已由复核方独立验算且全部匹配。真实数据库行为仍未验证。** 因此 B1–B3 从 `待复核` 改为 `源码定点复核通过 · 真实环境待验证`，但仍不等于 `真实环境已验证`。

同日收到 Codex 对**容器验证清单（`ff486e7` 状态）**的六条定点修订意见（卷标签判据 / 负向探针可回滚且可辨因 / 不打印渲染配置 / 区分新卷初始化与旧卷复用 / 设计文档口令与 json 类型 / 目标环境命令可执行性），本轮全部落实到文档，见 §2 三条新增、清单 §14 对照表、设计文档 §16。**该轮结论待复核方确认，本轮不自称「已解决」。**

其后又收到对清单（`203c37f` 状态）的**两处**定点修订意见（卷在容器不在时 C2-post 无法先于 `up` 执行的分支 / 把所有把宿主机口令值展开进进程参数的 `docker exec` 改写并做假值验证），本轮（§2「清单第二轮定点修订」）已全部落实到清单正文，并在静态核验中新发现 `-i`/stdin 那个会让整份 SQL 检查静默假通过的缺陷（该缺陷不在复核意见里，是本轮自查得到的）。**同样待复核方确认**；清单 §14 的 #6/#7/#8 是「意见 → 修订」对照表，§14.1 是「验证命令 → 退出码」表。

最新一轮（R4 / T9，基线 `27c0afb`、提交 `42124ff`）按复核方给的 4 项离线指令落实：`docker exec` 的 `-T` 全删（并把「是否支持 `-T`」由今晚待验证改为**本轮真机实测事实**，exit 125）、C4 时间记录改为 up 前/后窗口 + 分支 A 区间核对、设计文档 §13 与 §14 的可复制面同步到清单口径、本文件同步。**结论仍待复核方确认**，对应清单 §14 的 #9/#10/#11 与 §14.1 的 R4 行、设计文档 §16 第 7/8 行。**这一轮没有新增任何真实环境证据：C1–C11 仍零项实测，`§2「真实环境已验证」= 零项` 未变。**

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
- **T3 清单静态自检** — 状态 `已完成（本轮再次追加）`。四项防误用核查：① 是否引用生产 Compose/端口（清单只出现 `docker-compose-eval.yml`、5433，且显式禁止裸跑 `docker compose`）；② 是否要求自动删卷（删卷只在 C6 作为人工确认后的手工动作出现，无自动清理脚本，本轮状态 ④ 进一步把歧义拦在 `up` 之前）；③ 是否含真实密钥（全部为 `<EVAL_RUNNER_PASSWORD>` 占位符；**本轮起连「宿主机持有口令变量」这个假设也删掉了**，断言里额外要求正文不再出现把宿主变量透进 argv 的写法，`-e PGPASS…` / `PGPASSWORD="$PG…"` 残留各 0 命中）；④ 断言脚本是否会匹配到自己的搜索词（用 `kk` 剔除脚本自身 + 口令搜索词用相邻字符串拼接），以及每条命令在目标机是否**形态可执行**（上一轮 `bash -n` 18/18 + `read` 写法实跑；**本轮 24/24 + 假 `docker`/假 `psql` 干跑 + 突变测试**）。
- **T6 清理设计文档口令字面量（B7）** — 状态 `本轮完成`。Codex 本轮的定点修订指令第 5 条明确要求修正，因此不再是「等批准后不动」：4 处字面量移除 + §15 #8 标为已解决 + §16 json/JSONB 与文件清单纠正。验收同 B7（工作区 `grep` 命中 0）。
- **T7 清单与设计文档的定点修订（上一轮）** — 状态 `已完成（待 Codex 复核）`。依赖：Codex 六条意见。验收：§2 该轮三条新增条目所列改动逐项可指认；清单 §14 是「意见 → 修订」对照表、§14.1 是「验证命令 → 退出码」表；设计文档 §16「v1.4 文档定点修订」表 6 行。**未启动容器、未连数据库、未调用 Embedding、未运行 `evalP1cReal`、未删任何卷、未改任何 `src/` 代码。**
- **T8 清单两处定点修订（上一轮）** — 状态 `已完成（待 Codex 复核）`。依赖：Codex 对 `203c37f` 状态清单的两处意见。验收：C2 的「卷在容器不在」分支与四状态判定表可指认、每条 `eval_runner` SQL 都是容器内展开且 argv 无口令值、§0 断言覆盖这两处形态并要求 `-i`。改动文件只有 `P1C-CONTAINER-CHECKLIST.md` 与本文件（提交 `27c0afb`）。**未启动容器、未连数据库、未调用 Embedding、未运行 `evalP1cReal`、未删任何卷、未改 `src/` 代码，也未改 `P1C-L1-DESIGN.md`**（按当时边界「只修清单并验证命令形态」；该文档 §13 的旧写法在 T9 清除）。
- **T9 清单 + 设计文档第三轮定点修订（本轮 R4）** — 状态 `本轮完成（待 Codex 复核）`。依赖：复核指令第 1 条「用本机 `docker exec --help` 核对选项，不能再把『`docker exec` 是否支持 `-T`』留作今晚验证」；本轮据此在目标机自行实测（`--version` 与两条 `--help` exit 0、`docker exec -T nosuchcontainer true` exit 125，全程不启动 Docker Desktop、不接触任何容器或数据库）。验收：正文 `docker exec` 命令行零 `-T` 且 14 条 here-doc 全带 `-i`（断言 + 形态清点可指认）、C4 改为 up 前/后两个时刻且分支 A 判据写成区间核对、设计文档 §13 与 §14 的可复制面与清单同口径并由 5 条新增断言机器核对、§0 断言 39/39 且退出码在有 FAIL 时为 1。**改动文件只有 `P1C-CONTAINER-CHECKLIST.md`、`P1C-L1-DESIGN.md` 与本文件**；未启动容器、未连数据库、未调用 Embedding、未运行 `evalP1cReal`、未删任何卷、未改 `src/` 与容器侧配置。**T4 今晚的执行路径以本轮修订版为准。**

**22:00–23:00（较大编码 / 验证任务）**
- **T4 独立评测容器的「无 API」验证** — 内容：按 `eval/datasets/devsupport-v0.1/P1C-CONTAINER-CHECKLIST.md` 逐条执行 C1–C11 并填 §12 记录表。**不得发起任何 Embedding 调用、不得运行 `evalP1cReal`、不得删除任何卷。** 验收：清单 §11 判据全满足（含分支 A/B 必须写明走了哪条、三个时间戳、C2 标签原文与 C2-pre-vol 的 `CAND_COUNT`/卷名/`CreatedAt` 快照、C8b 每条探针的错误文本），实测值写回本文件 §5（把对应项从「未验证」移入「真实环境已验证」）；任何 FAIL 即停，保留原始输出交复核，不改脚本语义凑过；FAIL 与 BLOCKED 不得混记（后者是「命令没跑对」，不能写成权限结论）。**开工后第一件事是 §5 的 C5.1 形态冒烟自检**：没有 `stdin_ok user=eval_runner` 原文，后面所有 SQL 类条目一律不能记 PASS。
  **前置条件（本轮实测后更新，缺一就别开工）**：
  1. Codex 对**本轮文档定点修订**的复核结论已收到（清单头部就是按这条写的）。
  2. **用户手工启动 Docker Desktop** —— 本轮 `docker info` 返回 `failed to connect to the docker API at npipe:////./pipe/dockerDesktopLinuxEngine`，daemon 未运行；助手不代为启动。
  3. **用户手工创建 `.env.eval`** 并填入两个强随机口令（`EVAL_DB_PASSWORD` / `EVAL_RUNNER_PASSWORD`，例如 `openssl rand -base64 24`）。助手不生成、不代填、不写入任何提交。**本轮起口令不进任何命令行**：清单已删除上一版的「宿主机静默读取 + `export PGPASSWORD`」录入方式和 `docker exec -e PGPASSWORD=…` 透传写法，口令只由 compose 在 `up` 时注入容器环境，核验时由容器内 `/bin/sh` 展开它自己那个变量——所以执行者**不需要也不应该**在宿主机 shell 里设置任何口令变量。
  4. 目标机是 Windows + Git Bash：**宿主机没有 `psql`、没有 `jq`**（实测），SQL 一律在容器内执行——**读 here-doc 的命令是 `docker exec -i interview-eval-postgres sh -c '…exec psql … -f -'`，不读 stdin 的超管/探针命令是裸 `docker exec … psql -c`**；`docker exec` **没有 `-T` 旗标**（本轮真机实测：带 `-T` 报 `unknown shorthand flag: 'T' in -T`、exit 125，见清单 §1「实测事实」），只有 `docker compose exec` 有 `-T/--no-tty`，所以 C4 那条 `compose … exec -T` 是合法的。JSON 过滤一律 `python`。若执行中发现某条命令仍假设宿主机有 `psql`、或某条 `docker exec` 命令行混进 `-T`，那是清单/抄写缺陷 → 如实记录并按「命令抄写缺陷」改正形态后重跑，**不要临场改判据、不要把 125 写成数据库结论**。
  5. 时间窗 22:00–23:00（沿用原计划）。
- **T5 DevSupport 改造范围讨论（仅出方案，不改代码）** — 内容：明确第一阶段能力（知识库索引 + 故障排查问答）与命名/迁移策略（包名、表名、bucket 是否动）。验收：一页方案 + 影响清单交复核。

**后续门槛（需另行授权，不在当前队列执行）**
- G1 真实 Embedding 冒泡（1–2 题，付费开关显式 `-Peval.p1c.realApi=true`）。
- G2 正式 L1 全量 20 题运行 + 报告复核。
- G3 P2 Hybrid 检索 / DevSupport 功能模块实现。

## 5. 执行边界（这些一律不得写成「通过」）

以下为 2026-09-30 当前的**未验证**清单，逐项对应 `P1C-CONTAINER-CHECKLIST.md` 的条目；清单里的「预期」是期望值，执行前不构成任何证据。

- Docker 评测容器：从未启动过；`docker-compose-eval.yml`、`eval-init.sql`、`eval-user.sh` 只经过文本级静态核对（§2）。本轮 `docker info` 显示 **Docker daemon 未运行**，按边界要求没有启动它。→ C1–C5
- **命令形态核验的边界**：本轮对清单命令只做 `bash -n` 语法级检查、「合成假 JSON 喂 python 过滤器」和「假 `docker` 函数 + 假 `psql` 脚本」的离线干跑，**没有**对任何真实容器/数据库执行过；因此「命令语法可解析 + 过滤器形状正确」不等于「今晚能跑通」。`docker inspect --format '{{json .Labels}}'` 的实际字段、`pg_hba_file_rules` 在该镜像里是否可查、`vector_dims()` 是否存在，都仍未知。→ C2、C3、C6–C9
- **`-i` / stdin 转发这条判据的性质（本轮新增，今晚第一优先核验）**：清单 §1 关于「不加 `-i` 时 here-doc 到不了容器进程、`psql -f -` 会零输出 + 退出码 0」的表述，依据是 Docker CLI 文档对 `-i/--interactive`（“Keep STDIN open even if not attached”）的措辞做的**推理**，本机 daemon 未运行、**没有真机实测**；本轮那个假 `docker` 干跑也是按同一条推理建模的（它按参数里有无 `-i` 决定转发还是丢弃 stdin），所以干跑证明的是「命令形状正确、宿主机 argv 不含口令值、断言非恒真」，**不能**当作 CLI 真实行为的证据。今晚若真机出现「命令零输出 + exit 0」，就按「形态缺陷」记 BLOCKED，不得凭清单文字判 PASS，也不得把零输出写成「权限没问题」。**`-T` 那一面本轮已改测为事实、不再是待验证项**：目标机 `docker exec --help` 不列 `-T`，`docker exec -T … true` 在旗标解析层就报 `unknown shorthand flag: 'T' in -T`、**exit 125**（解析先于 daemon 调用，所以 daemon 停止也能判定），而 `docker compose exec --help` 确有 `-T/--no-tty`——正文 18 条 `docker exec` 已按此全部去掉 `-T`（14 条带 `-i`、4 条裸执行），今晚若某条命令仍报 125，就是有人照抄了旧版打印出来的命令，按抄写缺陷改正后重跑，不许现场换成 `bash -c` 或改用 `-c` 传 SQL（那会把命令形态问题伪装成数据库结论）。`docker --version` 与 CLI 实现（Docker Desktop / compose 版本）也要记进 §12，因为判据依赖版本（本轮实测版本 `29.7.2, build a7dcaa6`）。
- **C2 卷归属判据的性质**：本轮把判据从「卷上有 `config_files` 标签」改为「容器 Labels 项目名 + 容器 Mounts 卷名 + 卷 Labels 项目名」三方闭环。这个改法依据的是 Compose 的标签分层约定，属**推导**，今晚要以实测标签原文为准；如果实测发现该镜像/compose 版本连 `com.docker.compose.volume` 都不给，就如实记为判据不足，不要改成「看起来对就算过」。**本轮新增的「卷在、容器不在」分支还叠了两层推导**：一是「卷的完整名字 = `<项目名>_<卷短名>`」这条命名约定，二是「默认项目名 = Compose 文件所在目录名（本仓库 `interview-guide`），可被 `-p` / `COMPOSE_PROJECT_NAME` 覆盖」。因此状态 ② 的卷侧结论只算**初判**，必须在 `up` 之后用容器 `Mounts` 里的卷名逐字比对来闭环，比对不一致就按状态 ④ 停；候选过滤用的是「等于 `eval_postgres_data` 或以 `_eval_postgres_data` 结尾」这种**宽松匹配**（就是为了不被前缀规则绊住），所以今晚很可能一次筛出多个候选——那属状态 ④，不 `up`。生产栈容器名是 `interview-postgres`、库名与端口都不同，不能拿来当同一个对象核对。→ C2
- **C8b 的 PASS/FAIL/BLOCKED 分类**：错误文本判据（`permission denied for …` / `duplicate key` / `FATAL:` / `syntax error` / `current transaction is aborted`）来自 PostgreSQL 的通用报错形态与 SQLSTATE 约定（42501、25P02），**未在该镜像的 psql 上实测过具体文案**；今晚要记录错误原文，不能只写「符合预期」。→ C8b
- 初始化脚本是否真的执行过：取决于卷是否为空，从未实测；且 `eval-user.sh` 的 `CREATE ROLE ... WHERE NOT EXISTS` 意味着**复用旧卷时口令不会随 `.env.eval` 更新**，该行为未实测。清单本轮已把「对象存在」与「本次执行过 init」拆成两个命题。→ C5、C6
- 数据库权限与身份：`eval_runner` 的声明式授权与实际以该用户执行 SQL 是两个口径，两者都未在真实实例验证；负向（TRUNCATE/CREATE TABLE/INSERT 标记表应被拒）同样未验证。容器内 unix socket 的认证方式（很可能是 `trust`）未实测，所以凭据核验强制走容器内 TCP。→ C7、C8
- pgvector 实际 SQL：`vector_dims()` 是否存在于该镜像的 pgvector 版本、余弦算子、HNSW 索引可用性、`atttypmod` 是否等于 1024、`PgVectorStore` 的 `filterExpression("kb_id in ['900001']")` 行为与 `metadata`（`json` 列）里 kb_id 的实际 JSON 类型，均未验证。→ C9、C10
- 真实 HTTP 次数：外层操作预算与 OkHttp 拦截器观测值是否一致、`maxRetries(0)` 是否真的无隐藏重试，未验证。→ 不在本清单范围，属 G1
- 付费评测：从未调用 Embedding API；任何 L1 质量数字都还不存在（B5）。
- 生产检索等价性：P1-C 固定 topK=10 / minScore=0 / 关闭查询改写；而生产默认 `rag.rewrite.enabled=true`（`application.yml:169-171`），检索参数按查询长度动态取 topK 20/12/8 与 minScore 0.18/0.28（`application.yml:173-177`，`KnowledgeBaseQueryService.resolveSearchParams()` :302），`KnowledgeBaseVectorService.similaritySearch()` :125 的回退 `similaritySearchFallback()` :161 是**静默本地过滤**（:178 调用 `isDocInKnowledgeBases()` :195）而非中止。因此 L1 是**组件基线，不代表生产检索端到端行为**，`P1cRetrievalHandler` 类注释也明确不声称与生产回退等价。
- **上一轮的已知遗留已清除（本轮 R4）**：`P1C-L1-DESIGN.md` §13 表内/表下那段旧写法（`docker exec -T` + 把宿主机口令变量透进 `docker` argv，以及教宿主机静默录入 + `export`/`unset` 的取值说明）已按清单 §1 口径改写，同轮另清除了扫描 HEAD 时新发现的 §14「失败时清理规则」方案 A/B（缺 `--env-file`、`-T`、`-e` 透传、`-c "DELETE …"`）。**并且这条一致性现在由机器保证**：清单 §0 有 5 条断言只扫设计文档的「可复制面」（bash 围栏 + §13 表格行，不含 §16 历史描述）。**仍按边界未动的陈旧命令书面残留**（不在今晚执行路径上，待批准后另行清理）：`docker-compose-eval.yml:7`/`:8`（缺 `--env-file`，:8 还把 `down -v` 写成常规清理步骤）、`P1cRealRetrievalEvalTest.java:774`/`:793`（报错提示缺 `--env-file`，:793 直接教人 `down -v`）、同文件 :85 的 `EVAL_RUNNER_PASSWORD=…` 命令行前置赋值示例、设计文档 :165/:177/:294/:314 的行内示例（同段 :178/:246 也是 `down -v`）。→ 执行 T4 时一律以 `P1C-CONTAINER-CHECKLIST.md` 为准。
- 远程 CI 对当前 HEAD 的运行结果未核对。
- **历史提交里仍有一个固定弱口令值**：B7 只清理了工作区文件（本轮改完后 `git grep -c` 在 HEAD 应命中 0），旧提交未做历史重写。分布是：`e32be2e` 把它同时写进了 `app/build.gradle`、`P1cRealRetrievalEvalTest.java`、`docker/postgres/eval-init.sql` 和设计文档（共 4 个文件）；`58b9b32` 把角色创建/口令从 `eval-init.sql` 与装配代码中移出（改由 `eval-user.sh` 的 `${EVAL_RUNNER_PASSWORD:?}` 强制、Gradle 只在显式 `-P` 时转发），三处代码与配置自此不再含该值，只剩设计文档 5 处；`6c33a05` 又在本进度文件与清单里引用了它。**当前交付物（HEAD 的工作区）三处代码/配置与两份文档都不含该值**，口令只能来自 `.env.eval` / 环境变量。该值从未用于任何真实实例（`.env.eval` 至今不存在，容器从未起过），但它作为「仓库历史里可见的示例口令」不该被照抄——今晚生成口令不要参考任何文档里的值。是否重写历史属用户决策，助手不主动做。

## 6. 交接区

- **最新已知 HEAD**：本文件与进度变更一起提交，所以「最新 HEAD」请用 `git log -1 --oneline` 现场核对（本文件最后一次被提交的记录见 §7 首行）。**最后一次代码改动提交是 `f6aa629`**（`fix: P1-C 白天离线定点修复…`），已与 `devsupport/master` 同步（`git rev-list --left-right --count devsupport/master...master` = `0 0`）。推送目标是 `devsupport`（`HyCheng-reborn/devsupport-platform`），**从不推 `origin`**（`Snailclimb/interview-guide`，上游只读）。
- **工作区状态**：`gradle/wrapper/gradle-wrapper.properties` 有刻意保留的本机改动（离线分发地址），永不提交、永不还原；未跟踪残留 `devsupport-p1b-v3-review.zip`、`eval/datasets/devsupport-p1b-v3.1-review.zip`、3 个 `eval/datasets/*.tar.gz`、`status.txt`、`interview-guide_本机环境与启动关闭说明.md` —— 均不属于进度改动，保持原样。已跟踪的进度类文件：`PROJECT_PROGRESS.md`（`9255dc9` 起）、`eval/datasets/devsupport-v0.1/P1C-CONTAINER-CHECKLIST.md`（`6c33a05` 起）。**本轮只动两份文档**：`P1C-CONTAINER-CHECKLIST.md`（443 → 579 行）+ 本文件；`src/main`、`src/test`、`app/build.gradle`、容器侧三个文件、`P1C-L1-DESIGN.md` 均未改。
- **推送安全陷阱（重要，2026-09-30 实测确认）**：`branch.master.remote = origin`、`push.default` 未设置（即 git 默认 `simple`）、且没有配 `remote.pushDefault` / `branch.master.pushRemote`。因此**裸跑 `git push` 会把提交推到上游开源仓库 `Snailclimb/interview-guide`**，那是绝对禁止的方向。规则：推送必须写全 `git push devsupport master`；`git status -sb` 显示的 `ahead N` 是相对 `origin/master` 的，**不是**复核基线，判断是否同步只认 `git rev-list --left-right --count devsupport/master...master`（应为 `0 0`）。本仓库约定不改 git config（含不加 pushDefault），所以这个陷阱长期存在。
- **下一位助手先读什么（按顺序）**：
  1. `AGENTS.md`（工程约束）+ 本文件 §3、§5；
  2. `eval/datasets/devsupport-v0.1/P1C-CONTAINER-CHECKLIST.md`（今晚要执行的清单，含 §1 执行边界与 §12 记录表）；
  3. `eval/datasets/devsupport-v0.1/P1C-L1-DESIGN.md` §7 评测流程（Phase 0 步骤 6、回退候选处置分类表、Phase 4 后整轮判定）、§13 运行前检查清单与 §14 失败时清理规则（**本轮 R4 已与清单 §1 同口径：`docker exec -i` + 容器内展开口令 + here-doc，超管项裸 `docker exec`；并由清单 §0 的 5 条断言机器核对**）、§16「v1.4 文档定点修订」（现 8 行）与「v1.4 实现期定点修订」；
  4. `app/src/test/java/interview/guide/eval/P1cRetrievalHandler.java`、`P1cRoundAvailability.java`、`P1cFrozenArtifactVerifier.java`；
  5. 装配 `P1cRealRetrievalEvalTest.java`（阶段边界与门控位置）；
  6. 容器侧配置 `docker-compose-eval.yml`、`docker/postgres/eval-init.sql`、`docker/postgres/eval-user.sh`；
  7. 数据集语义 `eval/datasets/devsupport-v0.1/README.md` 与 `P1B-REPORT.md`。
- **下一条具体任务**：等 Codex 对本轮 R4 修订（T9：`-T` 全删 + C4 时间窗口 + 设计文档 §13/§14 同步 + §0 断言 34→39 与退出码修正）的复核结论。**通过后才在 22:00–23:00 按 `P1C-CONTAINER-CHECKLIST.md` 执行 T4**（C1→C11，逐条填 §12 实测值）——**开工前必须满足 §4 T4 列出的 5 项前置条件**，其中两项只能由用户完成：手工启动 Docker Desktop（daemon 未运行，助手不代为启动）、手工创建 `.env.eval` 并填入两个强随机口令（助手不生成、不代填、不写入任何提交；清单也不允许把口令放进宿主机命令行或记录表）。**执行顺序上的硬要求**：`up` 之前先跑 C2-pre + C2-pre-vol 并按四状态判定表定档（状态 ④ 不 `up`）；C4 的 `date` 取两次——`up -d` **之前**记 UP_START、健康检查与 `pg_isready` 之后记 UP_END，分支 A 用这两个时刻的**区间**核对卷/容器创建时间；`up` 之后的第一件事是 C5.1 形态冒烟自检、拿到 `stdin_ok user=eval_runner` 原文才允许继续任何 SQL 类条目。**命令行形态**：读 here-doc 一律 `docker exec -i`，不读 stdin 一律裸 `docker exec`，**没有任何 `docker exec` 带 `-T`**（真机 exit 125）；只有 C4 那条 `docker compose … exec -T` 保留。执行期间不启动生产 Compose、不调用 Embedding、不运行 `evalP1cReal`、**不删除任何卷**、不打印渲染后的完整 Compose 配置、不读容器环境变量（`.Config.Env` / `env` / `printenv`）；任一条目 FAIL 即停止并保留原始输出，FAIL 与 BLOCKED 分开记（`exit 125` 属抄写缺陷，不是数据库结论）。
- **环境提醒**：Gradle 命令必须带 `GRADLE_USER_HOME=/c/temp/gradle-tmp`（默认用户目录含非 ASCII 字符会让 test worker 启动失败）；Jackson 是 3.x（`tools.jackson.databind`）。从 Windows Python 里 `subprocess.run(['bash', …])` 会解析到 `C:\WINDOWS\System32\bash.exe`（WSL 启动器）而不是 Git Bash，会返回一堆 UTF-16 的 WSL 网络提示而把每个块都判成失败——要显式用 `C:\Program Files\Git\bin\bash.exe`；Python 读子进程输出别用 `text=True`（GBK 解码崩），用字节模式 + `errors='replace'`。宿主机无 `psql` / `jq`。**本轮 R4 新增三条**：① **`docker exec` 没有 `-T` 旗标**（真机 `Docker version 29.7.2, build a7dcaa6` 实测：带 `-T` 报 `unknown shorthand flag: 'T' in -T`、exit 125，且 flag 解析先于 daemon 调用，所以**daemon 停止也能测**）；`docker compose exec` 才有 `-T/--no-tty`。② **文档里不要写出真围栏标记**（三个反引号 + `bash`）——清单 §0 的核验脚本按围栏提取正文，围栏字面量会截断它自己的正则，让新增断言静默不执行却仍报 exit 0；断言里用 `chr(96)*3` 拼。同理「旧写法」一律用文字描述，原样粘贴会被自己的断言判成违例（本轮实测：贴进一行 → 38 PASS / 1 FAIL）。③ **`ok()` 只 print 的断言脚本没有失败信号**——退出码恒 0，「exit=0」不证明通过；已改为收集失败项并 `raise SystemExit(1 if BAD else 0)`。

## 7. 变更记录（简短，倒序）

- 2026-10-01 — **RagChatController 来源组装责任下沉到 Service**（见 §12）。将 `buildSourceReferences` 的数据库查询与来源组装从 `KnowledgeBaseQueryService` 移入已持有 `KnowledgeBaseRepository` 的 `RagChatSessionService`，`RagChatController` 不再直接注入 Repository。仅改这两个 Service + Controller + 三处测试 + 本文件；未改来源字段/排序/SSE 序列化/状态持久化契约、未改 schema、未动其他端点、未调外部 API / 数据库。状态 `代码已写 · 离线通过（compileJava/compileTestJava exit 0 + RagChatControllerTest/RagChatSessionServiceTest/KnowledgeBaseQueryServiceTest/SourceReferenceTest 全绿 :app:test exit 0）· 真实环境未验证`。
- 2026-10-01 — **`KnowledgeBaseController.queryKnowledgeBaseStream()` 流式错误兜底语义定点恢复**（见 §11）。仅改该端点 + 新增 `KnowledgeBaseControllerStreamTest` + 本文件；未改共享的 `answerQuestionStream`、未改 RagChatController 的 SSE 协议。未调外部 API / 数据库、未启 Docker。状态 `代码已写 · 离线通过（新类 7 用例 + RagChatControllerTest/QueryServiceTest 全绿，:app:test exit 0）· 真实环境未验证`。
- 2026-10-01 — **Phase 1 流式回答前端状态处理定点修复**（见 §10）。仅改前端：新增 `ragStreamStatus.ts` + 测试、`stream.ts` 透传 done 状态、`ragChat.ts` 收敛回调、`KnowledgeBaseQueryPage.tsx` 按状态写定 + 门控来源、`package.json` / `ci.yml` 注册新测试、本文件；消费 §9 后端 `done.status` 契约，不再无条件写 COMPLETED。未调外部 API / 数据库、未启 Docker。状态 `代码已写 · 离线通过（node --test 5 绿 + pnpm build exit 0）· 真实环境未验证`。
- 2026-10-01 — **Phase 1 流式回答服务端最终状态与事件顺序定点修复**（见 §9）。仅改 `RagChatController` + `KnowledgeBaseQueryService` + 两处测试 + 本文件；未调付费 API、未启 Docker、未跑真实 L1、未动生产数据。状态 `代码已写 · 离线通过 · 真实环境未验证`。

- 2026-09-30 — Phase 1 编码启动（见下方 §8）。
- 2026-09-30 — **`docs/devsupport-phase1-plan.md` 第二轮定点修订（Codex 复核 6 点意见落实，P0×2 + P1×3 + P2×1）**，只改两份文档（方案文档 + 本文件），零代码改动、零容器、零网络。修订内容：
  ① **来源字段明确（P0）**：`SourceReference` DTO 的 `kbName` 改为 `documentName`（取自 `KnowledgeBaseEntity.originalFilename`，不可被用户修改），获取方式为通过 `Document.getMetadata()` 中的 `kb_id` 反查 `knowledge_bases` 表；新增 `score: Double` 字段（依据：Spring AI 2.0.0 `Document.getScore()` 返回 `Double`，P1-C 测试已实际调用）；删除所有"不含 score"表述。
  ② **调用链重设计（P0）**：SSE 事件协议与实现位置重写——现有调用链中检索结果 `List<Document>` 在 `KnowledgeBaseQueryService.answerQuestionStream()` 内部被消费为纯文本 context（metadata 丢弃），新增 `RetrievalResult` record 契约（`contentStream` + `sourceDocuments`），级联修改返回类型，使 Controller 层可获取检索来源。
  ③ **消息状态区分（P1）**：边界情况处理重写——`rag_chat_messages` 表新增 `status` 字段，4 种完成态（`COMPLETED` / `NO_RESULTS` / `MODEL_FAILED` / `CLIENT_DISCONNECTED`），来源与状态绑定持久化，错误文本区分方案（推荐 `RetrievalResult` 增加 `errorFlag`）。
  ④ **Gate 0c 冒泡入口（P1）**：说明现有 `P1cRealRetrievalEvalTest` 不支持单题运行，需新增冒泡测试方法或系统属性控制（test 作用域新代码）。
  ⑤ **0d/7b 去重（P1）**：删除 Task 7b（与 Gate 0d 操作完全相同），Gate 0d 保持为"执行完整 L1"唯一入口，更新依赖关系图与并行策略。
  ⑥ **Task 7a API 成本（P1）**：将"不涉及付费 API"改为"生产链路 API 冒烟，有外部调用"，估算约 0.01 元。
  ⑦ **链接与基线修正（P2）**：8 个相对链接 `app/src/...` 修复为 `../app/src/...`；顶部基线标注改为"调研基线: `4fd2de0`（方案修订: `5c2426d`）"；前端改动补充 `stream.ts`（onSources 回调 + sources 分支）、`ragChat.ts`（透传 onSources）、`KnowledgeBaseQueryPage.tsx`（Message 接口新增 sources + status）。
  **当前状态：方案第二轮修订完成，待 Codex 复核。**

- 2026-09-30 23:30 — **`docs/devsupport-phase1-plan.md` 定点修订（6 点修订意见落实）**，只改两份文档（方案文档 + 本文件），零代码改动、零容器、零网络。修订内容：
  ① **核实调研基线**：替换所有 `file:///` 链接为仓库相对路径；为“100% 复用”能力表每项附源码依据（类名+方法名）；端点数量改为已核实精确数字（KB 14 + RagChat 8 + KB Interview 10 = 32）；表数量改为已核实 14 张（含 vector_store 由 Spring AI 管理）。
  ② **拆分迁移策略**：Task 1 改为“产品展示层改名”，Phase 1 只做前端 package name / 页面标题 / 导航标题 / 文件头注释；主库名、S3 bucket、容器名前缀、Java 包名、JPA 表名、P1-C 评测实例名全部保留不改；删除“机械替换”“直接 drop 表重建”表述；修正迁移策略：`ddl-auto: validate` + Flyway 已启用，所有 schema 变更必须通过 Flyway 迁移脚本。
  ③ **P1-C 分段门槛**：原单一 Task 0 拆为 4 个独立 Gate（0a 无 API 容器核验 / 0b 离线复核 / 0c 付费冒泡 / 0d 正式 L1），每个有明确前置条件和验收标准，独立记录通过/未通过状态。
  ④ **来源标注增强契约**：为 Task 3 补充详细后端/前端契约——`SourceReference` DTO（kbId/kbName/content，不含 score）、SSE `event: sources` 事件协议、`sources_json TEXT` 列持久化、断流/无结果/多文档边界处理、前端 `Message` 接口扩展。
  ⑤ **最小 DevSupport 数据与交互能力**：新增 §2.4，明确分类字段保持自由文本现状（不新增分类体系）、5 个可验收例子、验收标准。
  ⑥ **前后端禁用方案与验收分离**：前端导航移除面试入口 + 旧路由重定向到首页（不删除页面组件）；后端 Phase 1 不禁用任何端点（保留 32 个端点可用）；Task 7 拆分为 7a（E2E 功能冒烟，不依赖 P1-C）和 7b（P1-C 检索质量评测，独立于 E2E）。
  **当前状态：方案修订完成，待 Codex 复核。**

- 2026-09-30 21:52 — **清单 + 设计文档第三轮定点修订（T9 / R4，基线 `27c0afb`，本轮文档修订提交 `42124ff`）**，只改三份文档（清单 / 设计文档 / 本文件），零代码、零容器、零网络、未运行 `evalP1cReal`、未删任何卷、未启动 Docker Desktop。① **`docker exec` 的 `-T` 全部删除并就地实测**：`docker exec --help` 无 `-T`、`docker exec -T … true` → `unknown shorthand flag: 'T' in -T` + **exit 125**（旗标解析先于 daemon 调用，daemon 停止即可判定），`docker compose exec --help` 有 `-T/--no-tty` → 正文 18 条改为 14 条 `docker exec -i` + 4 条裸 `docker exec`，C4 的 `compose … exec -T` 保持；§1 那两处「今晚再看 CLI 是否收 `-T`」的推导级存疑换成实测事实块，**「不加 `-i` 则 here-doc 到不了容器」仍标注为推理、留给 C5.1 证伪**（不改判据凑过）。若这条没测，今晚**每一条** SQL 都会在解析层 125 死掉。② **C4 时间记录改为窗口**：`UP_START` 在 `up -d` 之前、`UP_END` 在 `ps`/`pg_isready` 之后，分支 A 判据 1 由「晚于单个 up 时刻」（数学上不可能成立，且更晚的记录值能让旧卷意外「通过」）改为 [开始, 结束] 区间核对（含等号），分支 B/B2、§1 口令新鲜度、§11、§12（开始/结束 + 区间核对格）同步。③ **设计文档同步**：§13 第 2 项改 `docker exec -i` + 容器内展开 + here-doc、第 6 项改裸 `docker exec`、表下段落删除宿主机 `read -rs`/`export`/`unset` 教学并补 `-T` 实测注记；扫描 HEAD 另发现 §14「失败时清理规则」的方案 A/B 也旧（缺 `--env-file`、`-T`、`-e` 口令透传、`-c "DELETE …"`）→ 一并改为清单口径（`DELETE` 带 `metadata->>'eval_run_id'` 谓词 + `remaining_for_run_id=` 复核，`down -v` 仍为人工确认后手工动作且要求把确认记进 §12）；§16 新增第 7、8 行。④ **取证工具自身的两处缺陷**（本轮自查发现，非复核意见）：新加的 5 条设计文档断言最初含真围栏标记，会截断核验脚本自己的提取正则 → 断言静默不执行却仍报 exit 0（虚报 34），改 `chr(96)*3` 后真实计数 **39**；`ok()` 原本只 print、**FAIL 不影响退出码**，「重跑 exit=0」从来不是通过判据 → 改为收集失败项并以 exit 1 退出（对照实测：注入假断言 → exit 1；模拟旧形状 → 1 FAIL 仍 exit 0）。验证（零容器、零网络）：§0 断言 **39 / 39 PASS / 0 FAIL / exit 0**；清单 **24 个 bash 块 `bash -n` 0 失败**、设计文档 **1 个块 0 失败**；here-doc `14/14` 与设计侧 `1/1`；形态清点 `docker exec` 18 条 = 14 带 `-i` + 4 裸、带 `-T` **0**；`--env-file` 违例（两文档可复制面）= 无；**反向突变全部非恒真**——恢复旧旗标组合 → 「零 `-T`」报 **18** 条违例（干净 0）、只删 `-i` → 报 **14** 条、设计侧 5 条（回潜 `-T` / 回潜 `-e` 透传 / 加宿主机静默录入 / 加缺 `--env-file` 的 compose / 改掉 here-doc）分别且互不串台地立刻响；写本轮记录时把旧写法原样贴进 §14.1 一行 → 立刻 **38 PASS / 1 FAIL**，改文字描述后回 39/39。C1–C11 **仍全部未实测**（§5 未验证清单不变，仅形态判据由「待今晚核验」升级为「已实测」）。本轮未动的同形态书面残留已在 §2/§5 逐条列出行号待批。本条行首的 `42124ff` 就是本轮文档修订提交本身，由紧随其后的「登记提交号」提交写入（不在同一提交里写死自身哈希：那会被该提交自身作废，见 §6）。

- 2026-09-30 19:53 — **清单第二轮定点修订轮（T8）**，按 Codex 两处意见只改 `P1C-CONTAINER-CHECKLIST.md`（443 → 579 行）与本文件，零代码、零容器、零网络、未改设计文档。① C2 拆成 C2-pre（容器侧）+ **C2-pre-vol**（卷侧，不依赖容器存在），加**四状态判定表**处理「卷在、容器不在」这一常见状态：分支 B 在 `up` 前只做卷侧初判、C2-post 顺延到 `up` 后立即补做并逐字比对 `Mounts` 里的卷名，候选卷 ≥ 2 / 标签不符 / 标签缺失一律**停止且不 `up`**（`up` 会新建第三个卷，而 `down -v` 按项目名删卷）。② 消除宿主机口令泄漏面：删除 `docker exec -e PGPASSWORD="$PGPASSWORD"` 与 §1 的宿主机静默录入，改为容器内 `sh -c 'PGPASSWORD="$EVAL_RUNNER_PASSWORD"; export …; exec psql … -f -'` + 顶格 here-doc，会话开头只跑打印长度的存在性探针，新增两条硬禁令（`.Config.Env`、容器内 `env`/`printenv`）。③ **本轮自查新发现的 P0 形态缺陷**（不在复核意见里）：上面那次改造沿用了 `docker exec -T`，而 `-T` 只关 TTY、**不转发 stdin** —— 缺 `-i` 时 here-doc 到不了容器进程，`psql -f -` 立刻 EOF：**零输出、无报错、退出码 0**，会让整份清单的 SQL 检查**静默假通过**。修法是 14 条读 stdin 的命令统一 `-i -T`、4 条不读 stdin 的保持 `-T`（按规则逐条判断，未做全局替换），§5 末新增强制 **C5.1 形态冒烟自检**（三分支：`stdin_ok` / 零输出即 BLOCKED / `FATAL` 走凭据分支），并补齐 §7 三条负向探针的会话存活行（判据要求 3、上一版只有 1）。验证（全部零容器）：§0 断言 **33 项 / 33 PASS / 0 FAIL / exit 0**；**24 个 bash 块 `bash -n` 0 失败**；here-doc 开闭 **14/14**；5 条 python 过滤器合成 JSON 实跑、哨兵 `SECRET` 零命中；假 `docker` + 假 `psql` 对照干跑（只差 `-i`：带 → 收到 39 字节 SQL 且输出 `stdin_ok`，不带 → 0 字节 / 零输出 / exit 0）；**突变测试证明新断言非恒真**（在内存副本上把 14 条 `-i -T` 改回 `-T` → 该断言报 14 条违例；删一条存活行 → 计数 3→1 触发 FAIL；文件本体未改动，改后重跑仍 33 PASS）。§5 新增 `-i`/`-T` 判据与卷名前缀规则的**推导性质**声明，并记 `P1C-L1-DESIGN.md` §13 仍留旧 `-e` 透传写作为已知遗留（本轮边界内未动）。**（本条里的 `-i -T` / `-T` 均为当时形态，已被 21:52 条目整体作废：真机 `docker exec` 没有 `-T`，那些命令今晚会 exit 125；§13 那条遗留同样已在 21:52 轮清除。）**

- 2026-09-30 18:48 — **离线文档定点修订轮（T7 / 落实 B7）**，收到 Codex 六条意见后只改三份文档（清单 / 设计文档 / 本文件），零代码改动、零容器、零网络。清单侧：C2 卷归属改用 `.Labels` 三方闭环并取消 `config_files` 判据、C8b 三条负向探针拆成独立事务 + 残留复查 + PASS/FAIL/BLOCKED 分类表、C3 限定为 `config --quiet` 与 ports-only 过滤（禁打印渲染配置）、§5 拆新卷/旧卷两命题与分支 A/B 证据、全部 SQL 改容器内 `docker exec -T … psql`（宿主机无 `psql`/`jq`，实测）、口令取值改 `read -rs` + `export`（**该宿主机录入方式已被下一轮整体删除，见 19:53 条目**）、新增 §14/§14.1 与扩展 §11/§12。设计文档侧：移除 4 处固定弱口令字面量、§15 #8 标为已解决、`metadata JSONB` → 交付的 `json`、§2 compose 示例换成实际交付内容、§12 文件清单纠正、§13 命令改为可复制执行形态、§14 方案 B 改按 `eval_run_id` 谓词删除、新增 §16 文档修订小节。验证：清单断言块 25 项重跑 **25 PASS / 0 FAIL（exit 0）**；18 个 bash 块 `bash -n` **0 失败**；3 条 python 过滤器用合成 JSON 实跑且哨兵字段未泄漏；工作区口令字面量 `grep` 命中 0（历史提交未重写，见 §5）。本轮新暴露并修正两处「今晚才会炸」的文档缺陷：`read -rs VAR?提示语` 的 ksh/zsh 语法、C7 里不存在的列 `client_host`。§5 补记命令形态核验的边界与 C2/C8b 判据的推导性质；§4 T4 前置条件改为 5 项（含 Docker daemon 未运行需用户启动）。

- 2026-09-30 17:52 — §6 新增推送安全陷阱：`branch.master.remote=origin` + `push.default` 未设 → 裸跑 `git push` 会推到上游 `Snailclimb/interview-guide`；规则改为写全 `git push devsupport master`，同步判据只认 `devsupport/master...master` 的 `0 0`。
- 2026-09-30 17:48 — 清单交付前自审，修正 4 处会让今晚执行者误判的地方：C3 的 `compose config` 是长格式（不能按 `127.0.0.1:5433:5432` 短语法 grep）、C5 明确「日志是线索、C6 对象存在性才是权威判据」、C6 补齐容器内可执行命令行、C8 说明 `CREATE TABLE` 意外成功时不要自行 DROP、C9 残留判定改用 `starts_with()`（`LIKE '__…'` 里下划线是通配符）。
- 2026-09-30 17:42 — 收到 Codex 对 `f6aa629` 的定点复核：B1–B3 通过、四个归一化哈希独立验算匹配；B1–B4 状态改为 `源码定点复核通过 · 真实环境待验证`。新增 `P1C-CONTAINER-CHECKLIST.md`（C1–C11 无 API 验证清单 + §12 记录表）并做 26 项配置静态核对（全 PASS）。修正 B4 的快照表述（阶段边界 5 次 `snapshot(ctx)`；阶段内异常时报告读 `RunState`，非逐步快照）。新发现 B7：设计文档残留固定弱口令字面量且 §15 #8 陈旧，待批准后清理。本轮无代码改动。
- 2026-09-30 17:12 — 新增本文件（同轮一次自引用修订：交接区改为现场核对 HEAD，避免被自身提交作废）；同步 `f6aa629` 后的真实状态（B1–B4 记为待复核，非已解决），并记录生产检索与 L1 的参数差异、CI 结果未核对、`status.txt` 陈旧。
- 2026-09-30 16:56 — `f6aa629`：三项复核 finding 的离线实现 + 81 条 P1-C 离线用例；设计文档同步至实现口径；推送 `devsupport`。
- 2026-09-30 — `caf05cf`：P1-C 数据与失败契约（62 条离线用例）；Codex 复核指出三项问题（见 §3）。

## 8. Phase 1 编码启动

**时间**: 2026-09-30
**基于提交**: `3dc6e1a`

### 已完成模块

| 任务 | 内容 | 状态 |
|------|------|------|
| Task 1+2 | 前端展示层改名 + 导航清理 | 编译通过 |
| Task 4 | DevSupport Prompt 模板适配 | 编译通过 |
| Task 3 后端 | RetrievalResult 契约 + Flyway 迁移 + 消息状态区分 | 编译通过 |
| Task 3 前端 | stream.ts/ragChat.ts/QueryPage 来源展示 | 编译通过 |
| Task 3 测试 | 13 个新测试（SourceReference + SessionService + QueryService） | 全部通过 |

### 关键改动

- 新增 `RetrievalResult` record 解决来源传递断裂
- 新增 `MessageStatus` 枚举区分 4 种完成态
- Flyway `V20260930` 为 `rag_chat_messages` 增列 `sources_json` + `status`
- SSE 新增 `event: sources` + `event: done`
- 前端 `stream.ts` 支持 `onSources`/`onDone` 事件分发
- 错误处理从字符串前缀改为 `Flux.error()` 传播

### 未完成

- Gate 0a-0d（P1-C 真实环境门槛）
- Task 7a（E2E 功能冒烟）
- 代码审查与 diff 复核

## 9. Phase 1 流式最终状态与事件顺序定点修复

**时间**: 2026-10-01（北京时间）
**基线 HEAD**: `67baebd`（修复前）
**范围**: 只修 Phase 1 流式回答（`RagChatController.sendMessageStream`）的服务端最终状态与事件顺序，不做无关重构，不改生产数据。

### 根因（修复前实为）

- 事件顺序：`data…` → `sources` → `done` → 流整体完成后 `doOnComplete` 才落库。**done（成功信号）先于持久化发给客户端**，落库失败无法撤回 done。
- 最终状态：`status` 在请求时按 `sourceDocuments().isEmpty()` 预先算定，**不看模型实际输出**——检索命中但输出被归一化成「未检索到相关信息」拒答时，仍存成有依据的 COMPLETED 且带 sources。
- 终止写入：`doOnError` / `doOnCancel` 与「落库抛异常再触发 doOnError」之间无护栏，可能重复写入 / 互相覆盖。

### 修复

- `KnowledgeBaseQueryService` 新增 `resolveFinalStatus(实际输出, 检索文档)`：空检索→NO_RESULTS；命中但输出为空或命中 `isNoResultLike` 拒答→NO_RESULTS；命中且实质回答→COMPLETED。判定收敛到 Service 层便于单测。
- `RagChatController.sendMessageStream` 链重构为：`data…` →（`concatWith(Flux.defer(...))` 内先 `resolveFinalStatus` → **先落库** → 成功才发 `sources` + `done`）；落库失败返回 `Flux.error(BusinessException)`、**不发 done**；`AtomicBoolean finalized` 单次护栏协调成功 / 错误(MODEL_FAILED) / 取消(CLIENT_DISCONNECTED) 三条终止路径，杜绝重复写与覆盖。NO_RESULTS 一律以 `[]` 存来源（有文档却拒答也不呈现为有依据）。
- 保留现有 SSE `sources` 契约；`done` 事件 data 由空改为 `{"status":"<COMPLETED|NO_RESULTS>"}`，供下一项前端任务区分最终态（前端 `stream.ts` 的 done 分支忽略 data，不破坏现有行为）。

### 测试

- 新增 `RagChatControllerTest`（5 条）：成功顺序 `data,data,sources,done`；持久化失败→终止于 error、不发 done、仅写一次；有文档却拒答→按 NO_RESULTS 且 sources=`[]`、done 带 NO_RESULTS；内容流出错→MODEL_FAILED 仅写一次且不调 resolveFinalStatus；取消→CLIENT_DISCONNECTED 仅写一次。
- `KnowledgeBaseQueryServiceTest` 新增 `resolveFinalStatus` 4 条（空检索 / 实质回答 / 拒答文本 / 空输出）。
- 验证命令（PowerShell，`$env:GRADLE_USER_HOME="C:\temp\gradle-tmp"`）：
  - `./gradlew :app:compileJava :app:compileTestJava --no-daemon` → exit 0
  - `./gradlew :app:test --tests 'interview.guide.modules.knowledgebase.RagChatControllerTest' --tests 'interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryServiceTest' --no-daemon --rerun` → exit 0；结果 XML：RagChatControllerTest 5（成功路径 2 + 外层 3）、resolveFinalStatus 4，均 0 失败 / 0 错误。

### 尚未验证的真实行为（不得写成已验证）

- 真实 SSE 端到端顺序与落库：未连真实 LLM / 数据库跑过一次，测试均为 Mockito + `StepVerifier`/`collectList` 的单元级验证；`completeStreamMessage` 的事务回滚在真实 DB 下的表现未实测。
- 「检索命中但模型输出恰为拒答模板」依赖 `resolveFinalStatus` 的关键词判定，真实模型输出的多样措辞是否都能落入 `isNoResultLike`，未经真实样本验证。
- 在 Reactor 链中同步执行阻塞式 JDBC 落库（`@Transactional`）仍是既有形态，本轮未改线程模型。
- 前端据 done 的新 `status` 字段区分展示属下一项任务，本文件只保证后端已带出该字段。

## 10. Phase 1 流式回答前端状态处理定点修复

**时间**: 2026-10-01（北京时间）
**基线 HEAD**: `b85890f`（本轮修复前）
**范围**: 只修前端流式回答（RAG Chat）的最终状态处理与来源展示，使其消费 §9 后端已带出的 `done.status` 契约；不改后端、不调外部 API / 数据库、不动无关页面。

### 消费的后端契约（§9 已确定）

- `done` 事件 data 为 `{"status":"<COMPLETED|NO_RESULTS|MODEL_FAILED|CLIENT_DISCONNECTED>"}`；仅成功落库后才发 `done`，落库失败 / 模型错误以 SSE `error` 事件或无 `done` 的流终止收场。
- `sources` 事件在 `done` 之前发出；NO_RESULTS 时后端以 `[]` 落库、前端也应视为无依据。

### 根因（修复前）

- `stream.ts` 的 `done` 分支只调 `onDone?.()`、丢弃 data；页面在 `onDone` 里**无条件写 `status:'COMPLETED'`**、`sources:currentSources`，不看服务端确认状态。
- 「无 `done` 确认」与「`onError`」都未收敛为失败态；有文档但拒答（NO_RESULTS）时仍会带来源呈现为有依据的回答。
- 刷新恢复走 `getSessionDetail`，历史上未读取持久化的 `status` / `sources_json`，展示与实际落库不一致。

### 修复

- 新增纯逻辑模块 `frontend/src/api/ragStreamStatus.ts`：`parseDoneStatus`（解析 done data，缺失/非法/未知→`undefined`）、`resolveFinalStatus`（无确认回退 `MODEL_FAILED`，绝不当 COMPLETED）、`sourcesDisplayMode`（COMPLETED→grounded / NO_RESULTS→none / MODEL_FAILED·CLIENT_DISCONNECTED→degraded / 生成中→pending）、`selectSourcesForStatus`（NO_RESULTS 一律清空来源）。
- `stream.ts`：`onDone?: (status?: string) => void`，`done` 分支把 data 透传给 `onDone`。
- `ragChat.ts`：`MessageStatus` 收敛到 `ragStreamStatus` 再导出；`sendMessageStream` 用 `finalized` 单次护栏协调 `onDone`（解析服务端状态）/ `onComplete`（流结束却无 done→`MODEL_FAILED`）/ `onError`，`onComplete` 回传最终状态。
- `KnowledgeBaseQueryPage.tsx`：完成回调按服务端状态写定、用 `selectSourcesForStatus` 决定来源；渲染用 `sourcesDisplayMode` 门控——仅 grounded 作为「引用来源」，degraded 降级为「参考文档（本条回答未成功生成，仅供参考）」，none/pending 不渲染；非 COMPLETED 显示状态徽标（未找到相关文档 / 回答生成失败 / 回答中断）。刷新恢复沿用 `m.status` + `m.sourcesJson` 经同一渲染管线呈现。

### 测试

- 新增 `frontend/src/api/ragStreamStatus.test.ts`（5 条，`node --test`）：四种状态解析；缺失/非法/未知→`undefined`；无确认回退 `MODEL_FAILED`；`sourcesDisplayMode` 各态；`selectSourcesForStatus` NO_RESULTS 清空。
- `package.json` 新增脚本 `test:rag-stream`；`.github/workflows/ci.yml` frontend 任务加入 `pnpm run test:rag-stream`。
- 验证命令（PowerShell，`frontend/` 下）：
  - `node --test src/api/ragStreamStatus.test.ts` → 5 pass / 0 fail
  - `pnpm run build`（`tsc && vite build`）→ exit 0（仅既有的 CSS `:where()` 语法告警与 chunk 体积告警，与本次改动无关）

### 尚未验证的真实行为（不得写成已验证）

- 未连真实后端跑过一次端到端 SSE：真实网络下 done/error/断流的实际时序、以及 `onComplete` 无 done 回退路径仅由单元级纯函数与代码走查保证。
- 刷新恢复依赖后端确已持久化 `status` / `sources_json`（§9 后端侧形态），本轮未对真实 DB 落库结果做端到端核对。
- degraded 展示假定 MODEL_FAILED / CLIENT_DISCONNECTED 时后端可能保留检索到的文档来源；真实拒答措辞能否稳定落入 NO_RESULTS（`isNoResultLike`）仍属后端侧未验证项。

## 11. KnowledgeBaseController 流式错误兜底语义定点恢复

**时间**: 2026-10-01（北京时间）
**基线 HEAD**: `376ef5a`（本轮修复前）
**范围**: 只恢复 `KnowledgeBaseController.queryKnowledgeBaseStream()`（`POST /api/knowledgebase/query/stream`，纯文本 `Flux<String>`）原有的流式错误兜底语义。不改共享的 `KnowledgeBaseQueryService.answerQuestionStream()`，不改 RagChatController 的 SSE 协议。

### Phase 1 前后的错误处理对比

- **Phase 1 前**：`answerQuestionStream` 直接返回 `Flux<String>`，两处错误都降级为显式文本：同步 catch → `Flux.just("【错误】知识库查询失败：" + e.getMessage())`；异步 onErrorResume → `Flux.just("【错误】知识库查询失败：AI服务暂时不可用，请稍后重试。")`。前端拿到含【错误】的可读文本。
- **Phase 1（`da1c744`）**：为支持 RagChatController “失败不宣告成功 / MODEL_FAILED”，共享 Service 两处都改为 `Flux.error(e)`，Controller 端点变成 `return queryService.answerQuestionStream(...).contentStream()` 且无任何错误处理。
- **回归表现**：该纯文本端点把错误裸传播；前端 `streamSse`（line 模式）看到流被 abrupt 终止后触发 `onComplete`，将“被中断的部分回答”当作正常完成——丢失了可读的【错误】兜底，也变相把失败伪装成正常回答。

### 修复（仅端点，服务与 RagChat 不动）

- `queryKnowledgeBaseStream` 内恢复兜底，明确区分两类错误：
  - **(A) 建立 RetrievalResult 时同步抛错**：用 try/catch 包住 `answerQuestionStream(...).contentStream()`；抛出时返回 `Flux.just(STREAM_ERROR_PREFIX + 真实原因)`。
  - **(B) Flux 订阅后异步出错**：`.doOnNext` 标记 `emitted`，`.onErrorResume` 按是否已输出内容分流：尚未输出→整串 `【错误】知识库查询失败：` + 真实原因；已输出部分→追加固定标记 `\n\n【错误】知识库查询失败：AI服务暂时不可用，请稍后重试。`，避免残缺回答被伪装成正常回答。
- 新增常量 `STREAM_ERROR_PREFIX` / `STREAM_UNAVAILABLE_FALLBACK` 与私有 `resolveErrorReason(Throwable)`（message 为空时回退不可用文案）。失败永远带【错误】标记，不回退为 NO_RESULT 正常文案。

### 测试

- 新增 `KnowledgeBaseControllerStreamTest`（7 条，JUnit5 + Mockito + StepVerifier/collectList）：正常透传；(A) 同步抛 BusinessException 带真实原因、无 message 回退；(B) 未输出即失败→整串【错误】、已输出部分后中断→保留部分+追加标记且正常 complete、失败不伪装成 NO_RESULT；固定无结果文案不受影响。
- 验证命令（PowerShell，`$env:GRADLE_USER_HOME="C:\temp\gradle-tmp"`）：
  - `./gradlew :app:compileJava :app:compileTestJava --no-daemon` → exit 0
  - `./gradlew :app:test --tests '...KnowledgeBaseControllerStreamTest' --tests '...RagChatControllerTest' --tests '...KnowledgeBaseQueryServiceTest' --no-daemon --rerun` → exit 0（新类 7：外层 2 + 同步 2 + 异步 3；RagChatControllerTest / QueryServiceTest 无回归）

### 尚未验证的真实行为（不得写成已验证）

- 未连真实后端跑过一次端到端 SSE：真实网络上 Flux.error 与中断的实际时序、Spring WebFlux/SSE 对错误终止的底层行为未实测。
- “同步抛错”分支在当前服务形态下几乎不会触发（Service 内部已 catch 并包为 `Flux.error`）；try/catch 为防御，主要真实路径是 `(B)` 中 `emitted=false` 分支。

## 12. RagChatController 来源组装责任下沉到 Service

**时间**: 2026-10-01（北京时间）
**基线 HEAD**: `bab3a62`（本轮前）
**范围**: 只把 `buildSourceReferences` 的数据库查询与来源组装从 `KnowledgeBaseQueryService` 移入 `RagChatSessionService`，让 `RagChatController` 不再直接持有 `KnowledgeBaseRepository`。保持来源字段、排序、SSE 序列化、状态持久化与现有接口兼容；不改数据库 schema、不动其他端点。

### 为何 Controller 之前直接注入 Repository

- `buildSourceReferences(List<Document> docs, KnowledgeBaseRepository kbRepo)` 原本住在 `KnowledgeBaseQueryService`，但需要 Repository 才能由 `kb_id` 反查 `KnowledgeBaseEntity.originalFilename` 作为 `SourceReference.documentName`。Service 自己并未持有 Repository，于是 Controller 仅为把这个 Repository 参数传进去而直接注入了 `KnowledgeBaseRepository`——违反 Controller 不碰数据访问的分层职责。
- `SourceReference.documentName` 的来源：从每个检索 `Document` 的 metadata 取 `kb_id`，批量 `findAllById` 查 `knowledge_bases` 表，取 `originalFilename`（原始文件名，不可被用户修改）；命中不到时回退“未知文档”。

### 修复（仅换调用方与归属）

- `RagChatSessionService` 新增 `buildSourceReferences(List<Document> docs)`，复用自身已有的 `knowledgeBaseRepository` 字段；逻辑、顺序、截断（200 + `...`）、未知回退与原实现逐字一致。
- `KnowledgeBaseQueryService` 删除该方法及不再使用的 `KnowledgeBaseEntity`/`SourceReference`/`KnowledgeBaseRepository` import（`Set`/`Map`/`HashMap`/`Collectors` 仍被其他方法使用，保留）。
- `RagChatController` 移除 `KnowledgeBaseRepository` 字段与 import，改调 `sessionService.buildSourceReferences(result.sourceDocuments())`；`resolveFinalStatus` 仍走 `queryService`，SSE 事件顺序、来源 JSON 序列化、完成/错误/取消三路持久化均未变。

### 测试

- `RagChatControllerTest`：构造函数改为三参（去掉 repository mock），五处来源打桩改为 `sessionService.buildSourceReferences(anyList())`。
- 来源提取用例从 `KnowledgeBaseQueryServiceTest` 迁到 `RagChatSessionServiceTest`（新增 4 条：顺序与截断 / 未知 kb 回退 / 空列表 / null）；`KnowledgeBaseQueryServiceTest` 删除旧来源用例及无用 import。
- 验证命令（PowerShell，`$env:GRADLE_USER_HOME="C:\temp\gradle-tmp"`）：
  - `./gradlew :app:compileJava :app:compileTestJava --no-daemon` → exit 0
  - `./gradlew :app:test --tests '...RagChatControllerTest' --tests '...RagChatSessionServiceTest' --tests '...KnowledgeBaseQueryServiceTest' --tests '...SourceReferenceTest' --no-daemon --rerun` → exit 0（四套件 failures=0 errors=0）

### 尚未验证的真实行为（不得写成已验证）

- 纯重构，未连真实 LLM / 数据库跑端到端 SSE；来源组装仅由单元测试与 Controller 打桩覆盖，真实 `findAllById` 命中 / 缺行路径未做集成验证。
- `buildSourceReferences` 在 `RagChatSessionService` 中不开事务（与原先在 QueryService 中时一致）；只依赖一次批量 `findAllById`，未新增循环查库。

## 13. Codex 复核后的 P1/P2 定点修复与事件顺序测试补强

**时间**: 2026-10-01（北京时间）
**基线 HEAD**: `1c3d4c7`（§12；Codex 对 master 的独立复核结论）
**范围**: 两处定点修复 + 一项测试补强。保持第 3 轮纯文本端点兜底与第 4 轮来源下沉的现有分层与兼容契约；不改 schema、不做无关重构、不让 Controller 重新注入 Repository；维持 `data… → 持久化成功 → sources → done(status)` 事件契约与来源字段/顺序/score/截断/未知回退语义。

### P1：有效长回答被误判为 NO_RESULTS（后端）

- 根因：`KnowledgeBaseQueryService.resolveFinalStatus` 复用 `isNoResultLike`（`contains` 全文匹配），只要整段正文任意位置出现“信息不足”等歧义词，即判为 NO_RESULTS 并清空来源，导致正常长回答被误伤。
- 修复：新增 `isExplicitRefusal`，只认两类“整段式明确拒答”——① 正文等于/起始于固定无结果模板 `NO_RESULT_RESPONSE`；② 第一句（首个句子终止符之前）本身包含 `STRONG_REFUSAL_MARKERS`。`resolveFinalStatus` 由 `isNoResultLike` 改判 `isExplicitRefusal`。判定基于结构位置，既非“全文任意位置含关键词”，也非靠长度放行。
- 共享路径不动：`isNoResultLike`、探测窗口 `normalizeStreamOutput`（`STREAM_PROBE_CHARS=120`）与 `answerQuestionStream` 保持不变——真正的开头即无信息仍由上游探测窗口收敛为固定模板，`STRONG_REFUSAL_MARKERS` 刻意不含“信息不足”。
- 反例（修复前→后）：前段正常说明、后文“排查时如果日志信息不足……实际解决方法是将服务端口改为8080。” → 修复前判 NO_RESULTS（2 条新用例 FAIL，failures=2），修复后判 COMPLETED。

### P2：SSE 解析器在 CRLF 跨网络块时丢失 done（前端）

- 根因：一个 `\r\n` 被拆成“…\r” + “\n…” 两块时，`flushEventBuffer` 先把孤立的尾部 `\r` 归一成 `\n`，与下一块开头的 `\n` 拼成假空行分隔符，使 `event:` 头与其 `data:` 被拆成两个块，`done` 丢失、状态 JSON 混入正文、上层无 done 误回退 MODEL_FAILED。
- 修复：`!done` 且缓冲区以孤立 `\r` 结尾时先扣留该 `\r`（`heldCR`），处理完再接回尾部交由下一块配对；`done==true` 时不扣留。line/event 两种模式与所有调用方语义等价（去掉 `!done` 分支里的提前 return、`done && remaining.trim()` 改 `remaining.trim()` 仅在 done 路径生效）。
- 反例（修复前→后）：块 `["event:done\r", "\ndata:{\"status\":\"COMPLETED\"}\r\n\r\n"]` → 修复前 done 丢失（用例 FAIL），修复后 done 恰好触发一次、状态 JSON 不混入正文、ragChat 不误回退。

### 测试补强（事件顺序）

- 后端新增 `persistenceVerifiedAtMomentSourcesAndDoneArrive`：用 StepVerifier 在收到 `sources` 事件的当下即 `verify(sessionService).completeStreamMessage(...)` 并用 ArgumentCaptor 断言有效回答保留真实来源（含 `README.md`，非空数组），收到 `done` 的当下再次确认持久化已完成——直接证明“持久化先于事件发出”，而非 `collectList` 之后再 verify。保留原有：持久化失败不发 sources/done 且只写一次、模型错误按 MODEL_FAILED 只写一次不发 done、取消按 CLIENT_DISCONNECTED 只写一次。
- 前端新增 `frontend/src/api/stream.test.ts`：走真实 `streamSse` 解析链路（TextDecoder + flushBuffer + processEventBlock，mock fetch 逐块喂 Uint8Array），覆盖跨块 CRLF 拆分、LF、CRLF、event/data/分隔符全拆块、UTF-8 多字节拆块、无 done 回退、line 模式兼容，以及 ragChat 端到端 `finalized` 单次护栏（COMPLETED 仅一次 / 无 done→MODEL_FAILED / 跨块 done 不误回退）。为此把 `request.ts` 的 axios 类型改为 `import type`、`import.meta.env?.` 可选链，`stream.ts`/`ragChat.ts` 内部 import 加 `.ts` 扩展（`allowImportingTsExtensions` 已开，Vite 与 node 均可解析）；`package.json` 注册 `test:sse-stream` 并加入 CI frontend 任务。

### 验证（离线通过，代码已写 + 本地/离线测试通过）

- `./gradlew :app:compileJava :app:compileTestJava --no-daemon` → BUILD SUCCESSFUL（exit 0）。
- `./gradlew :app:test --tests '...KnowledgeBaseQueryServiceTest' --tests '...RagChatControllerTest' --no-daemon` → BUILD SUCCESSFUL；resolveFinalStatus 套件 tests=7 failures=0，成功路径套件 tests=3 failures=0。P1 反例：临时还原为 `isNoResultLike` 后该 2 条用例 FAIL（failures=2），改回 `isExplicitRefusal` 后转绿。
- `./gradlew :app:test --no-daemon`（全量）→ BUILD SUCCESSFUL（约 2m24s，无 FAILED）。
- `node --test src/api/stream.test.ts` → pass 10 fail 0；P2 反例：临时把扣留条件置 `false` 后跨块用例 FAIL，改回后转绿。既有前端测试合计 pass 18 fail 0。
- `cd frontend && pnpm run build` → 构建成功（tsc 通过，仅历史 CSS `:where()` 语法告警，非本次引入）。

### 尚未验证的真实行为（不得写成已验证）

- 未连真实 LLM / 付费 Embedding / 真实 L1 / 生产数据库跑端到端 SSE；真实网络上 `\r\n` 跨 TCP 段的实际拆分时机、Spring WebFlux SSE 底层分帧行为未实测。
- `isExplicitRefusal` 的强拒答词表基于现有模板与常见拒答构造归纳，真实模型输出的多样拒答措辞覆盖面未经线上样本验证。

## 14. P1 定点修复：收窄最终拒答判定（修复 1ba2a57 确定性回归）

**时间**: 2026-10-01（北京时间）
**基线 HEAD**: `1ba2a57`（§13）
**状态**: 代码已写 + 本地/离线测试通过；**待 Codex 复核**（未提前写成复核通过）。
**范围**: 仅改最终拒答判定（`KnowledgeBaseQueryService.isExplicitRefusal`）与相关后端测试、进度文档。前端/P2、共享 `answerQuestionStream`、`isNoResultLike`、探测窗口 `normalizeStreamOutput`、SSE 协议、事务范围、schema、来源组装均不动；Controller 未重新注入 Repository。

### 回归根因
- `1ba2a57` 的 `isExplicitRefusal` 仍基于“起始句 `contains` STRONG_REFUSAL_MARKERS”，其中“找不到”“知识库中未”等宽泛子串会误伤正常故障排查答案。Codex 真实判定：以下两条旧版非拒答、`1ba2a57` 却误判为拒答（有检索文档时应为 COMPLETED，却变 NO_RESULTS 并清空来源）：
  1. “找不到配置文件时，请先检查工作目录及挂载路径，并将配置放在应用指定的位置，随后重新启动服务。”
  2. “知识库中未配置索引版本字段，需要先添加该字段并重新构建向量索引。”

### 修复（只将“无法依据资料回答当前问题”的整句式拒答判为拒答）
- 删除 `STRONG_REFUSAL_MARKERS` 宽泛子串列表，改为三个构造级正则常量：
  - `REFUSAL_INABILITY`：同一分句内“无法/不能/难以/没法 …(≤12字)… 回答/作答/给出答案/给出答复”；
  - `REFUSAL_EMPTY_RETRIEVAL`：“未/没有/未能/没能 …(≤8字)… 检索|找到|发现|查到|命中 …(≤8字)… (相关)?(信息|内容|资料|结果|依据)”——对象必须是“信息类”，因此不匹配“找不到配置文件”“知识库中未配置……字段”；
  - `REFUSAL_NEGATION`：“并非/不是/并未/绝非 …(≤4字)… 无法/不能/没法”——出现时视为正常作答。
- `isExplicitRefusal` 保留固定模板 `NO_RESULT_RESPONSE` 的 equals/startsWith 判定；非模板部分只看起始句，先过否定护栏，再依次匹配两个拒答构造。判定基于具体句式搭配 + 结构位置，不靠宽泛子串、不靠长度放行。

### 保留的局限（规则边界）
- 拒答语句若只出现在第一句之后（如“抱歉。未检索到相关信息。”）可能被当作 COMPLETED；若一段有效回答的**起始句**本身肯定式地断言“无法…回答”/“未检索到…信息”仍可能误判。选择“起始句 + 构造级正则”是在不引入额外 LLM 调用/架构改造前提下的平衡。
  > 更正（Codex 三次复核）：本条旧表述“多为条件/引用/否定句，已由否定护栏覆盖”**并不属实**——`7801762` 的 `REFUSAL_NEGATION` 对整句一刀切，条件句、引用文本与否定作用范围仍有确定性误判（详见 §15，已于本轮修复）。

### 测试
- 先写两条回归用例（上面反例）→ 对 `1ba2a57` 实现确认 FAIL（failures=2，行 252/261），修复后转绿。
- `KnowledgeBaseQueryServiceTest.ResolveFinalStatusTests` 补全反例矩阵：条件句/引用错误文本/否定式拒答/信息不足描述句均 COMPLETED；固定模板与非模板明确拒答（无法根据现有资料回答 / 没有找到相关信息）均 NO_RESULTS；无文档/空输出保持 NO_RESULTS。
- `RagChatControllerTest` 新增 `normalAnswerWithRealStatusJudgingPersistsCompletedWithSources`：**不 mock 最终状态**，用真实 `KnowledgeBaseQueryService.resolveFinalStatus` 判定反例 1，断言落库 COMPLETED 且保存/发送的 sources 非空（含 README.md，不为 `[]`）。

### 验证（均为离线/本地，非真实环境；PowerShell 直取 `$LASTEXITCODE`，已消除管道对 stderr 的 NativeCommandError 干扰）
- `./gradlew :app:compileJava :app:compileTestJava --no-daemon` → GRADLE_EXIT=0。
- `./gradlew :app:test --tests '...KnowledgeBaseQueryServiceTest' --tests '...RagChatControllerTest' --no-daemon` → BUILD SUCCESSFUL；resolveFinalStatus 套件 tests=14/failures=0/errors=0/skipped=0，成功路径套件 tests=4/failures=0，顶层套件 tests=3/failures=0。
- `./gradlew :app:test --no-daemon`（全量）→ GRADLE_EXIT=0，BUILD SUCCESSFUL，无 FAILED。
- `git diff --check` → exit 0（无空白错误）。
- 关于退出码：之前个别命令显示 exit 1 是因为 `2>&1 | Select-String` 将 Gradle 写入 stderr 的进度行当作 NativeCommandError；改用 `*>` 重定向到文件后 `$LASTEXITCODE` 真实为 0，已据此排查确认而非单纯归因于 stderr 告警。

### 尚未验证的真实行为（不得写成已验证）
- 未连真实 LLM / 付费 Embedding / 真实 L1 / 生产数据库跑端到端 SSE；真实模型输出的多样拒答措辞与正则覆盖面未经线上样本验证。
- 当前状态为“待 Codex 复核”，不得提前写成复核通过。

## 15. P1 定点修复：条件 / 引用 / 否定的作用范围限定（修复 7801762 确定性回归）

**时间**: 2026-10-01（北京时间）
**基线 HEAD**: `7801762`（§14）
**状态**: 代码已写 + 本地/离线测试通过；**待 Codex 四次复核**（未提前写成复核通过）。
**范围**: 仅改最终拒答判定（`KnowledgeBaseQueryService.isExplicitRefusal` 及其私有辅助）与相关后端测试、进度文档。前端/P2、共享 `answerQuestionStream`、`isNoResultLike`、探测窗口 `normalizeStreamOutput`、SSE 协议、事务、schema、来源组装、已通过的事件顺序补强均不动；Controller 未重新注入 Repository。

### 回归根因（Codex 三次复核，7801762 确定性错误）
§14 的 `isExplicitRefusal` 仍对整句一刀切：`REFUSAL_INABILITY.find()` 会命中条件句、引用文本里的“无法回答”；`REFUSAL_NEGATION` 命中即对整句返回正常，会把“并非无法连接”错误外溢到后面的真实拒答。三条真实判定反例（有检索文档时，均不被探测窗口拦截，错误发生在最终状态判定）：
1. “如果模型无法回答，请先检查 API Key 和超时配置，然后重新发起请求。” 期望 COMPLETED，`7801762` 判 NO_RESULTS。
2. “日志出现‘无法回答’时，请检查模型连接配置并重启服务。” 期望 COMPLETED，`7801762` 判 NO_RESULTS。
3. “并非无法连接服务，但无法根据现有资料回答您的问题，请补充文档。” 期望 NO_RESULTS，`7801762` 判 COMPLETED。

### 最小方案（把条件 / 引用 / 否定各自限定到对应作用范围再判定）
- 删除整句一刀切的 `REFUSAL_NEGATION`，仍只看起始句，但改为分作用域判定：
  - `QUOTED_SPAN`：判定前先把成对引号（U+2018/2019、U+201C/201D、ASCII "、「」、『』）内的引用/提及文本剔除，“日志出现‘无法回答’”不再被当作当前拒答。
  - `CLAUSE_SPLIT`：以子句（，,、；;：:。 等）为作用域逐句判断，条件/否定只在所在子句内生效。
  - `NEGATION_BEFORE_MODAL`：仅当否定词紧邻拒答情态词（“并非无法回答”）时取消该处拒答，不外溢到同句后面的真实拒答。
  - `CONDITIONAL_CONNECTIVE`：仅当条件连接词（如果/假如/倘若/若/要是/万一/一旦/设若）位于拒答之前且同属一个子句时，该拒答视为假设。
  - 逐子句遍历 `REFUSAL_INABILITY` / `REFUSAL_EMPTY_RETRIEVAL` 的每一处匹配，只要存在一处“未被就地否定、非同子句条件假设、不在引用内”的拒答构造即判为拒答；固定模板 equals/startsWith、空输出 / 无文档仍走原路径。
- 未采用“整句含如果就放行”“整句含引号就放行”“整句含否定词就放行”的一刀切，也未针对具体字符串打补丁。

### 保留的局限（规则边界）
- 否定词与拒答情态词被较多文字隔开（“这并非意味着我们无法回答”）、或条件标记出现在拒答之后，仍可能被就地判定，属已知边界；未引入额外 LLM 调用 / 大型解析框架 / 架构改造。
- 有效回答起始句肯定式断言“无法…回答”仍会被判拒答（本就是拒答语义）；真实线上多样措辞未经样本验证。

### 测试
- 先加三条失败用例（反例 A/B/C，`KnowledgeBaseQueryServiceTest` 行 315/324/333）→ 对 `7801762` 确认 FAIL（31 tests completed, 3 failed，GRADLE_EXIT=1），修复后转绿。
- 对偶用例（同批新增，与三条反例成对讲清作用范围）：
  - “并非无法回答，解决方法是将端口改为8080。” → COMPLETED（否定拒答本身）。
  - “日志出现‘无法回答’，但根据现有资料仍无法回答您的问题。” → NO_RESULTS（引用提及后仍有真实拒答）。
  - “如果需要详细步骤，请补充日志；目前无法根据现有资料回答您的问题。” → NO_RESULTS（条件子句与独立真实拒答并存，不整句放行）。
- 保留 §13/§14 全部回归：固定模板 / 无文档 / 空输出 = NO_RESULTS；正常排查答案 = COMPLETED。
- `RagChatControllerTest` 新增 `realRefusalWithRealStatusJudgingClearsSources`：仍不 mock 最终状态，用真实 `resolveFinalStatus` 判定明确非模板拒答，断言落库 NO_RESULTS 且 sources 清空为 `[]`、发送的 sources 事件为 `[]`、done 含 NO_RESULTS；与 §14 的 `normalAnswerWithRealStatusJudgingPersistsCompletedWithSources`（正常回答保留来源）形成“保留来源 / 清空来源”配对。

### 验证（均为离线/本地，非真实环境；PowerShell 直取 `$LASTEXITCODE`）
- `./gradlew :app:compileJava :app:compileTestJava --no-daemon` → COMPILE_EXIT=0。
- 定点 `:app:test --tests '...KnowledgeBaseQueryServiceTest' --tests '...RagChatControllerTest'` → GRADLE_EXIT=0，BUILD SUCCESSFUL；resolveFinalStatus 套件 tests=20/failures=0/errors=0/skipped=0，成功路径套件 tests=5/failures=0，answerQuestionStream 契约 tests=3/failures=0，顶层控制器 tests=3/failures=0。
- 全量 `:app:test --no-daemon` → GRADLE_EXIT=0，BUILD SUCCESSFUL，无 FAILED。
- `git diff --check` → exit 0。

### 尚未验证的真实行为（不得写成已验证）
- 未连真实 LLM / 付费 Embedding / 真实 L1 / 生产库跑端到端 SSE；真实模型多样拒答措辞、引用/条件/否定的复杂嵌套未经线上样本验证。
- 当前状态为“待 Codex 四次复核”，不得提前写成复核通过。
  > 更正（Codex 四次复核）：`8d6f367` 仍把引用屏蔽放在句子切分之后、QUOTED_SPAN 不含 ASCII 单引号、且不支持跨子句的“如果…，…时请…”条件——以下三例当时仍为确定性误判（详见 §16，已于本轮修复），故 §15 不得写成“引用/条件已完备解决”。

## 16. P1 定点修复：引用屏蔽前置 / ASCII 单引号 / “……时请……”条件作用域（修复 8d6f367 确定性回归）

**时间**: 2026-10-01（北京时间）
**基线 HEAD**: `8d6f367`（§15）
**状态**: 代码已写 + 本地/离线测试通过；**待 Codex 五次复核**（未提前写成复核通过）。
**范围**: 仅改最终拒答判定（`KnowledgeBaseQueryService.isExplicitRefusal` 及其私有辅助）与相关后端测试、进度文档。前端/P2、共享 `answerQuestionStream`、`isNoResultLike`、探测窗口 `normalizeStreamOutput`、SSE 协议、事务、schema、来源组装、事件顺序补强均不动；Controller 未重新注入 Repository。

### 回归根因（Codex 四次复核，8d6f367 确定性错误）
`8d6f367` 的判定：引用屏蔽在句子切分之后才做、QUOTED_SPAN 不含 ASCII 单引号、且不识别跨子句的“……时请……”条件排查。三条真实反例（有检索文档时，均不被探测窗口拦截）：
1. “日志出现'无法回答'时，请检查模型连接配置并重启服务。”（ASCII 单引号，未被屏蔽） 期望 COMPLETED，`8d6f367` 判 NO_RESULTS。
2. “日志出现“无法回答。”时，请检查模型连接配置并重启服务。”（句号在引用内部，被句子切分提前截断） 期望 COMPLETED，`8d6f367` 判 NO_RESULTS。
3. “如果服务启动失败，模型无法回答时请检查 API Key 并重试。”（条件跨子句，“如果”与拒答不在同一子句） 期望 COMPLETED，`8d6f367` 判 NO_RESULTS。

### 最小方案
- **引用屏蔽前置 + 占位边界**：`maskQuotedSpans` 在任何句子/子句切分之前，用占位符 `□`（`QUOTE_MASK`，已加入 `CLAUSE_SPLIT` 作为子句边界）替换整段引用。引用内的句号/换行不参与截断；引用两侧因占位符断开，不会拼接出原文不存在的“无法…回答”。未闭合引号不匹配（不贪婪吞到结尾）。
- **QUOTED_SPAN 扩充**：新增 ASCII 单引号 `'[^'\n\r]*'`（不跨换行以降低撇号误配），ASCII 双引号改为允许跨换行 `"[^"]*"`；中文弯单/双、直角引号不变。
- **“……时请……”条件排查**：`isConditionalTroubleshooting`——当某处拒答之后紧邻 `时` 且同子句内出现请求/指令词（`CONDITIONAL_REQUEST_MARKER`）时，该拒答是条件假设。作用域只到这一处拒答后的紧邻 `时`，不因整句含“时/请”就整体放行。
- 固定模板 equals/startsWith、空输出 / 无文档仍走原路径；否定/同子句条件逻辑与 §15 一致。

### 保留的局限（规则边界）
- 若“如果…”条件与拒答跨子句、且拒答后无紧邻 `时`（如“如果服务启动失败，模型无法回答。请重试。”）——此形式条件仍可能被判为拒答；本轮只做“紧邻时+同子句请求词”的保守识别，未做跨子句条件传播（避免误放真实拒答）。
- ASCII 单引号靠成对匹配，若英文撇号（don't / it's）与中文拒答同句且恰好成对，可能误屏蔽；已用“不跨换行”降低面。未闭合/多行引用不处理。
- 否定词与情态词被较多文字隔开、或条件标记出现在拒答之后，仍属已知边界。
- 规则复杂度已接近“最小文本规则”上限；若线上再出现更多语义变体，建议的替代方案是在流式结束前用一次极小的“是否为当前回答拒答”二分类（不新增全量架构），需单独授权后实施。

### 测试
- 先加三条失败用例（A2/B2/C2，`KnowledgeBaseQueryServiceTest` 行 368/377/386）→ 对 `8d6f367` 确认 FAIL（共4项：另含“引用占位不拼接”对偶行 413，GRADLE_EXIT=1），修复后转绿。
- 对偶（同批）：ASCII 单引号/中文引号/引用内句号与换行、“无法”与“回答”分别被引用、“无法结合‘某字段’回答”拼接均 COMPLETED；ASCII 单引号提及后仍有独立真实拒答、条件指引后仍有独立真实拒答均 NO_RESULTS。
- 保留 §13/§14/§15 全部回归：固定模板 / 无文档 / 空输出 = NO_RESULTS；正常排查答案 = COMPLETED。
- Controller 协作：继续用真实 `resolveFinalStatus`，`normalAnswerWithRealStatusJudging...`（正常回答保留来源）与 `realRefusalWithRealStatusJudgingClearsSources`（真实拒答清空 `[]`）配对；不 mock 最终状态。

### 验证（均为离线/本地；PowerShell 直取 `$LASTEXITCODE`）
- `:app:compileJava :app:compileTestJava` → COMPILE_EXIT=0。
- 定点 `:app:test`（两测试类）→ GRADLE_EXIT=0；resolveFinalStatus 套件 tests=28/failures=0/errors=0/skipped=0，成功路径 tests=5/failures=0，answerQuestionStream 契约 tests=3/failures=0，顶层控制器 tests=3/failures=0。
- 全量 `:app:test --no-daemon` → GRADLE_EXIT=0，BUILD SUCCESSFUL，无 FAILED。
- `git diff --check` → exit 0（已消除本轮新常量块间的行尾空白）。

### 尚未验证的真实行为（不得写成已验证）
- 未连真实 LLM / 付费 Embedding / 真实 L1 / 生产库跑端到端 SSE；线上多样措辞、嵌套引用/条件/否定未经样本验证。
- 当前状态为“待 Codex 五次复核”，不得提前写成复核通过。
  > 更正（Codex 五次复核）：§16 把 `QUOTE_MASK`（`□`）加入 `CLAUSE_SPLIT` 当子句终止符的设计，会在“引号内是资料名、拒答构造在引号外”时把 `无法根据□回答` / `未找到关于□的信息` 从中间切断，导致真实拒答被误判 COMPLETED（详见 §17，已于本轮修复）。另 §16 的 `无法结合“某字段”回答也不奇怪 = COMPLETED` 前提本身不成立——该句原文已含 `无法…回答` 构造，不能以“删除引用才产生拒答”为由强判 COMPLETED。故 §16 不得写成“引用占位边界已完备解决”。

## 17. P1 定点修复：引用占位不作子句边界（修复 5b1d88b 确定性回归）

**时间**: 2026-10-01（北京时间）
**基线 HEAD**: `5b1d88b`（§16）
**状态**: P1 源码复核通过（Codex 六次定点源码复核通过 `db036fe`）；已在同机同工具链的独立干净检出上重新执行全量 `:app:test` 并**通过**（455 tests / 0 failures / 0 errors / 50 skipped；非跨机对照）；原工作副本 `GradleWorkerMain` 执行器启动故障根因仍未确定；真实 LLM / Embedding / 生产库及端到端 SSE 未验证；已知文本规则边界保留（后续改为小型二分类需单独授权）。
**范围**: 仅最终拒答判定（`isExplicitRefusal` / `maskQuotedSpans` / `CLAUSE_SPLIT`）+ 相关后端测试 + 进度文档。前端 P2、`answerQuestionStream`、共享 `isNoResultLike`、探测窗口 `normalizeStreamOutput`、SSE 协议、事务、schema、来源组装、已通过的事件顺序补强均不动；不调真实 LLM / 付费 Embedding / 真实 L1 / 生产库。

### 回归根因（Codex 五次复核，5b1d88b 确定性错误）
§16 让占位符 `□` 既作引用内联替换、又被加进 `CLAUSE_SPLIT` 充当子句终止符。当引号内是**资料名称**、而拒答构造在**引号外**时，`□` 把外部构造从中间切断：
1. “抱歉，无法根据“部署说明”回答您的问题，请补充资料。” 期望 NO_RESULTS，`5b1d88b` 判 COMPLETED。
2. “目前未找到关于“索引配置”的相关信息，请补充文档。” 期望 NO_RESULTS，`5b1d88b` 判 COMPLETED。
两例均不命中共享 `isNoResultLike`，错误发生在最终状态判定。

### 最小方案
- **把 `□` 从 `CLAUSE_SPLIT` 移除**：占位符仍是“内联中性标记”——在任何句子/子句切分之前替换整段引用，从而（a）屏蔽引用内部的拒答措辞、（b）吸收引用内的句号/换行不提前截断、（c）保留一个中性标记使引用两侧文本不拼接；但它**不再充当子句边界**，因此引用外的 `无法…回答` / `未找到…信息` 构造得以保持连续而被正确识别为拒答。
- 未新增个别关键词、未改动 `REFUSAL_INABILITY` / `REFUSAL_EMPTY_RETRIEVAL` 窗口、就地否定 / 同子句条件 / “……时请……”护栏与 §15/§16 完全一致。定长 vs 定界取舍：单字符内联占位会压缩间隔，但这恰是识别“未找到关于〔资料名〕的相关信息”这类被资料名撑开、原本超窗口的外部拒答所需；占位符不删空、两侧不拼接，故不会凭空合成原文没有的拒答。

### 重新审视 §16 歧义测试
`无法结合“某些字段”回答也不奇怪，这是正常说明。` 原文本身含 `无法…回答` 构造，旧测试“删除引用才产生拒答、故应 COMPLETED”的前提不成立（新逻辑下会判 NO_RESULTS）。已将其替换为无歧义反例：`“无法回答”这个提示通常表示模型连接异常，请检查网络后重试。`（只有引号内是被提及的界面字符串，引用外无拒答）→ COMPLETED，并据实修正 §16。

### 测试
- 先加 3 条失败回归（NO_RESULTS，`KnowledgeBaseQueryServiceTest` 行 440/449/458）：引用为资料名的外部 `无法…回答`、外部 `未找到…信息`、引用内含换行不干扰外部拒答 → 对 `5b1d88b` 确认 FAIL（GRADLE_EXIT=1）；Controller 协作 `quotedResourceNameRefusalWithRealStatusJudgingClearsSources`（行 281）同样 FAIL。
- Controller 协作：真实 `resolveFinalStatus` 判“无法根据“部署说明”回答您的问题”为 NO_RESULTS，断言落库 status=NO_RESULTS、sources=`[]`、sources 事件=`[]`、done 含 NO_RESULTS；与“正常回答保留来源”“真实拒答清空来源”配对，均不 mock 最终状态。
- 配对/保留：引号内仅提及拒答 → COMPLETED；引号内是资料名、引用外明确拒答 → NO_RESULTS；引用内含句号/换行不干扰外部判定；条件 / 就地否定与独立真实拒答既有作用域行为不变；§13/§14/§15/§16 全部历史回归（固定模板 / 无文档 / 空输出 = NO_RESULTS，正常排查答案 = COMPLETED，ASCII/中文引号提及 = COMPLETED）保留。

### 验证（区分两件事；PowerShell 直取 `$LASTEXITCODE`）
- **定点（此前一次、执行器健康时）= 本轮代码与新测试通过的实证**（日志 `build\p1r4-postfix.log`）：`compileJava`+`compileTestJava` COMPILE_EXIT=0；`:app:test`（`KnowledgeBaseQueryServiceTest` + `RagChatControllerTest`）GRADLE_EXIT=0，**BUILD SUCCESSFUL in 23s**；resolveFinalStatus 套件 tests=31/failures=0/errors=0/skipped=0、成功路径 tests=6/failures=0、answerQuestionStream 契约 tests=3/failures=0、顶层控制器 tests=3/failures=0。
- `git diff --check` → exit 0。
- **全量 `:app:test --no-daemon` = 本轮未取到有效结果（执行器启动故障，非测试通过/失败）**：多次尝试（含 18:30 起）worker JVM 在 bootstrap 阶段即报 `ClassNotFoundException: worker.org.gradle.process.internal.worker.GradleWorkerMain`、退出码 1；Gradle 随后向 worker stdin 写执行规格时才抛 `IOException: 管道正在被关闭`。受控恢复后最后一次单跑（START 19:24:03 / END 19:25:45 / 102s / FULL_EXIT=1）在**空闲物理 3.5GB、提交余量 8.86GB** 下仍复现；`Task :app:test` 已执行（非 UP-TO-DATE），但**本次 `app/build/test-results/test` 内 XML 数为 0**（worker 未跑到产出结果）→ 本次全量不能作为新验证，也不用旧 XML 充当通过。排查：`gradle-worker.jar` 完好且确含该类（`jar tf` exit 0）、失败/成功两次 worker 的 `-cp` argfile 内容 MD5 相同（`FB40…`）、系统无资源耗尽事件、无 java `hs_err_pid*.log`、堆仅 `-Xmx512m` → **释放内存后仍复现，尚无证据认定内存不足是直接原因**；也不能用 jar 缺类解释，**根因尚未确定**，已按指示停止继续重试。（注：该表述只推翻“内存不足即直接原因”这一未经证实的归因，不等于已确定根因。）

### 尚未验证的真实行为（不得写成已验证）
- 本轮修复后的全量回归在**原始工作副本执行器**上仍未取得 BUILD SUCCESSFUL（`GradleWorkerMain` 启动故障，根因未定）；但已在**同机同工具链的独立干净检出**上重新执行并通过（455 / 0 / 0 / 50，见文末「独立干净检出全量回归验证」）。
- 未连真实 LLM / 付费 Embedding / 真实 L1 / 生产库跑端到端 SSE；引用外拒答跨更多子句、多语言撇号成对、内联占位压缩间隔导致的过度识别等变体未经线上样本验证。
- 规则边界同 §15/§16：跨子句且拒答后无紧邻“时”的条件仍可能判拒答；ASCII 单引号靠成对匹配可能误屏蔽；否定词远离情态词可能误判；若再现更多语义变体，建议在流式结束前用一次极小的“当前回答是否拒答”二分类替代（需单独授权）。
- Codex 六次定点源码复核已通过 `db036fe`（P1 源码复核通过）；仍不得宣称原始工作副本执行器故障根因已确定，亦不得宣称真实 LLM / Embedding / 生产库 / 端到端 SSE 已验证；已知文本规则边界保留，后续如改为小型二分类需单独授权。

### 独立干净检出全量回归验证（2026-10-01，同机同工具链）
**结论**：db036fe 定点源码复核通过；在独立干净检出（分离 worktree）上全量 `:app:test` 重新执行并**通过**；原机主工作副本的执行器启动故障根因仍未确定。
**环境诚实说明**：本次运行与产生故障的会话在**同一物理机**（hostname `MSI`）、**同一 `~/.gradle`**、**同一工具链**上完成，差异仅在工作副本（干净 worktree + 清空 `test-results` + `--rerun`）。故这是“同机同工具链的独立干净检出”复验，**不宣称严格意义的跨机对照**。
- **提交**：`db036fe498a3b3c03ca23ea87215467ce8062d19`（干净 worktree `git checkout` 后 HEAD 与之一致，工作树 clean）。
- **工具链**：Gradle 9.6.1（wrapper `distributionUrl` 指向 `gradle-9.6.1-bin.zip`，Build 2026-06-26 / rev `309d128bd9`；Kotlin 2.3.21 / Groovy 4.0.32）；Java Temurin OpenJDK `25.0.4.1+1-LTS`（`JAVA_HOME=...jdk-25.0.4.101-hotspot`，Launcher/Daemon JVM 同此）；OS Windows 11 10.0 amd64。与故障会话所用工具链**一致**，未替换 Gradle 发行版、未复制故障机缓存。
- **外部依赖核对**：`:app:test` 默认排除 `real-eval` 标签（不触发真实 LLM / 付费 Embedding / 真实 L1 / 生产库）；测试用 H2 内存库、Flyway 关闭；Redis/S3 相关测试以 `@MockBean` 打桩；唯一需要真实 Redis 的 `RateLimitIntegrationTest` 标 `@Testcontainers(disabledWithoutDocker = true)`，本机 Docker 守护进程未运行时**自动跳过**而非失败。故本轮**未启动任何外部服务、未调用真实 LLM/Embedding/L1/生产库**。
- **真实结果（PowerShell 直取 `$LASTEXITCODE`，起止时间为本机时钟）**：
  - `:app:compileJava :app:compileTestJava --no-daemon`：START 21:36:29 / END 21:37:31（1m01s）→ **BUILD SUCCESSFUL，COMPILE_EXIT=0**。
  - `:app:test --no-daemon --rerun`：先删 `app/build/test-results/test`（前置 XML 数 0），START 21:37:58 / END 21:40:23（2m24s）→ **BUILD SUCCESSFUL，FULL_EXIT=0**；`:app:test` 实际执行（非 UP-TO-DATE）。
  - **本次新生成 XML**：94 个测试套件文件，合计 **tests=455 / failures=0 / errors=0 / skipped=50**（skipped 主要为无 Docker 时自动禁用的 Testcontainers 用例）。全量日志中 `FAILED` / `GradleWorkerMain` / `ClassNotFoundException` 出现 **0 次**——原 `ClassNotFoundException: GradleWorkerMain` 的 worker 启动故障在干净检出上**未复现**。
  - **定点关键类复核（与 §17 记录逐项一致）**：`KnowledgeBaseQueryServiceTest$ResolveFinalStatusTests` tests=31/0/0/0；`$AnswerQuestionStreamTests` tests=3/0/0/0；`RagChatControllerTest` tests=3/0/0/0。
- **不得写成已验证**：本轮仅补齐离线/本地全量回归证据；仍未连真实 PostgreSQL / 真实 LLM / 付费 Embedding / 真实 L1 / 生产库跑端到端 SSE；worker 启动故障在原始工作副本上的确切根因未确定（干净检出不复现只说明其与特定工作副本/守护态相关，不足以定性）。Codex 六次定点源码复核已通过 `db036fe`（P1 源码复核通过）。


## DevSupport Phase 1 设计文档（第二轮）

**时间**: 2026-10-01
**基线 HEAD**: `2cc5834`
**状态**: 方案待 Codex 复核
**文件**: `devsupport-phase1-design.md`（仓库根目录）

## DevSupport Phase 1 设计文档第二轮修订

**时间**: 2026-10-01
**基线 HEAD**: `05a123c`
**状态**: 修订方案待 Codex 复核
**文件**: `devsupport-phase1-design.md`

## DevSupport Phase 1 设计文档第三轮修订

**时间**: 2026-10-01
**基线 HEAD**: `7ed3eb3`
**状态**: 第三轮修订待 Codex 复核
**文件**: `devsupport-phase1-design.md`
**关键决策**: service/environment = 组织标签；fileHash 唯一不变；来源快照语义；fallback 允许
