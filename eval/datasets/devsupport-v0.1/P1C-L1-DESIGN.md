# P1-C L1 真实向量检索评测 — 实施设计 v1.4

> 状态：**设计 v1.4 + 实现期定点修订（见 §16）已落地**。离线装配与假组件测试已编译通过并全绿；**仍未**启动 Docker、连接数据库、调用付费 Embedding API、运行 `evalP1cReal` 或执行正式 L1。
> v1.3 → v1.4：①预算口径明确为**外层 Embedding 操作次数**，不伪称 HTTP 请求硬上限；评测专用 `OpenAIClient` 关闭 SDK 自动重试（`maxRetries(0)`）并通过 OkHttp 拦截器观测实际 HTTP 请求数，两个计数分别记入报告，两者可能不等时报告差异；②数据库身份核验增强：评测容器 `eval-init.sql` 创建标记表 `eval_instance_identity`（固定 UUID）+ 专用用户 `eval_runner`（最小权限），Java 连接验证标记 UUID 和用户身份，有效拦截常见误连（固定 UUID 是仓库常量，不能单独证明物理容器身份，运行前仍须核对连接 URL、Compose 目标和标记）；③`queryCounts` 拆分：`excludedQueries` → `retrievedQueries` / `answerableMetricQueries` / `noAnswerDiagnosticQueries` / `metricsExcludedNoAnswerQueries` / `failedRetrievalQueries`；④`dataHashes` 补齐 `chunkManifestSha256`，P1-B 冻结工件引用全部核实；⑤`initializeSchema` 改为 `false`（由 `eval-init.sql` 预建表/扩展/索引），评测用户无需 `CREATE EXTENSION` 权限；⑥`tryAcquire()` 达到上限时拒绝递增，避免报告出现未执行的 attempt。
> v1.2 → v1.3：①调用预算移到 Embedding 发送前、区分尝试/成功/失败/重试；②连接身份核验增强（端口+主机+数据库+用户）；③装配代码修正为 `OpenAIClient` + `.openAiClient()` + `org.springframework.ai.document.MetadataMode`；④Phase 0 顺序闭合（`to_regclass` 先于 `COUNT`）；⑤入库核对改为 SHA-256 + 逐行对照 + 全表 28 行；⑥NO_ANSWER 4 题纳入检索（不计入可答题指标分母）；⑦回退检索写入明确契约（候选数、过滤、计数、隔离）；⑧报告补齐第四工件哈希、起止时间、完整请求计数、清理状态。
> 前置依赖：P1-A（指标计算核心，已就绪）、P1-B（devsupport-v0.1 语料 + 候选 gold，已就绪）。

## 1. 目标与范围

**L1 向量检索组件基线**：将 P1-B 的 28 个 chunk 入库到**独立评测 Postgres 实例**后，对全部 20 道问题执行真实 Embedding + 检索：

- 16 道 ANSWERABLE 题：计算片段命中诊断指标和要点覆盖指标
- 4 道 NO_ANSWER 题：保留 topK 原始结果和分数作为诊断参考，**不进入可答题指标分母**

指标：

- **片段命中诊断指标**（沿用 P1-A）：Hit@K、MRR@K（仅 ANSWERABLE 题参与宏平均）
- **要点覆盖指标**（P1-C 新增）：AnswerPointCoverage@K、FullCoverage@K（仅 ANSWERABLE 题参与宏平均）

**L1 调用边界**：直接调用 `VectorStore.similaritySearch(SearchRequest)`，使用与 `KnowledgeBaseVectorService` 相同的 `kb_id` 过滤表达式和 fallback 模式。不经过 Service 层——因 Service 的 2-arg 构造器为包私有，评测代码位于不同包（`interview.guide.eval`），不修改 main 源码。

**明确不做**：
- 不做 L2（原系统查询策略基线）：不涉及查询改写、动态 topK/阈值、RAG chat 接口。
- NO_ANSWER 题（4 道）的检索结果仅作诊断记录（保留 topK 原始结果和分数），不进入 Hit@K / MRR@K / APC@K / FC@K 分母。
- 不做 NDCG@K——当前只有支持与否标注，无分级相关性。

## 2. 稳定评测 ID 与入库 Document ID 的对应

### 问题

P1-B 的 chunk 使用稳定 ID：`{docId}__{configId}__{seq:04d}__{sha256(text):12}`。
PgVectorStore 入库时自动生成 UUID 作为主键。检索返回 UUID，gold 标注的是稳定 chunkId。

### 方案：metadata 桥接 + 入库后数据库核对

入库时在 Document 的 `metadata` 中写入 `eval_chunk_id`（稳定 chunkId）和 `eval_run_id`（本次运行标识）。检索后直接从 `Document.getMetadata().get("eval_chunk_id")` 读取，无需内存反向映射表。

### 入库后核对

入库完成后，通过评测库 JdbcTemplate 执行以下核对（顺序不可调换）：

```sql
-- 1. 全表行数 = 28（包括所有行，不仅按 runId 过滤）
SELECT COUNT(*) FROM vector_store;
-- 期望：28

-- 2. 本次 eval_run_id 的行数 = 28
SELECT COUNT(*) FROM vector_store WHERE metadata->>'eval_run_id' = ?;
-- 期望：28（与全表行数一致，确认无其他 run 的残留数据）

-- 3. 唯一 eval_chunk_id 数量 = 28
SELECT COUNT(DISTINCT metadata->>'eval_chunk_id')
FROM vector_store WHERE metadata->>'eval_run_id' = ?;

-- 4. 无重复 eval_chunk_id
SELECT metadata->>'eval_chunk_id', COUNT(*)
FROM vector_store WHERE metadata->>'eval_run_id' = ?
GROUP BY metadata->>'eval_chunk_id' HAVING COUNT(*) > 1;
-- 期望：0 行

-- 5. 内容校验：每条入库内容的 SHA-256 与 chunks.jsonl 逐行比对
SELECT metadata->>'eval_chunk_id' AS eval_chunk_id,
       ENCODE(DIGEST(content, 'sha256'), 'hex') AS content_sha256,
       metadata->>'doc_id' AS doc_id,
       metadata->>'chunk_index' AS chunk_index
FROM vector_store WHERE metadata->>'eval_run_id' = ?;
```

Java 侧逐行对照逻辑：

```java
// 读取 chunks.jsonl 构建 Map<chunkId, expectedRow>
// expectedRow = { chunkId, sha256(text), docId, chunkIndex }
// 对数据库返回的每一行：
//   1. eval_chunk_id 必须存在于 expectedRow 中
//   2. content_sha256 必须等于 expectedRow.sha256
//   3. doc_id 必须等于 expectedRow.docId
//   4. chunk_index 必须等于 expectedRow.chunkIndex（整数比较）
// 全部匹配后，确认 expectedRow 已全部消费（无遗漏）
```

**失败条件**：全表行数 ≠ 28、本次 runId 行数 ≠ 28、唯一 ID ≠ 28、存在重复、任一 content SHA-256 不匹配、`doc_id` 或 `chunk_index` 缺失或不匹配——任一发生即评测失败，进入 Phase 5 清理。

## 3. 数据隔离

### v1.1 的问题

v1.1 在同一 Postgres 容器中创建第二个数据库 `interview_guide_eval`。GPT v1.2 复核要求：**独立 Postgres 容器与卷**，杜绝与生产实例的任何共享。

### v1.4 方案：独立容器 + 标记表 + 专用评测用户

```
┌──────────────────────────────────┐  ┌──────────────────────────────────────────┐
│  docker-compose.dev.yml          │  │  docker-compose-eval.yml                 │
│  container: interview-postgres   │  │  container: interview-eval-postgres      │
│  port: 5432                      │  │  port: 5433                              │
│  database: interview_guide       │  │  database: interview_guide_eval          │
│  volume: postgres_data           │  │  volume: eval_postgres_data              │
│  user: (生产用户)                │  │  superuser: postgres (init only)         │
│                                  │  │  eval user: eval_runner (运行时)         │
│  ┌────────────────────────────┐  │  │  ┌──────────────────────────────────┐    │
│  │ vector_store (生产数据)    │  │  │  │ eval_instance_identity (标记)    │    │
│  │ 其他业务表                 │  │  │  │ vector_store (仅评测数据, 预建)  │    │
│  └────────────────────────────┘  │  │  └──────────────────────────────────┘    │
└──────────────────────────────────┘  └──────────────────────────────────────────┘
         生产 DataSource                       评测 DataSource
         localhost:5432                        localhost:5433
         生产用户                              eval_runner
```

### docker/postgres/eval-init.sql

由 `pgvector/pgvector:pg16` 容器在首次启动时以 `postgres` 超级用户执行。**此文件仅用于评测容器，不挂载到生产容器。**

```sql
-- 1. 向量扩展（超级用户执行，eval_runner 无需此权限）
CREATE EXTENSION IF NOT EXISTS vector;

-- 2. 实例身份标记表（固定 UUID，由评测容器 init 写入）
CREATE TABLE eval_instance_identity (
    marker_key   TEXT PRIMARY KEY,
    marker_value TEXT NOT NULL,
    created_at   TIMESTAMPTZ DEFAULT NOW()
);

INSERT INTO eval_instance_identity (marker_key, marker_value) VALUES
    ('instance_type',  'p1c-eval-isolated'),
    ('instance_uuid',  'f47ac10b-58cc-4372-a567-0e0283c5d9e7');

-- 3. 预建 vector_store 表（与交付的 eval-init.sql 一致）
CREATE TABLE IF NOT EXISTS vector_store (
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    content   TEXT,
    metadata  json,           -- 交付 schema 是 json，不是 jsonb
    embedding vector(1024)
);

-- 4. 预建 HNSW 索引
CREATE INDEX IF NOT EXISTS spring_ai_vector_index
    ON vector_store USING hnsw (embedding vector_cosine_ops);
```

**与交付文件的一致性说明（2026-09-30 定点修订）**：
- 上面的片段就是 `docker/postgres/eval-init.sql` 的实际内容；该文件里**不含任何用户、口令或授权语句**（`GRANT`/`CREATE ROLE`/`PASSWORD` 关键字零命中）。
- 专用用户与最小权限授予在**另一个初始化脚本** `docker/postgres/eval-user.sh` 中，由它作为 `/docker-entrypoint-initdb.d/20-eval-user.sh` 执行，口令只从容器环境变量 `EVAL_RUNNER_PASSWORD` 读取，缺失时脚本立即退出：

```sql
-- docker/postgres/eval-user.sh 内的等价语义（口令以占位符表示，仓库中不出现字面量）
CREATE ROLE eval_runner WITH LOGIN PASSWORD '<EVAL_RUNNER_PASSWORD>';  -- 实际带 WHERE NOT EXISTS 守卫
GRANT CONNECT ON DATABASE interview_guide_eval TO eval_runner;
GRANT USAGE ON SCHEMA public TO eval_runner;
GRANT SELECT ON TABLE eval_instance_identity TO eval_runner;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE vector_store TO eval_runner;
```

**eval_runner 不具备的权限**：
- 不能 `CREATE TABLE` / `CREATE INDEX` / `CREATE EXTENSION`
- 不能访问 `eval_instance_identity` 以外的系统表（除 `pg_settings` 等公共视图）
- 不能创建用户或授权
- 不能 `DROP` 或 `ALTER` 任何表

**身份核验的保证范围**：
- 专用用户 + 标记表能有效拦截**常见误连**（端口配错、连到生产库、连到其他同名容器）
- 固定 UUID `f47ac10b-...` 是写在仓库 `eval-init.sql` 中的常量，**不能单独证明物理容器身份**——拥有仓库访问权限的人可以在任何 Postgres 实例中手动创建相同的表和数据
- 因此运行前**仍须核对**：实际连接 URL（`eval.datasource.url`）、Compose 目标容器（`docker compose -f docker-compose-eval.yml ps`）、标记表返回值，三者一致才信任
- 报告中**分别记录**配置值（`configuredUrl`、`configuredPort`）与数据库返回值（`current_database()`、`pg_settings port`），供人工比对

### docker-compose-eval.yml

```yaml
# 评测专用 Postgres 实例
# 启动前：
#   1) cp .env.eval.example .env.eval
#   2) 在 .env.eval 中填入 EVAL_DB_PASSWORD 与 EVAL_RUNNER_PASSWORD
#   3) 使用 --env-file .env.eval
# 启动：docker compose -f docker-compose-eval.yml --env-file .env.eval up -d
# 停止：docker compose -f docker-compose-eval.yml down
# 清除：docker compose -f docker-compose-eval.yml down -v

services:
  eval-postgres:
    image: pgvector/pgvector:pg16
    container_name: interview-eval-postgres
    environment:
      POSTGRES_USER: postgres
      # 强制要求显式设置；缺失时 compose 直接拒绝启动，避免使用弱默认值
      POSTGRES_PASSWORD: ${EVAL_DB_PASSWORD:?必须在 .env.eval 或环境变量中设置 EVAL_DB_PASSWORD}
      POSTGRES_DB: ${EVAL_POSTGRES_DB:-interview_guide_eval}
      # 传给 20-eval-user.sh 用于创建 eval_runner
      EVAL_RUNNER_PASSWORD: ${EVAL_RUNNER_PASSWORD:?必须设置 EVAL_RUNNER_PASSWORD}
    volumes:
      - eval_postgres_data:/var/lib/postgresql/data
      - ./docker/postgres/eval-init.sql:/docker-entrypoint-initdb.d/10-eval-schema.sql:ro
      - ./docker/postgres/eval-user.sh:/docker-entrypoint-initdb.d/20-eval-user.sh:ro
    ports:
      # 显式绑定 127.0.0.1，避免本机局域网 / 容器网络暴露到公网
      - "127.0.0.1:${EVAL_POSTGRES_PORT:-5433}:5432"
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres"]
      interval: 5s
      timeout: 5s
      retries: 5

volumes:
  eval_postgres_data:
```

**本节示例此前与交付不符，已按实际文件更正（2026-09-30 定点修订）**：旧示例的超管口令变量名与交付不同，且写成 `${...:-<六位弱默认值>}` 形式（带弱默认口令）；端口未绑定 `127.0.0.1`；且只挂一个 `/docker-entrypoint-initdb.d/init.sql`。交付版把两个密码都改成 `:?` 强制、显式回环绑定，并把初始化拆成 `10-eval-schema.sql`（扩展/标记表/`vector_store`/索引）+ `20-eval-user.sh`（角色与授权，口令只取环境变量）。

与生产实例**零共享**：不同容器、不同卷、不同端口、不同数据库名、不同用户、不同 init SQL。

**注意**：`/docker-entrypoint-initdb.d/` 下的两个脚本**只在数据目录为空（新卷首次初始化）时执行**。若卷已有数据（上次评测未清理或复用旧卷），本次启动**必然**不会重跑 init——此时标记表、`vector_store` 与 `eval_runner` 角色照样存在，但它们是**历史某次**初始化的产物，不能当作「本次启动执行过初始化脚本」的证据；同理，`CREATE ROLE ... WHERE NOT EXISTS` 意味着复用旧卷时**改了 `.env.eval` 的口令也不会轮换角色口令**。两个命题的判据分开写在 `P1C-CONTAINER-CHECKLIST.md` §5（新卷分支/旧卷分支）。
`down -v` 会**不可逆删除卷**，不属于清单的自动步骤：只有在人工确认卷内无需保留的数据后才可由执行者手工运行，并记录确认人与时间。

### 首次写入前核实（v1.4：标记表 + 用户身份 + 表状态）

Phase 0（环境校验）和 Phase 1（入库）之间，以 `eval_runner` 用户连接评测库，执行以下核实（顺序不可调换）：

```java
// ═══ 第 1 层：连接用户身份 ═══
String currentUser = evalJdbc.queryForObject(
    "SELECT current_user", String.class);
if (!"eval_runner".equals(currentUser)) {
    throw new IllegalStateException(
        "评测数据库用户不匹配: 期望 eval_runner, 实际 " + currentUser
        + "。请检查 eval.datasource.username 配置。");
}

// ═══ 第 2 层：实例标记核验（核心——区分评测容器与生产实例） ═══
String expectedUuid = "f47ac10b-58cc-4372-a567-0e0283c5d9e7";

String markerType;
String markerUuid;
try {
    markerType = evalJdbc.queryForObject(
        "SELECT marker_value FROM eval_instance_identity "
        + "WHERE marker_key = 'instance_type'", String.class);
    markerUuid = evalJdbc.queryForObject(
        "SELECT marker_value FROM eval_instance_identity "
        + "WHERE marker_key = 'instance_uuid'", String.class);
} catch (Exception e) {
    throw new IllegalStateException(
        "评测实例标记表 eval_instance_identity 查询失败: " + e.getMessage()
        + "。该表由 docker/postgres/eval-init.sql 创建，"
        + "仅存在于评测容器中。请确认连接的是评测容器"
        + "（核对 docker compose -f docker-compose-eval.yml ps 和 eval.datasource.url）。", e);
}

if (!"p1c-eval-isolated".equals(markerType)) {
    throw new IllegalStateException(
        "评测实例类型标记不匹配: 期望 p1c-eval-isolated, 实际 " + markerType);
}
if (!expectedUuid.equals(markerUuid)) {
    throw new IllegalStateException(
        "评测实例 UUID 标记不匹配: 期望 " + expectedUuid + ", 实际 " + markerUuid
        + "。可能连接到了错误的容器或残留的旧实例。");
}

// ═══ 第 3 层：辅助身份信息（记入报告，不作为通过/失败条件） ═══
String dbName = evalJdbc.queryForObject(
    "SELECT current_database()", String.class);
Integer pgPort = evalJdbc.queryForObject(
    "SELECT setting::int FROM pg_settings WHERE name = 'port'", Integer.class);
boolean isSuperuser = evalJdbc.queryForObject(
    "SELECT rolsuper FROM pg_roles WHERE rolname = 'eval_runner'", Boolean.class);

if (isSuperuser) {
    throw new IllegalStateException(
        "eval_runner 不应具有超级用户权限，请检查 docker/postgres/eval-user.sh");
}

// 连接身份信息记入报告（分别记录配置值与数据库返回值，供人工比对）
String configuredUrl = System.getProperty("eval.datasource.url",
    "jdbc:postgresql://localhost:5433/interview_guide_eval");
String connectionIdentity = "configuredUrl=" + configuredUrl
    + ",db=" + dbName
    + ",internalPort=" + pgPort
    + ",user=" + currentUser
    + ",superuser=" + isSuperuser
    + ",markerType=" + markerType
    + ",markerUuid=" + markerUuid;

// ═══ 第 4 层：vector_store 表状态 ═══
// eval-init.sql 已预建 vector_store 表，此处表一定存在
int existingRows = evalJdbc.queryForObject(
    "SELECT COUNT(*) FROM vector_store", Integer.class);
if (existingRows > 0) {
    List<Map<String, Object>> residual = evalJdbc.queryForList(
        "SELECT metadata->>'eval_run_id' AS run_id, COUNT(*) AS cnt "
        + "FROM vector_store GROUP BY metadata->>'eval_run_id'");
    throw new IllegalStateException(
        "评测 vector_store 表非空（" + existingRows + " 行），拒绝写入。"
        + "残留详情: " + residual + "。"
        + "请先执行: docker compose -f docker-compose-eval.yml down -v "
        + "然后重新 up -d");
}
```

**验证层次总结**：

| 层次 | 验证内容 | 失败含义 | 通过/失败 |
|------|----------|----------|-----------|
| 1 | `current_user = eval_runner` | 连接的不是评测专用用户 | **硬性失败** |
| 2 | `eval_instance_identity` 表存在且 UUID 匹配 | 连接的不是评测容器（可能是生产库） | **硬性失败** |
| 3 | `current_database()`, `pg_settings port`, `rolsuper` | 辅助诊断信息 | 仅记入报告 |
| 4 | `vector_store` 全表行数 = 0 | 残留数据未清理 | **硬性失败** |

**已验证**：
- 连接用户是 `eval_runner`（非超级用户）
- 标记表 `eval_instance_identity` 存在且 UUID 为 `f47ac10b-58cc-4372-a567-0e0283c5d9e7`
- 标记表由评测容器 `eval-init.sql` 创建，生产库不包含此表

**未验证（需运维侧确认）**：
- Docker 容器 ID（SQL 层不可见，由 `docker compose -f docker-compose-eval.yml ps` 确认）
- 卷隔离（SQL 层不可见，由 `docker volume ls` 确认 `eval_postgres_data` 独立于 `postgres_data`）
- 宿主机端口映射（SQL 层看到的是容器内部端口 5432，宿主机 5433 由 `docker compose` 管理）

### 异常退出后的残留处理

若评测进程异常退出（OOM、kill -9、网络中断等），finally 块可能未执行，vector_store 中会残留本次 runId 的数据。

**下次运行时的处理流程**：

1. Phase 0 检测到 vector_store 非空（`existingRows > 0`）
2. 检查残留数据的 `eval_run_id`：
   ```sql
   SELECT DISTINCT metadata->>'eval_run_id' FROM vector_store
   WHERE metadata->>'eval_run_id' IS NOT NULL;
   ```
3. 若残留 runId 与本次不同：**拒绝启动**，报告残留 runId 和行数
4. 人工确认后，执行清理：
   ```bash
   # 方案 A：重建容器（最彻底，但 down -v 会不可逆删除卷——只允许人工确认卷内无需保留内容后手工执行）
   docker compose -f docker-compose-eval.yml down -v
   docker compose -f docker-compose-eval.yml up -d

   # 方案 B：按残留 runId 精确删除（保留容器与表结构），与交付代码 Phase 5 的清理口径一致
   docker exec -T interview-eval-postgres \
       psql -h 127.0.0.1 -p 5432 -U eval_runner -d interview_guide_eval \
       -c "DELETE FROM vector_store WHERE metadata->>'eval_run_id' = '<残留 runId>';"
   ```
   不使用无条件 `DELETE FROM vector_store;`：交付代码的清理始终带 `eval_run_id` 谓词，全表删除会把「污染清理」与「环境污染取证」混为一谈。宿主机无 `psql`，两种方案都要经容器执行。

**不自动清理的原因**：残留数据可能是上一次评测的中间结果，自动删除会掩盖问题。强制人工确认保证可追溯。

### 评测后正常清理

Phase 5（finally 块）按 `eval_run_id` 删除：

```java
try {
    int deleted = evalJdbc.update(
        "DELETE FROM vector_store WHERE metadata->>'eval_run_id' = ?", runId);
    log.info("评测清理完成: 删除 {} 行, runId={}", deleted, runId);

    int remaining = evalJdbc.queryForObject(
        "SELECT COUNT(*) FROM vector_store WHERE metadata->>'eval_run_id' = ?",
        Integer.class, runId);
    if (remaining != 0) {
        log.error("清理后仍有残留: {} 行, runId={}", remaining, runId);
    }
} catch (Exception cleanupError) {
    log.error("评测清理失败, runId={}: {}", runId, cleanupError.getMessage());
}
```

## 4. 模型与索引冻结信息

### v1.1 的问题

v1.1 试图通过 `LlmProviderRegistry` 获取模型配置——但窄 Spring 上下文不连生产库，`LlmProviderRegistry` 无法加载 provider 配置。

### v1.2 方案：评测模型配置独立于生产

评测使用的 EmbeddingModel **直接从系统属性 / 环境变量构建**，不经过 `LlmProviderRegistry`，不连生产数据库。

```java
// 评测 EmbeddingModel 配置来源
String evalEmbeddingApiKey = System.getProperty("eval.embedding.apiKey",
    System.getenv().getOrDefault("AI_BAILIAN_API_KEY", ""));
String evalEmbeddingBaseUrl = System.getProperty("eval.embedding.baseUrl",
    "https://dashscope.aliyuncs.com/compatible-mode/v1");
String evalEmbeddingModelName = System.getProperty("eval.embedding.model",
    "text-embedding-v3");
int evalEmbeddingDimensions = Integer.parseInt(
    System.getProperty("eval.embedding.dimensions", "1024"));
```

**报告措辞**：

| 字段 | 报告中的 key | 来源说明 |
|------|-------------|----------|
| provider | `embeddingProviderId` | 系统属性 `eval.embedding.providerId`，默认 `"dashscope"` |
| 模型名 | `embeddingModelName` | 系统属性 `eval.embedding.model`，默认 `"text-embedding-v3"` |
| 维度 | `embeddingConfiguredDimensions` | 系统属性 `eval.embedding.dimensions`，默认 `1024` |
| API base URL | `embeddingBaseUrl` | 系统属性 `eval.embedding.baseUrl` |

**不声称**"从生产运行时获取"——报告明确标注 `"source": "eval-system-property"`。

### 可编译的装配代码

基于 Spring AI 2.0.0 实际 API（对照仓库 `LlmProviderRegistry.java:262` 和 `ApiPathResolver.java:22`）。

**v1.4 变更**：不再复用 `ApiPathResolver.buildOpenAiClient()`（该方法不配置 `maxRetries` 且无拦截器），改为评测代码自行构建 `OpenAIClient`：关闭 SDK 自动重试（`maxRetries(0)`），添加 OkHttp 拦截器观测实际 HTTP 请求数。`PgVectorStore` 使用 `initializeSchema(false)`（表/扩展/索引已由 `eval-init.sql` 预建）。

```java
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.core.Timeout;
import com.openai.credential.BearerTokenCredential;
import interview.guide.common.ai.ApiPathResolver;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

// 1. 评测 DataSource（专用评测用户 eval_runner）
DriverManagerDataSource evalDs = new DriverManagerDataSource();
evalDs.setUrl(System.getProperty("eval.datasource.url",
    "jdbc:postgresql://localhost:5433/interview_guide_eval"));
evalDs.setUsername(System.getProperty("eval.datasource.username", "eval_runner"));
// 口令：优先环境变量 EVAL_RUNNER_PASSWORD，其次系统属性 eval.datasource.password；
// 两者都缺失时在任何连接/HTTP 之前立即失败——绝不回退到仓库内可见的默认口令
evalDs.setPassword(requiredCredential("eval.datasource.password", "EVAL_RUNNER_PASSWORD"));
JdbcTemplate evalJdbc = new JdbcTemplate(evalDs);

// 2. 评测专用 OpenAIClient：关闭 SDK 重试 + HTTP 计数拦截器
AtomicInteger evalHttpCallCount = new AtomicInteger(0);

Timeout evalTimeout = Timeout.builder()
    .connect(Duration.ofMillis(10_000))
    .read(Duration.ofMillis(300_000))
    .build();

SpringAiOpenAiHttpClient evalHttpClient = SpringAiOpenAiHttpClient.builder()
    .timeout(evalTimeout)
    .interceptor(chain -> {
        evalHttpCallCount.incrementAndGet();
        return chain.proceed(chain.request());
    })
    .build();

ClientOptions evalClientOptions = ClientOptions.Companion.builder()
    .apiKey(evalEmbeddingApiKey)
    .credential(BearerTokenCredential.create(evalEmbeddingApiKey))
    .baseUrl(ApiPathResolver.resolveVersionedBaseUrl(evalEmbeddingBaseUrl))
    .timeout(evalTimeout)
    .httpClient(evalHttpClient)
    .maxRetries(0)  // 关闭 SDK 自动重试：每次外层操作 = 恰好 1 次 HTTP 请求
    .build();

OpenAIClient evalOpenAiClient = new OpenAIClientImpl(evalClientOptions);

EmbeddingModel evalEmbeddingModel = OpenAiEmbeddingModel.builder()
    .openAiClient(evalOpenAiClient)
    .options(OpenAiEmbeddingOptions.builder()
        .model(evalEmbeddingModelName)
        .dimensions(evalEmbeddingDimensions)
        .build())
    .metadataMode(MetadataMode.EMBED)
    .build();

// 3. PgVectorStore（initializeSchema=false：表/扩展/索引由 eval-init.sql 预建）
PgVectorStore evalVectorStore = PgVectorStore.builder(evalJdbc, evalEmbeddingModel)
    .dimensions(evalEmbeddingDimensions)
    .distanceType(PgDistanceType.COSINE_DISTANCE)
    .indexType(PgIndexType.HNSW)
    .initializeSchema(false)
    .build();
```

**与 v1.3 差异**：
- 自行构建 `OpenAIClient`（不再复用 `ApiPathResolver.buildOpenAiClient()`），以配置 `maxRetries(0)` 和 OkHttp 拦截器
- `ApiPathResolver.resolveVersionedBaseUrl()` 仍复用（public static，仅做 URL 版本化）
- `SpringAiOpenAiHttpClient.builder().interceptor()` 添加计数拦截器——位于 SDK `RetryingHttpClient` **内侧**，每次 HTTP 尝试（含重试）均经过
- `ClientOptions.Builder.maxRetries(0)` 关闭 SDK 自动重试——此时外层操作次数 = 实际 HTTP 请求数
- 连接用户从 `postgres` 改为 `eval_runner`
- `PgVectorStore` 的 `initializeSchema` 从 `true` 改为 `false`

**锁定依赖确认**：
- `com.openai:openai-java-core:4.39.1`：`ClientOptions.Builder.maxRetries(int)` 存在（默认值 2）
- `org.springframework.ai:spring-ai-openai:2.0.0`：`SpringAiOpenAiHttpClient.Builder.interceptor(okhttp3.Interceptor)` 存在
- `RetryingHttpClient` 在 `ClientOptions.build()` 中自动包装 `httpClient`，位于拦截器**外侧**
- 以上均来自仓库 Gradle 缓存中的 JAR 反编译验证（2026-09-29）

### 维度与索引从评测库读取

报告中的数据库结构字段**从评测库实际查询**，不读 YAML：

```java
// 向量列维度（atttypmod = 1024 表示 vector(1024)）
Integer vectorDim = evalJdbc.queryForObject(
    "SELECT atttypmod FROM pg_attribute "
    + "JOIN pg_class ON attrelid = oid "
    + "WHERE relname = 'vector_store' AND attname = 'embedding'",
    Integer.class);

// 索引定义
String indexDef = evalJdbc.queryForObject(
    "SELECT indexdef FROM pg_indexes "
    + "WHERE tablename = 'vector_store' AND indexname = 'spring_ai_vector_index'",
    String.class);
// 期望包含: USING hnsw ... vector_cosine_ops
```

**维度一致性校验**：

```java
if (vectorDim == null || vectorDim != evalEmbeddingDimensions) {
    throw new IllegalStateException(
        "向量维度不一致: 数据库=" + vectorDim
        + ", 评测配置=" + evalEmbeddingDimensions);
}
```

### 分数读取

检索结果的分数通过 `Document.getScore()` 获取。PgVectorStore 的 `DocumentRowMapper` 在 `similaritySearch` 时自动设置：

```java
// PgVectorStore 内部：score = 1.0 - COSINE_DISTANCE(embedding, query_embedding)
Double score = document.getScore();  // 由 similaritySearch 设置，非调用方设置
```

报告中的 score 来源标注为 `"source": "Document.getScore() = 1.0 - cosine_distance"`。

### 首次真实调用后核验

入库首批 chunk 后，从数据库读取一条 embedding 验证维度：

```java
String embeddingText = evalJdbc.queryForObject(
    "SELECT embedding::text FROM vector_store LIMIT 1", String.class);
// embeddingText 格式: "[0.012,-0.034,...]" — 解析逗号数 + 1 = 维度
long actualDim = embeddingText.chars().filter(c -> c == ',').count() + 1;
if (actualDim != evalEmbeddingDimensions) {
    throw new IllegalStateException(
        "首次 Embedding 维度不匹配: 期望 " + evalEmbeddingDimensions + ", 实际 " + actualDim);
}
```

## 5. 付费开关

### 严格布尔解析

```java
String raw = System.getProperty("eval.p1c.realApi");
boolean realApiEnabled = "true".equals(raw);  // 严格布尔，不宽松 truthy
```

缺失、空串、`"false"`、`"TRUE"`、`"1"`、`"yes"` 等**一律禁用**。

### Gradle 任务配置

```groovy
// app/build.gradle

// 护栏：普通 test 排除评测标签
tasks.named('test') {
    useJUnitPlatform {
        excludeTags 'real-eval'
    }
}

// 独立评测任务
tasks.register('evalP1cReal', Test) {
    group = 'evaluation'
    description = 'P1-C L1 real vector retrieval evaluation (requires API key)'

    onlyIf { project.findProperty('eval.p1c.realApi') == 'true' }

    useJUnitPlatform {
        includeTags 'real-eval'
    }

    filter { includeTestsMatching 'interview.guide.eval.P1cRealRetrievalEvalTest' }

    // 评测数据库连接：交付版只在显式 -P 时转发，不给任何默认口令
    ['eval.datasource.url', 'eval.datasource.username',
     'eval.datasource.password'].each { key ->
        def val = project.findProperty(key)
        if (val != null && val.toString() != '') {
            systemProperty key, val.toString()
        }
    }

    // Embedding 配置
    systemProperty 'eval.embedding.apiKey',
        project.findProperty('eval.embedding.apiKey')
            ?: ''  // 必须显式传入
    systemProperty 'eval.embedding.baseUrl',
        project.findProperty('eval.embedding.baseUrl')
            ?: 'https://dashscope.aliyuncs.com/compatible-mode/v1'
    systemProperty 'eval.embedding.model',
        project.findProperty('eval.embedding.model') ?: 'text-embedding-v3'
    systemProperty 'eval.embedding.dimensions',
        project.findProperty('eval.embedding.dimensions') ?: '1024'

    // 付费开关
    systemProperty 'eval.p1c.realApi',
        project.findProperty('eval.p1c.realApi') ?: ''

    shouldRunAfter tasks.named('test')
}
```

**交付版 `app/build.gradle` 与该示例的差异（据实记录）**：`eval.p1c.realApi` 的默认值是 `'false'`（真正的门控在 Java 侧）；`eval.datasetDir` 用 `rootProject.file(...)` 注入绝对路径，避免 Test 任务在 `app/` 下工作目录漂移；API Key 与数据库口令**只在显式 `-P` 时转发**，未指定时不设置系统属性——否则空串会覆盖 Java 端 `System.getenv` 的回退路径。推荐的调用方式是把密钥放在环境变量里：`AI_BAILIAN_API_KEY=... EVAL_RUNNER_PASSWORD=... ./gradlew :app:evalP1cReal -Peval.p1c.realApi=true`，**严禁在命令行明文传 API Key**。

## 6. 调用次数、重试与输入规模限制

### v1.3 的问题

v1.3 的 `EvalCallBudget` 在外层操作（`vectorStore.add()` / `similaritySearch()`）前做预算门控，但限制表中的"总 API 调用上限 50"措辞暗示这是 HTTP 请求硬上限。实际上：
- 一次外层 `add()` 调用内部，SDK 的 `RetryingHttpClient`（默认 `maxRetries=2`）可能发出 1~3 次 HTTP 请求
- 外层 `AtomicInteger` 无法感知 SDK 内部重试
- "最多 50 次 API 请求"的声称不成立

### v1.4 方案：双口径——外层操作预算 + HTTP 请求观测

**核心原则**：

1. **外层操作预算**（硬限制）：`EvalCallBudget` 门控的是**外层 Embedding 操作次数**（每次 `add()` 或 `similaritySearch()` 调用），不是 HTTP 请求数。报告、费用说明中**不再声称**"最多 N 次 API 请求"。
2. **HTTP 请求观测**（信息性）：评测专用 `OpenAIClient` 通过 OkHttp 拦截器记录实际 HTTP 请求数，写入报告。外层操作数与实际 HTTP 请求数**可能不等**——外层操作可能在发送前失败（0 HTTP），SDK 内部也可能拆成多个请求。实现应分别记录两个计数，发现不等时在报告中记录差异，不能据此宣称实际 HTTP 请求受 50 次硬限制。
3. **两个计数的可观测来源**：

| 计数 | 可观测来源 | 含义 | 是否硬限制 |
|------|-----------|------|-----------|
| 外层操作尝试数 | `EvalCallBudget.attempts`（`AtomicInteger`，评测代码维护） | 每次 `add()` / `similaritySearch()` 调用前 `tryAcquire()` 递增 | **是**——超过 `hardLimit=50` 即抛异常 |
| 实际 HTTP 请求数 | `evalHttpCallCount`（`AtomicInteger`，OkHttp 拦截器维护） | 每次 HTTP 请求发出前拦截器递增（位于 `RetryingHttpClient` 内侧） | **否**——仅记录，不阻断 |

**预算门控实现**（与 v1.3 相同，仅命名更准确）：

```java
class EvalCallBudget {
    private final int hardLimit;
    private final AtomicInteger attempts = new AtomicInteger(0);
    private final AtomicInteger successes = new AtomicInteger(0);
    private final AtomicInteger failures = new AtomicInteger(0);
    private final AtomicInteger retries = new AtomicInteger(0);

    EvalCallBudget(int hardLimit) { this.hardLimit = hardLimit; }

    /** 外层操作发送前调用：检查预算 + 递增尝试计数。返回本次 attempt 编号。 */
    int tryAcquire() {
        int current = attempts.get();
        if (current >= hardLimit) {
            throw new IllegalStateException(
                "外层操作预算耗尽: 已完成 " + current + " 次，上限 " + hardLimit
                + "（本次未递增，未执行）");
        }
        return attempts.incrementAndGet();
    }

    void recordSuccess() { successes.incrementAndGet(); }
    void recordFailure() { failures.incrementAndGet(); }
    void recordRetry() { retries.incrementAndGet(); }

    Map<String, Integer> snapshot() {
        return Map.of(
            "attempts", attempts.get(),
            "successes", successes.get(),
            "failures", failures.get(),
            "retries", retries.get(),
            "hardLimit", hardLimit
        );
    }
}
```

**调用模式**（入库和检索统一）：

```java
// 入库批次
for (List<Document> batch : batches) {
    budget.tryAcquire();  // 外层操作门控
    try {
        evalVectorStore.add(batch);
        budget.recordSuccess();
    } catch (Exception e) {
        budget.recordFailure();
        budget.recordRetry();
        budget.tryAcquire();  // 重试也是外层操作
        try {
            evalVectorStore.add(batch);
            budget.recordSuccess();
        } catch (Exception retryEx) {
            budget.recordFailure();
            throw retryEx;  // 重试失败 → 整体失败
        }
    }
}

// 查询检索（每题）
budget.tryAcquire();  // 外层操作门控
try {
    List<Document> results = evalVectorStore.similaritySearch(request);
    budget.recordSuccess();
} catch (Exception e) {
    budget.recordFailure();
    // fallback 逻辑（见 §7 Phase 2 回退契约）
}
```

**`maxRetries(0)` 的作用与局限**：

| 场景 | 外层操作数 | 实际 HTTP 请求数 | 两者关系 |
|------|-----------|-----------------|----------|
| `maxRetries(0)`，网络正常 | N | N | **相等** |
| `maxRetries(0)`，HTTP 5xx | N（失败 N 次） | N | **相等**——不重试，直接失败 |
| `maxRetries(2)`（SDK 默认），网络正常 | N | N | 相等 |
| `maxRetries(2)`，间歇性 429/5xx | N | N ~ 3N | **不等**——SDK 自动重试，外层无感知 |

v1.4 锁定 `maxRetries(0)` 关闭 SDK 自动重试，但外层操作仍可能在发送前失败（0 HTTP）或 SDK 内部拆成多个请求，因此两者**可能不等**。实现应分别记录两个计数，发现不等时在报告中记录差异。报告中**同时记录两个计数**，不混淆。

### 限制表

| 限制项 | 值 | 依据 | 执行方式 |
|--------|-----|------|----------|
| 入库 chunk 数 | 28 | P1-B 固定数据集 | 硬编码，超出即 bug |
| 查询数 | **20** | 16 ANSWERABLE + 4 NO_ANSWER | 硬编码，超出即 bug |
| 每批 chunk 大小 | ≤ 10 | DashScope API 限制（`MAX_BATCH_SIZE = 10`） | 与生产 `KnowledgeBaseVectorService` 一致 |
| 入库外层操作 | ≤ 3 | ⌈28/10⌉ = 3 | `budget.tryAcquire()`，超出即失败 |
| 查询外层操作 | ≤ 20 | 每题 1 次（含 NO_ANSWER） | `budget.tryAcquire()`，超出即失败 |
| 回退外层操作 | ≤ 20 | 主检索失败时才触发 | `budget.tryAcquire()`，与主检索共享预算 |
| **外层操作总上限** | **50** | 3 + 20 = 23 正常 + 回退/重试余量 | `EvalCallBudget`，**发送前**检查 |
| 每批重试次数 | ≤ 1 | 避免无限重试 | 入库批次失败后重试 1 次，仍失败则整体失败 |
| SDK 自动重试 | **0**（`maxRetries(0)`） | 评测代码配置 | `ClientOptions.Builder.maxRetries(0)` |
| 单题检索 topK | 10 | 评测参数 | 固定值 |
| 单题检索超时 | 30s | 防止挂起 | `SearchRequest` 无超时参数，由调用方计时 |

> **注意**：上表"外层操作总上限 50"保护的是**外层 Embedding 操作次数**，不是底层 HTTP 请求数。两者可能不等（外层操作可能在发送前失败、SDK 内部可能拆成多个请求），实现分别记录，发现不等时报告差异。

### 费用估算（仅供参考，不作为限制）

| 项目 | 计算 | 费用 |
|------|------|------|
| 入库 Embedding | 28 chunks × ~800 tokens = 22,400 tokens | ¥0.016 |
| 查询 Embedding | 20 queries × ~50 tokens = 1,000 tokens | ¥0.001 |
| **总估算** | ~23,400 tokens | **≈ ¥0.02** |

> 定价依据：DashScope `text-embedding-v3` 公开定价 ¥0.0007 / 1K tokens（2026-09 查询）。实际费用以阿里云账单为准。代码中**不实现费用熔断**——外层操作上限 50 已充分防止意外超支。`maxRetries(0)` 关闭 SDK 自动重试，减少隐藏重试费用，但外层操作仍可能在发送前失败或 SDK 内部拆成多个请求，实际 HTTP 请求数由拦截器观测并记入报告。

## 7. 评测流程

### Phase 0：环境校验

```
1. 检查 realApiEnabled == true，否则 AssumptionViolatedException
2. 以 eval_runner 用户连接评测数据库（localhost:5433），验证连接可用
3. 身份核验（4 层，见 §3 "首次写入前核实"）：
   a. 第 1 层：current_user = "eval_runner"（硬性失败）
   b. 第 2 层：eval_instance_identity 标记表查询，UUID = "f47ac10b-58cc-4372-a567-0e0283c5d9e7"（硬性失败）
   c. 第 3 层：current_database()、pg_settings port、rolsuper（记入报告，不判定）
   d. 第 4 层：vector_store 全表行数 = 0（硬性失败）
   e. 记录 connectionIdentity 写入报告
   ⚠️ 以上全部在任何建表、写入或付费请求之前执行
4. 读取 P1-B chunks.jsonl，验证 chunk 数 = 28
5. 读取 P1-B candidate-gold.json，验证总题数 = 20（ANSWERABLE = 16，NO_ANSWER = 4）
6. 冻结工件校验（先于凭据读取、客户端构建、数据库连接与任何写入）：
   a. 计算 chunks.jsonl、candidate-gold.json、corpus-manifest.json、chunk-manifest.json 的 SHA-256
   b. 行尾归一化（CRLF/CR → LF）后再取 UTF-8 SHA-256，作为比对口径
   c. 与已提交冻结清单 `p1c-frozen-artifacts.json` 的 `expectedSha256NormalizedLf` **逐项比对**
   d. 清单是超集要求：缺少必需工件条目、重复 fileName、非法哈希都算违例，不能靠裁剪清单放行
   e. 任一项不符 → 整轮不启动（仅计算哈希而不比对批准值不构成校验：条数不变、正文改写的漂移会静默通过）
   f. 原始字节哈希仅作为报告参考值记录，不参与比对（`core.autocrlf=true` 下随检出环境变化）
7. 构建 EmbeddingModel 和 PgVectorStore：
   - EmbeddingModel 使用评测专用 OpenAIClient（maxRetries(0) + HTTP 计数拦截器）
   - PgVectorStore: initializeSchema=false（表/扩展/索引由 eval-init.sql 预建）
8. 建表后核验（eval-init.sql 已预建，此处验证结构）：
   a. 查询向量列维度 == 评测配置维度
   b. 查询索引定义包含 USING hnsw ... vector_cosine_ops
```

### Phase 1：入库（写操作）

```
1. 生成 eval_run_id = UUID.randomUUID().toString()
2. 构建 Document 列表：
   - new Document(chunk.text, metadata)
   - metadata = {
       "kb_id": "900001",
       "eval_chunk_id": chunk.chunkId,
       "eval_run_id": runId,
       "doc_id": chunk.docId,          // 来源文档 ID
       "chunk_index": chunk.seqIndex    // chunk 在文档中的序号
     }
3. 分批调用 evalVectorStore.add(batch)（每批 ≤ 10）
   - 每批发送前调用 budget.tryAcquire()
   - 每批失败后重试 1 次（重试也需 budget.tryAcquire()）
   - 仍失败则整体失败，进入 Phase 5 清理
4. 入库后核对（见 §2 入库后核对）：
   a. 全表行数 = 28
   b. 本次 runId 行数 = 28
   c. COUNT(DISTINCT eval_chunk_id) = 28
   d. 无重复 eval_chunk_id
   e. 逐行 SHA-256 内容校验 + doc_id + chunk_index 对照
   f. 首条 embedding 维度核验
5. 任一核对失败 → 进入 Phase 5 清理，报告失败原因
```

### Phase 2：检索（只读）

```
对全部 20 道问题（16 ANSWERABLE + 4 NO_ANSWER）：
  1. 构建 SearchRequest：
     SearchRequest.builder()
       .query(question)
       .topK(10)
       .similarityThresholdAll()  // minScore = 0.0，不过滤
       .filterExpression("kb_id in ['900001']")
       .build()
  2. budget.tryAcquire()  // 发送前检查预算 + 计数
  3. 调用 evalVectorStore.similaritySearch(request)
     - 成功 → budget.recordSuccess()
     - 失败 → budget.recordFailure()，进入回退（见下方契约）
  4. 将返回的 Document 列表转为 RetrievalHit：
     - chunkId = metadata.get("eval_chunk_id")
     - kbId = "900001"
     - score = document.getScore()  // 1.0 - cosine_distance
  5. 隔离检查（主检索带 kb_id 过滤，返回结果同样逐条判定，不静默过滤）：
     - eval_run_id 缺失或与本次不同 → 中止并报告
     - eval_chunk_id 缺失或不在冻结 28 ID 集合内 → 中止并报告（冻结 ID 准入）
     - kb_id 越界或非字符串型 → 中止并报告
  6. 构建 QueryJudgement(evalQuery, hits)
     - ANSWERABLE 题 → 进入 Phase 3/4 指标计算
     - NO_ANSWER 题 → 仅记录 topK 原始结果和分数到报告诊断区
```

### Phase 2 回退契约

当主检索（带 `kb_id` 过滤）抛异常时，执行回退。回退的每一步都必须遵守预算门控和隔离检查。

| 步骤 | 操作 | 预算 | 说明 |
|------|------|------|------|
| 1 | `budget.tryAcquire()` | 递增 attempts | 回退也是一次 API 调用 |
| 2 | 构建无过滤 SearchRequest：`topK(30)`，无 `filterExpression` | — | 候选数扩大到 `topK×3 = 30`，与生产 `KnowledgeBaseVectorService` 回退模式一致 |
| 3 | `evalVectorStore.similaritySearch(fallbackRequest)` | — | 发出回退请求；此后步骤 4-6 都在"请求失败"捕获范围之外执行 |
| 4 | 对**原始**候选逐条归属判定（不先过滤） | — | 见下方"候选处置分类"，任何越界一律中止整轮 |
| 5 | 冻结 ID 准入（只对确属本次运行的候选） | — | 违例抛出后不得降级为单题 `RETRIEVAL_FAILED`，直接中止整轮 |
| 6 | 若回退请求成功但确属本次的候选为空 | — | 主链路已异常，空结果无法区分隔离问题，该题记 `RETRIEVAL_FAILED`；回退请求本身失败同样记 `RETRIEVAL_FAILED` |

**回退候选处置分类**（步骤 4 的权威口径：先判定，再取舍）：

| 候选类型 | 处置 | 依据 |
|----------|------|------|
| `eval_run_id == 本次 runId` 且 `eval_chunk_id ∈ 冻结 28 ID` 且 `kb_id == "900001"` | 保留，进入准入与指标 | 确属本次运行的本次数据 |
| 缺失 `eval_run_id`（完全不带 eval 元数据的行） | **中止整轮** | 无法归属即隔离破坏，回退不静默过滤任何行 |
| `eval_run_id` 为其他 runId | **中止整轮** | 本条即原"非本次 runId 不静默过滤"规则的落地 |
| 本次 runId 但 `eval_chunk_id` 缺失或不在冻结 28 ID 内 | **中止整轮** | 冻结集合外的本次行只能来自污染或串号 |
| 本次 runId 但 `kb_id` 越界或非字符串型 | **中止整轮** | kb 隔离破坏；`metadata`（交付 schema 为 `json` 列，非 `jsonb`）里 kb_id 的 JSON 值必须是字符串 `"900001"` |
| 候选列表为 `null` 或含 `null` 元素 | **中止整轮** | 无法归属 |

**回退限制**：
- 每道题最多 1 次回退（不回退再回退）
- 回退候选数固定为 `topK × 3 = 30`
- 回退调用与主检索共享 `EvalCallBudget` 的 50 次总预算
- 回退**不做静默过滤**：上表所有越界分类一律中止评测并报告，报告保留原始候选数 `fallbackRawCandidateCount` 以便核对
- 归属判定与准入在"回退请求失败"的 `catch` 之外：请求已成功，之后的隔离违例是实验级异常，不得 `recordFailure()` 后降级成单题失败

### Phase 3：片段命中诊断指标（纯离线，沿用 P1-A）

```
仅对 16 道 ANSWERABLE 题计算：
1. 将 candidate-gold 的 allCandidateChunkIds 展平为 relevantChunkIds
2. 调用 P1-A RetrievalMetrics.evaluate(k, judgements)
3. 得到 Hit@K、MRR@K（Recall@K 仅作参考）
4. 多 K 值：从 topK=10 的结果离线截断，分别计算 K=1,3,5,10

4 道 NO_ANSWER 题的检索结果不进入此阶段。
```

### Phase 4：要点覆盖指标（纯离线，P1-C 新增）

```
仅对 16 道 ANSWERABLE 题计算：
1. 从 candidate-gold.json 读取 answerPoints[].supportingChunkIds
2. 对每个 K ∈ {1,3,5,10}，计算：
   a. 单题 APC@K（要点覆盖比例）
   b. 单题 FC@K（全部要点是否都被覆盖）
   c. 宏平均 MacroAPC@K = (Σ APC@K(q)) / 16
   d. 宏平均 MacroFC@K = (Σ FC@K(q)) / 16
   e. 全局覆盖要点总数 = Σ covered_i（跨 16 题求和），与宏平均分别记录
3. 全局分母 = 38（P1-B 中 16 道 ANSWERABLE 题的要点总数）
4. 同时记录 "coveredPoints / 38" 和 "MacroAPC@K"，两者含义不同：
   - coveredPoints / 38 = 全局要点覆盖率（所有题的要点汇总）
   - MacroAPC@K = 宏平均单题要点覆盖率（先算每题再平均）

4 道 NO_ANSWER 题的检索结果不进入此阶段。
```

### Phase 4 之后：整轮可用性判定（实验级 verdict）

指标计算完成后、清理之前，对整轮做一次实验级判定（实现里随 Phase 3 指标一起计算，报告字段 `roundAvailability`）：

```
输入：expectedQueries=20、queriesCompleted、failedRetrievalQueries、
      zeroResultQueries、queriesWithFallbackAttempted

判定（Status = USABLE / NOT_USABLE）：
1. 请求完成题数 < 20 → NOT_USABLE，理由记入报告
2. failedRetrievalQueries > 0 → NOT_USABLE（"按零计入宏平均是失败题的单题口径，不是检索质量结论"）
3. 其余情况 → USABLE

NOT_USABLE 时的动作：抛出实验级异常，整轮判为失败，指标不得作为正式基线。
```

**口径边界**（避免与既有约定冲突）：
- `OK_ZERO_RESULT` 属正常数据点（检索真的零命中），**不参与**本判定，不是失败
- 失败题的原始记录照旧保留在报告里，宏平均照旧把失败题按零计入 — 本 verdict 只在**结论层**声明"这一轮不可解释"，不改变单题口径
- 正式基线的前提是 20 题请求全部完成；只有 USABLE 轮的指标才被解读

### Phase 5：清理（写操作，finally）

```
1. evalJdbc.update("DELETE FROM vector_store WHERE metadata->>'eval_run_id' = ?", runId)
2. 验证 COUNT(*) WHERE eval_run_id = runId → 0
3. 在 finally 块中执行，确保异常时也清理
4. 清理结果记入报告 cleanupStatus 字段：
   - "CLEANED"：清理成功，剩余 0 行
   - "CLEAN_FAILED"：清理失败（记录异常信息）
   - "CLEAN_PARTIAL"：清理后仍有残留（记录残留行数）
5. 清理失败时记录日志，不抛异常（不掩盖原始异常）
6. 正常剩余行非零也记入报告（不静默忽略）
```

## 8. 检索参数

| 参数 | 值 | 理由 |
|------|-----|------|
| topK | 10 | 28 个 chunk 的开发集足够观察前排和部分长尾 |
| minScore | 0.0 | 不过滤，先测原始排序能力 |
| query rewrite | 关闭 | L1 直接传原始 question，不做改写 |

**多 K 值报告**：每题检索一次 topK=10，再离线截断为 K=1, 3, 5, 10。

## 9. 指标定义

### 9.1 片段命中诊断指标（沿用 P1-A）

将 `allCandidateChunkIds` 展平为 `relevantChunkIds`：

- **Hit@K**：前 K 条中至少命中一条相关 chunk → 1.0，否则 0.0
- **MRR@K**：前 K 条中第一条相关 chunk 排名的倒数；无命中为 0
- **Recall@K**（仅作参考）：前 K 条命中的不同相关 chunk 数 / 全部相关 chunk 数

**注意**：Recall@K 将多个可替代片段视为"全部必须找回"，不适合衡量答案完整性。**不称为答案完整率**。

### 9.2 要点覆盖指标（P1-C 新增）

基于 `candidate-gold.json` 的 `answerPoints[].supportingChunkIds` 结构。

**符号**：
- 查询 q 有 n 个要点 `ap_1, ..., ap_n`
- `S_i = supportingChunkIds(ap_i)`
- `R_K` = 检索结果前 K 条的 chunkId 集合

**单题 AnswerPointCoverage@K**：

```
covered_i = 1 if S_i ∩ R_K ≠ ∅, else 0
APC@K(q) = (Σ covered_i) / n
```

**单题 FullCoverage@K**：

```
FC@K(q) = 1 if ∀i: S_i ∩ R_K ≠ ∅, else 0
```

**宏平均**：`MacroAPC@K = (Σ APC@K(q)) / 16`，`MacroFC@K = (Σ FC@K(q)) / 16`

### 9.3 指标关系

| 指标 | 衡量维度 | 对组合题的行为 |
|------|----------|----------------|
| Hit@K | 是否有任一相关 chunk 在前 K | 命中一个可替代片段即 1.0 |
| MRR@K | 首个相关 chunk 的排名 | 只看最早命中的那个 |
| Recall@K | 相关 chunk 的找回比例 | 可替代片段被当作"全部必须找回" |
| APC@K | 答案要点的覆盖比例 | 每个要点独立判定 |
| FC@K | 全部要点是否都被覆盖 | 最严格——要求所有要点都有支持片段命中 |

## 10. 报告格式

### 独立 P1-C 报告外壳

不修改 P1-A 的 `EvaluationReport` record 或 fixture 报告契约。

```json
{
  "reportVersion": "p1c-l1-v1.4",
  "executionMode": "real-api",
  "scope": "p1c_l1_vector_baseline",
  "disclaimer": "L1 向量检索组件基线，非完整系统基线。不含查询改写、动态 topK/阈值。",

  "snapshot": {
    "commit": "2903ec2...",
    "evalRunId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
    "startTime": "2026-09-29T20:00:00Z",
    "endTime": "2026-09-29T20:01:30Z"
  },

  "connectionIdentity": {
    "configuredUrl": "jdbc:postgresql://localhost:5433/interview_guide_eval",
    "database": "interview_guide_eval",
    "internalPort": 5432,
    "user": "eval_runner",
    "superuser": false,
    "markerType": "p1c-eval-isolated",
    "markerUuid": "f47ac10b-58cc-4372-a567-0e0283c5d9e7",
    "markerVerified": true,
    "source": "runtime-current_user + eval_instance_identity + pg_settings + pg_roles",
    "note": "configuredUrl 来自系统属性，其余来自数据库查询。两者分别记录，供人工比对。"
  },

  "embedding": {
    "providerId": "dashscope",
    "modelName": "text-embedding-v3",
    "configuredDimensions": 1024,
    "baseUrl": "https://dashscope.aliyuncs.com/compatible-mode/v1",
    "source": "eval-system-property"
  },

  "vectorStore": {
    "database": "interview_guide_eval",
    "containerPort": 5433,
    "table": "vector_store",
    "dbVectorDimension": 1024,
    "dbIndexDef": "CREATE INDEX spring_ai_vector_index ON public.vector_store USING hnsw (embedding public.vector_cosine_ops)",
    "dimensionConsistencyCheck": "PASS",
    "initializeSchema": false,
    "schemaCreatedBy": "docker/postgres/eval-init.sql",
    "source": "eval-db-pg-attribute + pg-indexes"
  },

  "scoreMethod": {
    "formula": "1.0 - cosine_distance",
    "source": "Document.getScore(), set by PgVectorStore.DocumentRowMapper",
    "note": "仅适用于当前锁定 Spring AI 2.0.0 实现 + COSINE_DISTANCE 配置"
  },

  "evalConfig": {
    "topK": 10,
    "minScore": 0.0,
    "queryRewrite": false,
    "evalKbId": 900001,
    "reportingKValues": [1, 3, 5, 10],
    "fallbackNote": "回退对原始候选逐条归属判定；越界一律中止整轮，不做静默过滤"
  },

  "roundAvailability": {
    "description": "本轮是否可作为正式基线（存在 RETRIEVAL_FAILED 或请求未全部完成 → NOT_USABLE，整轮判失败）",
    "expectedQueries": 20,
    "queriesCompleted": 20,
    "failedRetrievalQueries": 0,
    "zeroResultQueries": 0,
    "queriesWithFallbackAttempted": 0,
    "status": "USABLE",
    "reasons": []
  },

  "dataHashes": {
    "chunksJsonlSha256": "...",
    "candidateGoldSha256": "...",
    "corpusManifestSha256": "...",
    "chunkManifestSha256": "...",
    "contentHashAlgorithm": "SHA-256",
    "contentHashByteContract": "行尾归一化（CRLF/CR → LF）后 UTF-8 字节",
    "source": "runtime-sha256-of-frozen-p1b-artifacts（原始字节哈希仅作参考，批准值比对见 artifactFreeze）"
  },

  "artifactFreeze": {
    "description": "四份 P1-B 工件与已提交冻结清单的逐项哈希对照；失败发生在建客户端、连数据库与任何写入之前",
    "freezeListFile": "eval/datasets/devsupport-v0.1/p1c-frozen-artifacts.json",
    "hashCaliber": "行尾归一化（CRLF/CR → LF）后取 UTF-8 字节的 SHA-256 小写十六进制",
    "comparedField": "expectedSha256NormalizedLf",
    "requiredFileNames": ["chunks.jsonl", "candidate-gold.json", "corpus-manifest.json", "chunk-manifest.json"],
    "checks": [
      {
        "fileName": "chunks.jsonl",
        "expectedSha256NormalizedLf": "...",
        "actualSha256NormalizedLf": "...",
        "actualSha256RawBytes": "...",
        "sha256RawBytesAtRecord": "...",
        "normalizedLfMatched": true
      }
    ],
    "violations": [],
    "status": "PASS"
  },

  "ingestionVerification": {
    "expectedChunks": 28,
    "totalTableRows": 28,
    "actualRunIdRows": 28,
    "actualUniqueEvalChunkIds": 28,
    "duplicateEvalChunkIds": 0,
    "contentHashAlgorithm": "SHA-256",
    "contentHashMismatch": [],
    "docIdMismatch": [],
    "chunkIndexMismatch": [],
    "firstEmbeddingDimension": 1024,
    "status": "PASS"
  },

  "callGuard": {
    "description": "外层 Embedding 操作预算（非 HTTP 请求硬上限）",
    "sdkMaxRetries": 0,
    "outerOperations": {
      "ingestionAttempts": 3,
      "ingestionSuccesses": 3,
      "ingestionFailures": 0,
      "ingestionRetries": 0,
      "queryAttempts": 20,
      "querySuccesses": 20,
      "queryFailures": 0,
      "queryRetries": 0,
      "fallbackAttempts": 0,
      "totalAttempts": 23,
      "totalSuccesses": 23,
      "totalFailures": 0,
      "totalRetries": 0,
      "hardLimit": 50
    },
    "httpObservation": {
      "observedHttpRequests": 23,
      "source": "OkHttp interceptor (SpringAiOpenAiHttpClient.Builder.interceptor)",
      "note": "外层操作数与 HTTP 请求数可能不等（发送前失败、SDK 内部拆分等）。两者不等时此字段记录差异。"
    },
    "budgetGatePosition": "before-send",
    "status": "WITHIN_LIMIT"
  },

  "costEstimate": {
    "estimatedTokens": 23400,
    "estimatedCNY": 0.02,
    "pricingBasis": "DashScope text-embedding-v3 ¥0.0007/1K tokens (2026-09)",
    "note": "估算值，非代码熔断。maxRetries(0) 下无隐藏重试费用。实际费用以阿里云账单为准。"
  },

  "fragmentDiagnostics": {
    "description": "片段命中诊断（P1-A，allCandidateChunkIds 展平，仅 16 道 ANSWERABLE 题）",
    "participatingQueries": 16,
    "perK": {
      "k=1":  { "macroHitAtK": null, "macroMrrAtK": null },
      "k=3":  { "macroHitAtK": null, "macroMrrAtK": null },
      "k=5":  { "macroHitAtK": null, "macroMrrAtK": null },
      "k=10": { "macroHitAtK": null, "macroMrrAtK": null }
    }
  },

  "pointCoverage": {
    "description": "要点覆盖指标（P1-C，answerPoints[].supportingChunkIds，仅 16 道 ANSWERABLE 题）",
    "participatingQueries": 16,
    "totalAnswerPoints": 38,
    "perK": {
      "k=1":  { "macroApcAtK": null, "macroFcAtK": null, "coveredPoints": null, "globalCoverageRatio": null },
      "k=3":  { "macroApcAtK": null, "macroFcAtK": null, "coveredPoints": null, "globalCoverageRatio": null },
      "k=5":  { "macroApcAtK": null, "macroFcAtK": null, "coveredPoints": null, "globalCoverageRatio": null },
      "k=10": { "macroApcAtK": null, "macroFcAtK": null, "coveredPoints": null, "globalCoverageRatio": null }
    }
  },

  "noAnswerDiagnostics": {
    "description": "NO_ANSWER 题诊断（4 道，仅记录检索结果，不进入指标分母）",
    "participatingQueries": 4,
    "perQuery": []
  },

  "queryCounts": {
    "totalQueries": 20,
    "retrievedQueries": 20,
    "answerableMetricQueries": 16,
    "noAnswerDiagnosticQueries": 4,
    "metricsExcludedNoAnswerQueries": 4,
    "metricsDenominator": 16,
    "failedRetrievalQueries": 0,
    "zeroResultQueries": 0,
    "queriesWithFallbackAttempted": 0,
    "fallbackRawCandidatesObserved": 0
  },

  "failureInfo": {
    "failedPhase": null,
    "failureMessage": null,
    "partialResultsAvailable": false
  },

  "cleanupStatus": "CLEANED",

  "perQueryEvaluations": []
}
```

**`queryCounts` 字段说明**：

| 字段 | 期望值 | 含义 |
|------|--------|------|
| `totalQueries` | 20 | P1-B candidate-gold 中的总题数 |
| `retrievedQueries` | 20 | 成功完成检索的题数（含 ANSWERABLE 和 NO_ANSWER） |
| `answerableMetricQueries` | 16 | 进入 Hit@K / MRR@K / APC@K / FC@K 分母的题数 |
| `noAnswerDiagnosticQueries` | 4 | 仅记录检索诊断结果的 NO_ANSWER 题数 |
| `metricsExcludedNoAnswerQueries` | 4 | 被排除出指标分母的 NO_ANSWER 题数（= `noAnswerDiagnosticQueries`） |
| `metricsDenominator` | 16 | 宏平均分母（= `answerableMetricQueries`） |
| `failedRetrievalQueries` | 0 | 检索失败的题数（主检索 + 回退均失败） |

**不变量**：`totalQueries = retrievedQueries + failedRetrievalQueries`；`retrievedQueries = answerableMetricQueries + noAnswerDiagnosticQueries`。

**注意**：指标值写 `null` 表示尚未运行。真实报告只写实际计算结果。v1.1 中的 `"示意:..."` 前缀不再使用——报告只包含真实数据或 `null`。

### 报告输出位置

```
eval/datasets/devsupport-v0.1/p1c-l1-report.json
```

## 11. 从 candidate-gold 到评测输入的转换

### 11.1 片段命中诊断（P1-A EvalQuery）

```java
EvalQuery evalQuery = new EvalQuery(
    entry.queryId(),
    entry.question(),
    Answerability.ANSWERABLE,
    entry.candidateGold().allCandidateChunkIds()  // 展平列表
);
```

### 11.2 要点覆盖（P1-C 新增）

```java
record AnswerPointGold(
    String queryId,
    int answerPointCount,
    boolean multiChunkRequired,
    List<List<String>> supportingChunkIdsPerPoint
) {}

List<List<String>> supportingPerPoint = entry.candidateGold().answerPoints().stream()
    .map(ap -> ap.supportingChunkIds())
    .toList();
```

### 11.3 检索结果格式

```java
List<RetrievalHit> hits = documents.stream()
    .map(doc -> new RetrievalHit(
        doc.getMetadata().get("eval_chunk_id").toString(),
        "900001",
        doc.getScore()  // Double, set by PgVectorStore
    ))
    .toList();
```

## 12. 实现文件清单

| 文件 | 作用域 | 说明 |
|------|--------|------|
| `app/src/test/java/interview/guide/eval/P1cRealRetrievalEvalTest.java` | test | 评测入口，`@Tag("real-eval")`，窄上下文，手动构建 Bean |
| `app/src/test/java/interview/guide/eval/P1cEvalCallBudget.java` | test | 外层操作预算门控：尝试/成功/失败/重试分类计数 |
| `app/src/test/java/interview/guide/eval/P1cEvalChunkUploader.java` | test | 入库 + 入库后核对（全表 28 行、SHA-256 逐行对照、doc_id/chunk_index、首条向量维度） |
| `app/src/test/java/interview/guide/eval/P1cAnswerPointCoverage.java` | test | APC@K、FC@K 计算 + 全局 coveredPoints / 38 |
| `app/src/test/java/interview/guide/eval/P1cEvalReport.java` | test | P1-C 报告 record（独立外壳，含 connectionIdentity、callGuard 双口径、queryCounts 拆分、cleanupStatus、failureInfo） |
| `app/src/test/java/interview/guide/eval/P1cEvalReportWriter.java` | test | 报告 JSON 序列化（Jackson 3） |
| `app/src/test/java/interview/guide/eval/P1cRetrievalHandler.java` | test | 主检索 + 单次回退、四态契约；回退**原始候选**逐条归属判定与冻结 ID 准入（违例中止整轮） |
| `app/src/test/java/interview/guide/eval/P1cEvalRunOrchestrator.java` | test | 主体 → 清理 → 报告的生命周期编排；清理/报告失败作为 suppressed 附加，不覆盖主异常 |
| `app/src/test/java/interview/guide/eval/P1cFrozenArtifactVerifier.java` | test | 与已提交冻结清单逐项比对行尾归一化 SHA-256；纯函数 `compare()` + 清单读取 + 违例收集 |
| `app/src/test/java/interview/guide/eval/P1cRoundAvailability.java` | test | 整轮可用性 verdict（USABLE / NOT_USABLE），存在 `RETRIEVAL_FAILED` 或请求未全完成则整轮不可用 |
| `app/src/test/java/interview/guide/eval/P1cOfflineUnitTest.java` | test | 假组件离线用例（不含真实 DB / API，随 `:app:test` 运行） |
| `eval/datasets/devsupport-v0.1/p1c-frozen-artifacts.json` | — | 已提交冻结清单：4 份 P1-B 工件的批准哈希（行尾归一化口径）+ 记录时原始字节哈希与行数 |
| `app/build.gradle` | — | `test` 任务 `useJUnitPlatform { excludeTags 'real-eval' }` + `evalP1cReal` 任务 |
| `docker-compose-eval.yml` | — | 独立评测 Postgres 容器，挂载 `10-eval-schema.sql` + `20-eval-user.sh`，端口 `127.0.0.1:5433`，两个口令均 `${...:?}` 强制 |
| `docker/postgres/eval-init.sql` | — | 向量扩展 + 标记表 + 预建 `vector_store`（`metadata json`）+ HNSW `vector_cosine_ops` 索引；**不含用户、口令或授权语句** |
| `docker/postgres/eval-user.sh` | — | 创建 `eval_runner` 角色 + 最小权限授予；口令只取环境变量 `EVAL_RUNNER_PASSWORD`，缺失即 `set -u` 退出 |
| `.env.eval.example` | — | 变量名模板（不含值）。`.env.eval` 由 `.gitignore` 排除，永不提交 |
| `eval/datasets/devsupport-v0.1/P1C-CONTAINER-CHECKLIST.md` | — | 无 API 容器验证清单 C1–C11（含 §12 实测记录表） |
| `eval/datasets/devsupport-v0.1/p1c-l1-report.json` | — | 输出报告（运行后生成） |

### 与现有代码的关系

- **复用 P1-A**：`RetrievalMetrics.evaluate()`、`EvalQuery`、`RetrievalHit`、`QueryJudgement`、`Answerability`。
- **复用 P1-B**：`chunks.jsonl`、`candidate-gold.json`。
- **复用生产检索模式**：`SearchRequest` + `kb_id` 过滤表达式 + fallback，但不经过 `KnowledgeBaseVectorService`。
- **不新增 main 源码**。
- **不修改 docker-compose.dev.yml**——评测容器独立。

## 13. 运行前检查清单

| # | 检查项 | 命令 / 方式（宿主机无 `psql`，SQL 一律经容器执行） | 期望 |
|---|--------|-------------|------|
| 1 | 评测 Postgres 容器运行 | `docker compose -f docker-compose-eval.yml --env-file .env.eval ps` | State = healthy |
| 2 | 评测数据库可连接（专用用户，容器内 TCP） | `docker exec -T -e PGPASSWORD="$PGPASSWORD" interview-eval-postgres psql -h 127.0.0.1 -p 5432 -U eval_runner -d interview_guide_eval -X -c "SELECT 1"` | 连接成功，返回 1 |
| 3 | 标记表存在且 UUID 正确 | 同上包装，`-c "SELECT marker_value FROM eval_instance_identity WHERE marker_key='instance_uuid'"` | `f47ac10b-58cc-4372-a567-0e0283c5d9e7` |
| 4 | vector 扩展已安装 | 同上包装，`-c "SELECT extname FROM pg_extension WHERE extname='vector'"` | 一行 `vector` |
| 5 | vector_store 表已存在且为空 | 同上包装，`-c "SELECT COUNT(*) FROM vector_store"` | `0`（`eval-init.sql` 预建表） |
| 6 | `eval_runner` 权限正确 | `docker exec -T interview-eval-postgres psql -U postgres -d interview_guide_eval -X -c "SELECT rolsuper, rolcreatedb FROM pg_roles WHERE rolname='eval_runner'"` | `f \| f` |
| 7 | `.env` 中有 API Key | `grep -q AI_BAILIAN_API_KEY .env && echo "已设置" || echo "未设置"` | 输出 `已设置` |
| 8 | P1-B 工件完整（4 个冻结文件） | `cd` 到仓库根后 `ls eval/datasets/devsupport-v0.1/{chunks.jsonl,candidate-gold.json,corpus-manifest.json,chunk-manifest.json}` | 4 个文件存在（原示例只给第一个文件带了目录前缀，复制执行会误报缺失，已更正） |
| 8b | 冻结工件与批准哈希一致 | 由 Phase 0 自动执行（比对 `p1c-frozen-artifacts.json` 的 `expectedSha256NormalizedLf`）；离线可在改一个字符后运行 `./gradlew :app:test --tests 'interview.guide.eval.*'` 观察违例 | 4 项 `normalizedLfMatched=true`，违例列表为空 |
| 9 | 普通 test 不触发评测 | `GRADLE_USER_HOME=/c/temp/gradle-tmp ./gradlew :app:test --no-daemon` | 无 Embedding 调用 |
| 10 | 装配代码编译通过 | `GRADLE_USER_HOME=/c/temp/gradle-tmp ./gradlew :app:compileTestJava` | BUILD SUCCESSFUL |

**本表 2–6 项的执行环境（2026-09-30 实测）**：目标机是 Windows + Git Bash，**宿主机没有 `psql`**（`command -v psql` → NOT FOUND，`jq` 同样缺失），所以这些检查一律通过 `docker exec -T interview-eval-postgres psql ...` 在容器内执行；表中 `$PGPASSWORD` 由清单 §1 的 `echo` + `read -rs P1C_PW && export PGPASSWORD="$P1C_PW"` 在会话内取值一次（口令不出现在命令行、shell 历史或记录表里），收尾 `unset PGPASSWORD P1C_PW`。以 `eval_runner` 做凭据核验时必须走容器内 TCP（`-h 127.0.0.1 -p 5432`），因为 unix socket 的认证方式可能是 `trust`，socket 登录成功**不能**证明 `.env.eval` 里的口令与卷内角色口令一致。第 6 项用 `-U postgres` 走容器内 unix socket（仅读系统目录，不做凭据结论）。完整命令与判据见 `P1C-CONTAINER-CHECKLIST.md` §1 与 C6/C7/C8。

## 14. 失败时清理规则

| 场景 | 清理行为 | 谁执行 |
|------|----------|--------|
| Phase 1 入库后核对失败 | finally 块按 `eval_run_id` DELETE | 评测代码 |
| Phase 2 检索中 API 调用超限 | finally 块按 `eval_run_id` DELETE | 评测代码 |
| Phase 2 检索中异常退出 | finally 块尝试 DELETE；若 finally 也未执行，下次 Phase 0 检测残留 | 评测代码 + 人工 |
| finally 块自身失败 | 记录日志（runId、残留行数），不抛异常 | 评测代码 |
| 下次运行发现残留 | Phase 0 拒绝启动，报告清理命令 | 评测代码 |
| 人工清理 | `docker compose -f docker-compose-eval.yml down -v && up -d` | 人工 |

## 15. 仍需人工确认的事项

| # | 事项 | 原因 |
|---|------|------|
| 1 | ~~`OpenAiApi.builder()` 的确切 API~~ | **v1.3 已解决**：复用 `ApiPathResolver.buildOpenAiClient()`，使用 `OpenAIClient` + `.openAiClient()` |
| 2 | ~~数据库身份核验不足~~ | **v1.4 已解决**：`eval-init.sql` 创建标记表 + 专用用户 `eval_runner`，Java 端 4 层验证 |
| 3 | ~~预算口径混淆外层操作与 HTTP 请求~~ | **v1.4 已解决**：外层操作预算（硬限制）+ HTTP 观测（信息性），两者可能不等，分别记录 |
| 4 | 评测 Postgres 端口 5433 是否与本地环境冲突 | 若已占用 5433，需改为其他端口（同步修改 `docker-compose-eval.yml` 和 `eval.datasource.url`） |
| 5 | `eval.embedding.apiKey` 的传递方式 | 通过 Gradle `systemProperty` 从项目属性传入，还是通过环境变量直接读取 |
| 6 | 是否需要在 CI 中运行 P1-C | 若需要，CI 环境需有评测 Postgres 和 API Key secret |
| 7 | 报告 JSON 字段是否需要调整 | 当前结构基于 v1.4 反馈设计，实际运行后可能需要增减 |
| 8 | ~~`eval-init.sql` 中 `eval_runner` 密码策略~~ | **已解决（2026-09-30 定点修订）**：任何提交文件里都不再出现口令字面量。`eval-init.sql` 不含用户/授权语句；角色创建在 `docker/postgres/eval-user.sh`，口令只取容器环境变量 `EVAL_RUNNER_PASSWORD`（`${...:?}` 强制，缺失即拒绝启动/退出）；Gradle 仅在显式 `-P` 时转发；Java 侧 `requireCredential("eval.datasource.password", "EVAL_RUNNER_PASSWORD")` 缺失时在任何连接与 HTTP 之前失败。本文档原示例中的固定字面量已全部移除。**遗留风险（不是口令策略问题，而是卷复用问题）**：`CREATE ROLE` 带 `WHERE NOT EXISTS` 守卫，复用旧卷时口令不会随 `.env.eval` 轮换，必须用当前口令实际 TCP 登录核验，见验证清单 C6/C7 |

## 16. 变更摘要

### v1.4 文档定点修订（2026-09-30，白天离线文档轮）

只改文档，未启动容器、未连数据库、未调用 Embedding、未运行 `evalP1cReal`、未删任何卷，`reportVersion` 保持 `p1c-l1-v1.4`。

| # | 问题 | 修订 |
|---|------|------|
| 1 | 文档示例残留一个固定的评测口令字面量（共 **4** 处：§3 init SQL、§4 装配代码、§5 Gradle 片段、§16 v1.4 变更记录），与交付不符且照抄会给今晚实例设一个仓库内可见弱口令 | 全部移除：§3 改为 `<EVAL_RUNNER_PASSWORD>` 占位并说明角色/授权实际在 `20-eval-user.sh`；§4 改为「只从环境变量/系统属性读取，缺失即在任何连接前失败」；§5 改为交付版的「仅显式 `-P` 转发」形式；变更记录里那条标注为已作废。全仓 `grep` 该字面量现在零命中 |
| 2 | §3 示例把 `vector_store` 的元数据列写成二进制 JSON 类型，而交付的 `eval-init.sql` 用的是 `json` | 按真实交付 schema 更正为 `json`，并把 §7 回退归属表里对该列类型的描述改为「`metadata`（`json` 列）里 kb_id 的 JSON 值必须是字符串」 |
| 3 | §2 的 compose 示例是早期版本（超管口令变量名与交付不同且带六位弱默认值、未绑定 `127.0.0.1`、只挂 `init.sql`） | 用交付文件实际内容替换，并列出差异；§3 超级用户报错文案按交付代码改为指向 `eval-user.sh` |
| 4 | §15 #8 仍把口令策略列为「待人工确认」 | 标为已解决并写清各层强制点；同时把真正的遗留风险改记为「旧卷复用不轮换口令」，指向验证清单 C6/C7 |
| 5 | §3 init 只在空卷执行的说明不足；§12 文件清单把用户创建归给 `eval-init.sql`；§13 第 8 项 `ls` 缺目录前缀；§14 方案 B 是无条件全表 DELETE | 分别更正为：新卷/旧卷两命题与证据分离、文件清单补 `eval-user.sh` 与 `.env.eval.example` 并声明 init.sql 不含用户/授权、`ls` 用花括号展开、方案 B 按 `eval_run_id` 谓词删除。§13 末尾补记目标机实测环境（宿主无 `psql`/`jq`，psql 一律容器内执行且凭据核验走容器内 TCP） |
| 6 | §13 的 2–6 项命令仍是「宿主机直接 `psql -h localhost -p 5433`」形态，在目标机上必然 `command not found`；第 1/9/10 项缺 `--env-file` 与 `GRADLE_USER_HOME` | 表格命令改为可直接复制执行：第 1 项补 `--env-file .env.eval`，2–6 项包装成 `docker exec -T interview-eval-postgres psql …`（凭据核验用容器内 TCP，`-U postgres` 只用于读系统目录），9/10 项补 `GRADLE_USER_HOME=/c/temp/gradle-tmp`；表头与说明同步。修订后的命令经 `bash -n` 语法核验（0 失败），期望值也按容器输出改写（`0` 而不是「0 行」、`f \| f` 而不是 `false, false`） |

### v1.4 实现期定点修订（2026-09-30，白天离线修复轮）

针对 `caf05cf` 复核意见，仅改离线实现与文档，未启动容器、未连数据库、未调用付费 API，`reportVersion` 保持 `p1c-l1-v1.4`。

| # | 复核问题 | 严重度 | 修订前实现 | 修订后 |
|---|---------|--------|-----------|--------|
| 1 | 回退结果的隔离违例被静默丢弃 | P0 | 先按 runId/kb_id/冻结 ID 过滤，再对剩下的候选做准入 → 外来行根本不进违例检查，"合法 + 非法混合"的题被记成成功；且准入异常位于"回退请求失败"的 `catch` 内，被降级为单题 `RETRIEVAL_FAILED`，与"准入违例直接中止整轮"的交付说明不符 | 对**原始**候选先逐条归属判定（§7 回退候选处置分类：缺失 runId / 其他 runId / 本次 runId 但 ID 或 kb_id 越界 / null 候选，一律中止整轮，无静默过滤），准入与归属都在请求失败捕获范围之外，不再 `recordFailure()`；新增"合法+非法混合"等 8 个假组件用例 |
| 2 | 检索请求全部失败仍可能成功退出 | P1 | 无失败阈值，报告照常写出且实验判定为成功 | 新增 `P1cRoundAvailability`：请求完成题数 < 20 或存在 `RETRIEVAL_FAILED` → `NOT_USABLE` 并抛实验级异常，指标不得作为正式基线；失败题原始记录与"按零计入宏平均"的单题口径**保留不变**，`OK_ZERO_RESULT` 属正常数据点不参与判定 |
| 3 | "冻结工件校验"只算哈希不比对批准值 | P1 | 仅计算 SHA-256 写入报告；内容改写但条数不变（28/20/16/4/38 全对）的漂移可通过 | 新增已提交清单 `p1c-frozen-artifacts.json`，Phase 0 逐项比对**行尾归一化**（CRLF/CR → LF）SHA-256，失败发生在凭据读取、客户端构建、数据库连接与任何写入之前；清单按超集要求校验（缺条目/重复/非法哈希均为违例）；新增"计数不变但正文改写"用例 |
| 4 | 入库核对失败时上下文为空 | P1（口径） | `ctx.partialResults/observe` 只在全部阶段完成后设置 | 在每个阶段边界（PHASE0/写入起点/PHASE1/PHASE2/PHASE3）落快照，中途异常时报告仍携带 `artifactFreeze`/`ingestionVerification`/题目结果与请求观测 |
| 5 | 报告写出口径不一致 | P1（口径） | `reportWriter.write(result)` 收到 `reportWritten=false` 快照，`withReportWritten()` 只在之后返回 | 明确为**写出前快照**契约并写入 javadoc + 测试；写出结果只经 `execute()` 返回值传播，`reportWritten` 不进 JSON |
| 6 | 跨平台哈希口径风险 | — | 未记录 | 原始字节哈希仅作参考值（`core.autocrlf=true` 下随检出环境变化），批准值与比对均采用归一化口径，与 `P1cExpectedChunk` 一致 |

### v1.3 → v1.4

| # | 复核问题 | 严重度 | v1.3 | v1.4 |
|---|---------|--------|------|------|
| 1 | 预算口径：外层操作 ≠ HTTP 请求 | P0 | `EvalCallBudget` 在 `add()`/`similaritySearch()` 前计数，文档暗示为 HTTP 请求硬上限 | 明确命名为**外层 Embedding 操作预算**（硬限制）；评测专用 `OpenAIClient` 设 `maxRetries(0)` 关闭 SDK 自动重试；通过 OkHttp 拦截器**观测**实际 HTTP 请求数（信息性，不限制）；报告中 `callGuard` 拆为 `outerOperations` + `httpObservation` 两个独立对象 |
| 2 | 数据库身份核验不足以区分评测/生产实例 | P0 | 检查 `current_database()`、`pg_settings port`、`current_user=postgres`；生产库若同名同端口可通过 | `eval-init.sql` 创建标记表 `eval_instance_identity`（固定 UUID `f47ac10b-...`）+ 专用用户 `eval_runner`（最小权限，`rolsuper=false`）；Java 端 4 层验证：`current_user=eval_runner` → 标记 UUID 匹配 → 辅助信息记入报告 → `vector_store` 全表为空。保证范围：有效拦截常见误连；固定 UUID 是仓库常量，不能单独证明物理容器身份，运行前仍须核对连接 URL、Compose 目标和标记 |
| 3 | `initializeSchema=true` 与最小权限用户冲突 | P1 | `PgVectorStore(initializeSchema=true)` 尝试 `CREATE EXTENSION` | `eval-init.sql` 以超级用户预建扩展/表/索引；评测代码改为 `initializeSchema(false)`；`eval_runner` 仅需 SELECT/INSERT/UPDATE/DELETE |
| 4 | `queryCounts` 中 `excludedQueries` 语义模糊 | P1 | 只有 `totalQueries` + `excludedQueries` | 拆分为 `retrievedQueries`、`answerableMetricQueries`、`noAnswerDiagnosticQueries`、`metricsExcludedNoAnswerQueries`、`failedRetrievalQueries`；明确不变式 `totalQueries = retrievedQueries + failedRetrievalQueries`、`retrievedQueries = answerableMetricQueries + noAnswerDiagnosticQueries` |
| 5 | P1-B 工件引用不完整 | P1 | `dataHashes` 只有 `p1bManifestSha256`（歧义：P1-B 有两个 manifest） | 拆为 `corpusManifestSha256` + `chunkManifestSha256`；Phase 0 计算全部 4 个冻结工件的 SHA-256 |

#### 其他 v1.4 调整

- `eval-init.sql` 新增文件：创建标记表、预建 `vector_store` 表 + HNSW 索引、创建 `eval_runner` 用户
- `docker-compose-eval.yml` 挂载 `eval-init.sql` 替代 `init.sql`
- §4 装配代码：评测专用 `OpenAIClient` 构建（`maxRetries(0)` + OkHttp 拦截器）；`PgVectorStore` 使用 `eval_runner` 凭据 + `initializeSchema(false)`
- §5 Gradle 默认用户名改为 `eval_runner`、密码曾以固定字面量作为默认值（**该做法已作废**，见 §15 #8：现只在显式 `-P` 时转发，仓库内不保留口令字面量）
- §6 限制表：新增 `sdkMaxRetries=0` 行；费用估算补充 `maxRetries(0)` 与 `maxRetries(2)` 对比
- §7 Phase 0：从 9 步增加到 10 步（新增标记表验证、`eval_runner` 连接、4 个工件 SHA-256）
- §10 报告：`connectionIdentity` 增加 `markerType`/`markerUuid`/`superuser` 字段；`callGuard` 拆为两个对象；`queryCounts` 从 3 字段扩为 7 字段；`vectorStore.initializeSchema=false`
- §12 文件清单：新增 `docker/postgres/eval-init.sql`、`P1cEvalHttpCallCounter.java`
- §13 运行前检查：从 8 项增加到 10 项（新增标记表验证、`eval_runner` 权限检查、`chunk-manifest.json` 存在性）
- §15 人工确认：#2（身份核验）和 #3（预算口径）标记为已解决；新增 #8（`eval_runner` 密码策略）

### v1.2 → v1.3

| # | 复核问题 | 严重度 | v1.2 | v1.3 |
|---|---------|--------|------|------|
| 1 | 调用上限不能约束实际付费请求 | P0 | `AtomicInteger` 在 `add()`/`similaritySearch()` **之后**计数 | `EvalCallBudget` **发送前** `tryAcquire()` 检查预算；分类记录 attempts/successes/failures/retries；明确 SDK 内部重试不可拦截 |
| 2 | 数据库名检查不足以证明连接独立容器 | P0 | 仅检查 `current_database()` | 增加 `pg_settings port`、`current_user` 核验；记录 `connectionIdentity` 写入报告 |
| 3 | 装配代码与锁定依赖不符 | P1 | `OpenAiApi` + `.openAiApi()` + `org.springframework.util.MetadataMode` | `OpenAIClient` + `.openAiClient()` + `org.springframework.ai.document.MetadataMode`；复用 `ApiPathResolver.buildOpenAiClient()` |
| 4 | Phase 0 空表检查顺序不闭合 | P1 | 直接 `SELECT COUNT(*) FROM vector_store`（表可能不存在） | `to_regclass` 先判断表是否存在 → 存在则检查全表 → 不存在则继续 → `initializeSchema` 建表 → 建表后再核验 |
| 5 | 入库核对不足以证明 28 chunk 一一对应 | P1 | 仅按 runId 统计不同 ID + MD5 | 全表行数 = 28 + runId 行数 = 28 + SHA-256 逐行对照 + `doc_id`/`chunk_index` 核对 + 无额外行 |
| 6 | NO_ANSWER 诊断被排除 | P1 | 仅检索 16 道 ANSWERABLE 题 | 全部 20 道题检索；4 道 NO_ANSWER 保留 topK 原始结果和分数到 `noAnswerDiagnostics`；不进入可答题指标分母 |
| 7 | 回退检索改变预算与隔离语义 | P1 | "无过滤回退 + 本地过滤"，未规定计数和隔离 | 明确回退契约：`topK×3=30` 候选、`budget.tryAcquire()` 计数、`eval_run_id` 隔离检查、非本次 run 结果中止报告 |
| 8 | 要点覆盖与报告口径需补齐 | P2 | 只有宏平均 APC@K；缺第四工件哈希、起止时间、完整请求计数、清理状态 | 分别记录 `macroApcAtK` 和 `coveredPoints/38`；补齐 `p1bManifestSha256`、`startTime/endTime`、`callGuard` 分类计数、`cleanupStatus`、`failureInfo` |

### 其他 v1.3 调整

- 查询数从 16 改为 20（含 NO_ANSWER），正常预算从 19 次改为 23 次（3 入库 + 20 查询）
- Phase 1 metadata 增加 `doc_id` 和 `chunk_index` 字段
- 入库核对 SQL 从 3 条增加到 5 条（增加全表行数、runId 行数检查）
- Phase 0 从 9 步增加到 9 步（步骤重排 + 增加建表后核验）
- §12 文件清单增加 `P1cEvalCallBudget.java`
- §13 运行前检查增加第 8 项（编译检查）
- §15 人工确认事项 #1 标记为已解决

---

**P1-B 提交号**：`2903ec2`
**设计版本**：P1-C L1 design v1.4
**状态**：待定点复核，复核通过后实现并运行。
