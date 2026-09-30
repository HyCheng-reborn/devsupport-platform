# P1-C 独立评测容器 · 无 API 验证清单

> 状态：**待执行**（计划 22:00–23:00 执行，前置条件是 Codex 对 `f6aa629` 的复核结论已收到）。
> 本文件里所有「预期」都是**设计与代码推导出的期望值，不是实测结果**。执行时把实测值写进 §12 记录表。
> 范围：只启动评测 Postgres 容器 + 静态/SQL 核对。**禁止**：调用 Embedding API、运行 `:app:evalP1cReal`、执行正式 L1、连接生产库、启动生产 Compose。

## 0. 本轮（白天）已做的静态核对

纯本地、零成本、可复跑。本轮共执行 26 项断言全部 PASS；下方代码块给出其中 15 项核心断言的可复跑子集（其余 11 项是同一套检查里的跨文件常量一致性与密钥扫描，结论逐条列在下面）。

- `docker-compose-eval.yml`：`127.0.0.1:${EVAL_POSTGRES_PORT:-5433}:5432`（显式回环绑定）、`image: pgvector/pgvector:pg16`、独立 `container_name: interview-eval-postgres`、挂载 `10-eval-schema.sql` + `20-eval-user.sh`、两个密码均为 `${...:?}` 强制（无默认弱口令）、无 `network_mode: host`。
- `docker/postgres/eval-init.sql`：`CREATE EXTENSION IF NOT EXISTS vector`、`embedding vector(1024)`、`USING hnsw (embedding vector_cosine_ops)`、标记值与 Java 常量逐字一致、文件内不含任何用户/权限/口令语句、无 `DROP`/`TRUNCATE`。
- `docker/postgres/eval-user.sh`：`set -euo pipefail`、密码缺失即退出、口令经 `psql -v` + `:'runner_pw'` + `%L` 传参（文件内无明文口令）、`CREATE ROLE` 带 `WHERE NOT EXISTS`、授予项恰为 `CONNECT` / `USAGE` / `SELECT, INSERT, UPDATE, DELETE`（`vector_store`）/ `SELECT`（标记表）、**无** `ALL PRIVILEGES`、**无** `SUPERUSER`/`CREATEDB`。
- 装配与门禁：`initializeSchema(false)`、`current_user` 必须为 `eval_runner`、默认连接串 `jdbc:postgresql://127.0.0.1:5433/interview_guide_eval`、检查 `rolsuper`、检查全表为空；`:app:test` 排除 `real-eval`，`evalP1cReal` 的 `eval.p1c.realApi` 默认 `'false'`。
- 隔离与密钥：`docker-compose.yml` / `docker-compose.dev.yml` 中**不含**任何 `eval` 引用（生产栈不会挂载评测初始化脚本）；`.gitignore` 第 12–13 行忽略 `.env` 与 `.env.eval`，`git ls-files` 只有 `.env.eval.example`；对 eval 相关文件做口令字面量扫描命中 0。
- 离线测试：`GRADLE_USER_HOME=/c/temp/gradle-tmp ./gradlew :app:test --tests 'interview.guide.eval.*' --no-daemon` exit 0。

静态核对只能证明「配置自洽」，不能证明容器实际行为、镜像内 pgvector 版本、权限实际生效。以下 C1–C11 就是为了补这一段。

复跑上述断言（零成本，只读文件）：

```bash
python - <<'PY'
import re,io
read=lambda p: io.open(p,encoding='utf-8').read()
c=read('docker-compose-eval.yml'); s=read('docker/postgres/eval-init.sql')
sh=read('docker/postgres/eval-user.sh'); j=read('app/src/test/java/interview/guide/eval/P1cRealRetrievalEvalTest.java')
g=read('app/build.gradle'); ok=lambda cond,msg: print(('PASS  ' if cond else 'FAIL  ')+msg)
U='f47ac10b-58cc-4372-a567-0e0283c5d9e7'
ok('127.0.0.1:${EVAL_POSTGRES_PORT:-5433}:5432' in c,'compose 回环绑定 5433')
ok('image: pgvector/pgvector:pg16' in c,'compose pgvector 镜像')
ok('container_name: interview-eval-postgres' in c,'compose 独立容器名')
ok(c.count(':?')>=2,'compose 两个密码为强制无默认')
ok('network_mode' not in c,'compose 无 host 网络')
ok('embedding vector(1024)' in s,'init 1024 维列')
ok('USING hnsw (embedding vector_cosine_ops)' in s,'init HNSW cosine')
ok(U in s and 'p1c-eval-isolated' in s,'init 标记常量')
ok(not re.search(r'\b(GRANT|CREATE ROLE|PASSWORD)\b',s),'init 不含用户/权限/口令语句')
ok('set -euo pipefail' in sh,'user.sh 严格模式')
ok('WHERE NOT EXISTS (SELECT FROM pg_roles' in sh,'user.sh 角色仅缺时创建(口令不轮换)')
ok('ALL PRIVILEGES' not in sh and not re.search(r'SUPERUSER|CREATEDB',sh,re.I),'user.sh 无权限放大')
ok('initializeSchema(false)' in j,'java initializeSchema=false')
ok('"eval_runner".equals(currentUser)' in j,'java 强制 current_user')
ok("excludeTags 'real-eval'" in g and "'false'" in g,"gradle 排除 real-eval 且 realApi 默认 false")
PY
```

## 1. 执行边界与通用停止条件

- **只允许**显式 `-f docker-compose-eval.yml`。在仓库根目录裸跑 `docker compose up/down` 会命中生产 `docker-compose.yml`（服务名 `postgres`、端口 5432、库 `interview_guide`），这正是要防的误用。
- 凭据只从本机 `.env.eval`（或环境变量）注入。本清单所有命令里的口令位置一律写成占位符 `<EVAL_RUNNER_PASSWORD>` / `<EVAL_DB_PASSWORD>`，**不得**把真实口令写进任何提交文件、脚本或命令行历史（`psql` 用 `PGPASSWORD` 环境变量或 `.pgpass`，不要 `-W` 明文拼接）。
- **不提供自动删除卷的步骤**。C6 的重建动作需要执行者先人工确认「该卷内没有需要保留的东西」，再手工执行；确认过程记入 §6。
- 通用停止条件：任一条目实测与预期不符 → **停下**，原样记录实际输出与判定失败的原因。不修改 `eval-init.sql` / `eval-user.sh` / 设计文档语义来「让检查通过」；不把 FAIL 改写成 PASS。
- 全程不发起任何 HTTP 到模型服务：本清单里唯一的向量字面量是手工拼的定长数组，不涉及 Embedding。
- **不要照抄设计文档里的口令字面量。** `P1C-L1-DESIGN.md` 的示例片段（第 143、416、577 行）仍写着 `eval_runner_2026`，但**已不符合现状**：实际交付的 `docker/postgres/eval-user.sh` 强制要求 `EVAL_RUNNER_PASSWORD` 且无默认值，`app/build.gradle` 也不传默认口令。若按文档示例设口令，等于把一个仓库里可见的弱口令用于今晚的实例。今晚请自行生成强随机值（如 `openssl rand -base64 24`）只写入 `.env.eval`。该文档片段的清理已作为 B7 记入进度文档，等复核后再改。

## 2. C1 — 宿主机端口未被占用

```bash
netstat -ano | grep ":5433" | grep LISTEN          # 期望：无输出
docker ps --format '{{.Names}} {{.Ports}}' | grep 5433   # 期望：无输出
```
预期：5433 空闲。失败停止：若有其他进程占用，**不要**改评测端口去凑合——记录 PID 与镜像/服务名，交回给用户决定；端口换值会连带修改 `eval.datasource.url` 与文档常量。

## 3. C2 — 容器与卷的归属（防误连生产栈）

```bash
docker ps -a --filter name=interview-eval-postgres --format '{{.Names}} {{.Status}} {{.Ports}}'
docker volume ls --filter name=eval_postgres_data
docker volume inspect --format '{{ index .Config.Labels "com.docker.compose.project.config_files"}}' <卷名>
```
预期：评测容器名恰为 `interview-eval-postgres`（生产是 `interview-postgres`，两者不得混淆）；卷的 `config_files` 标签指向 `docker-compose-eval.yml`。失败停止：若标签指向别的 compose 文件，说明卷属于其他栈，**立即停止**，不要 `down`、不要删卷。

## 4. C3 — Compose 配置渲染（不启动）

```bash
docker compose -f docker-compose-eval.yml --env-file .env.eval config --quiet; echo "exit=$?"
docker compose -f docker-compose-eval.yml --env-file .env.eval config | grep -B2 -A6 "5433"
```
预期：第一条 exit=0（两个 `:?` 变量都已提供；缺失时 compose 必须拒绝渲染——那正是期望的护栏，此时记录为「护栏生效」并停止去补 `.env.eval`）。第二条里出现 `published: "5433"`、`target: 5432`、`host_ip: 127.0.0.1`（`config` 输出是长格式，**不要**按 `127.0.0.1:5433:5432` 这个短语法去 grep，会误判）。

## 5. C4–C6 — 容器启动与初始化脚本是否真的执行过

```bash
# C4 启动并等待健康检查
docker compose -f docker-compose-eval.yml --env-file .env.eval up -d
docker compose -f docker-compose-eval.yml ps                     # 期望 Status 含 (healthy)
docker compose -f docker-compose-eval.yml exec -T eval-postgres pg_isready -U postgres   # 期望 exit 0

# C5 初始化脚本执行情况（关键：init 只在数据目录为空时执行）
docker compose -f docker-compose-eval.yml logs eval-postgres \
  | grep -E "initdb|docker-entrypoint-initdb.d|10-eval-schema|20-eval-user|database system is ready"
```
预期：日志出现 `/docker-entrypoint-initdb.d/10-eval-schema.sql` 与 `20-eval-user.sh` 的执行行。
判据分层：日志措辞随镜像版本可能变化，因此**权威判据是 C6 的对象存在性**（两张表 + 角色都在 = 初始化确实跑过）；日志只是定位线索，二者都要记进 §12。
失败停止：若日志显示跳过初始化（复用已有数据目录），说明卷是旧的 → 走 C6 判定；**不要**默认「表应该已经在了」就继续。

```bash
# C6 陈旧卷判定（容器内超级用户，只读；本容器内 postgres 走 local socket 免密）
docker compose -f docker-compose-eval.yml exec -T eval-postgres \
  psql -U postgres -d interview_guide_eval -v ON_ERROR_STOP=1 \
  -c "SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename IN ('vector_store','eval_instance_identity');" \
  -c "SELECT rolname FROM pg_roles WHERE rolname='eval_runner';"
```
预期：两张表都在 + `eval_runner` 存在。
失败停止：缺任一即证明初始化未执行。此时**先人工确认卷内确无可保留数据**，再由执行者手工运行 `docker compose -f docker-compose-eval.yml down -v` 与 `up -d`（本清单不代为自动执行），并在 §12 记下确认人与时间。
附加检查（`eval-user.sh` 的已知语义缺口）：`CREATE ROLE` 带 `WHERE NOT EXISTS`，因此**换了 `.env.eval` 里的 `EVAL_RUNNER_PASSWORD` 但复用旧卷时，角色口令仍是旧的**，脚本不会报错。判据：必须用当前 `.env.eval` 的口令实际登录成功（见 C7）才算通过，不能只看角色存在。

## 6. C7 — 数据库名与身份（必须以 eval_runner 连接，不能用超管）

```bash
PGPASSWORD='<EVAL_RUNNER_PASSWORD>' psql -h 127.0.0.1 -p 5433 -U eval_runner -d interview_guide_eval \
  -c "SELECT current_database(), current_user, version();" \
  -c "SELECT marker_key, marker_value FROM eval_instance_identity;" \
  -c "SELECT setting FROM pg_settings WHERE name='port';" \
  -c "SELECT count(*) FROM vector_store;"
```
预期（逐项）：
- `current_database()` = `interview_guide_eval`，且与 Java 侧 `eval.datasource.url` 的库名一致；
- `current_user` = `eval_runner`（**不是** `postgres`）；
- 标记表两行 = `instance_type | p1c-eval-isolated`、`instance_uuid | f47ac10b-58cc-4372-a567-0e0283c5d9e7`；
- `pg_settings.port` = `5432`（容器内部端口，宿主机映射才是 5433）；
- `vector_store` 行数 = 0。
失败停止：库名/用户/标记任一不符即终止——这意味着可能连到了非评测实例。注意固定 UUID 是仓库常量，只能排除「连到没跑过本初始化的实例」这一类误连，**不能单独证明物理容器身份**；须再用 `docker inspect` 的容器 ID 与 `docker compose ps` 交叉比对，两者一致才记 PASS。

## 7. C8 — 权限：区分「角色拥有权限」与「用 eval_runner 实际执行」

两个口径必须分开记，声明式通过不代表行为式通过（例如列级权限、属主、RLS、扩展函数 EXECUTE 都可能在声明之后拦住实际语句）。

```sql
-- (a) 声明式：以超级用户查询，只证明"角色被授予了什么"
SELECT rolname, rolsuper, rolcreatedb, rolcanlogin FROM pg_roles WHERE rolname='eval_runner';
SELECT has_database_privilege('eval_runner','interview_guide_eval','CONNECT') AS db_connect,
       has_schema_privilege('eval_runner','public','USAGE') AS schema_usage,
       has_table_privilege('eval_runner','vector_store','SELECT') AS vs_select,
       has_table_privilege('eval_runner','vector_store','INSERT') AS vs_insert,
       has_table_privilege('eval_runner','vector_store','UPDATE') AS vs_update,
       has_table_privilege('eval_runner','vector_store','DELETE') AS vs_delete,
       has_table_privilege('eval_runner','eval_instance_identity','SELECT') AS marker_select,
       has_table_privilege('eval_runner','vector_store','TRUNCATE') AS vs_truncate;
```
预期：`rolsuper=f`、`rolcreatedb=f`、`rolcanlogin=t`；前 7 项为 `t`；**`vs_truncate` 必须为 `f`**（Phase 5 清理靠 `DELETE`，不依赖 TRUNCATE）。

```sql
-- (b) 行为式负向：以 eval_runner 执行，期望"被拒绝"，被拒绝才是 PASS
INSERT INTO eval_instance_identity VALUES ('probe','x');   -- 期望 permission denied
CREATE TABLE public.probe_t (id int);                       -- 期望 permission denied（PG15+ public 不再默认授 CREATE）
TRUNCATE vector_store;                                      -- 期望 permission denied
```
失败停止：若任一条**成功**，说明权限被放大，立即停止并报告，不要继续写探针。特别注意 `CREATE TABLE` 若意外成功，会留下一张 `public.probe_t` —— **不要**用 `eval_runner` 去 DROP（那会把「权限放大」和「自行善后」两件事混在一起），只需在 §12 原样记录残留对象名，交由复核决定重建卷。

## 8. C9 — 事务内 INSERT/SELECT/UPDATE/DELETE 探针，回滚后全表仍为空

必须显式 `BEGIN … ROLLBACK`（`psql` 默认自动提交，不加 `BEGIN` 的探针会真的留下数据）。探针向量用 1024 维字面量，不经过 Embedding，零 API 成本。

```bash
PGPASSWORD='<EVAL_RUNNER_PASSWORD>' psql -h 127.0.0.1 -p 5433 -U eval_runner -d interview_guide_eval \
  -v ON_ERROR_STOP=1 -c "
BEGIN;
INSERT INTO vector_store (content, metadata, embedding)
VALUES ('__p1c_perm_probe__', '{\"probe\":\"p1c-c9\"}'::json,
        ('[' || repeat('0.1,', 1023) || '0.1]')::vector);
SELECT count(*) AS probe_rows, max(vector_dims(embedding)) AS probe_dims
  FROM vector_store WHERE content='__p1c_perm_probe__';
UPDATE vector_store SET content='__p1c_perm_probe_u__' WHERE content='__p1c_perm_probe__';
DELETE FROM vector_store WHERE content='__p1c_perm_probe_u__';
ROLLBACK;"
```
预期：事务内 `probe_rows=1`、`probe_dims=1024`，四条 DML 全部成功，整段 exit 0。这一步同时验证 `id` 默认 `gen_random_uuid()` 可用、`metadata` 是 `json` 而非 `jsonb`（PgVectorStore 期望的形态）、`vector_dims()` 在该镜像的 pgvector 版本里存在。

紧接其后的**回滚证据**（必须独立执行，不能放在同一事务里）：
```bash
PGPASSWORD='<EVAL_RUNNER_PASSWORD>' psql -h 127.0.0.1 -p 5433 -U eval_runner -d interview_guide_eval \
  -c "SELECT count(*) AS total_rows FROM vector_store;
      SELECT count(*) AS probe_leftovers FROM vector_store WHERE starts_with(content, '__p1c_perm_probe');"
```
预期：`total_rows=0` 且 `probe_leftovers=0`。（用 `starts_with()` 而不是 `LIKE '__p1c_perm_probe%'`：`_` 在 `LIKE` 里是单字符通配符，会把匹配范围放大到非探针行。）
失败停止：若残留 > 0（例如 `-c` 里的 `BEGIN` 被提前中断），**必须**按 `starts_with(content, '__p1c_perm_probe')` 精确删除并复查，直到回到 0；不得留下探针行进入后续评测——Phase 0 的「全表为空」硬性检查会因此把整轮拒掉，而这属于环境污染，不是隔离结论。删除只允许针对该 `content` 前缀，禁止无条件 `DELETE`/`TRUNCATE` 全表。

## 9. C10 — 1024 维列、HNSW `vector_cosine_ops` 索引、扩展版本

```sql
-- 以 eval_runner 执行（Java Phase 0 用的就是这三条，口径保持一致）
SELECT atttypmod FROM pg_attribute JOIN pg_class ON attrelid=oid
 WHERE relname='vector_store' AND attname='embedding';                 -- 期望 1024
SELECT indexname, indexdef FROM pg_indexes WHERE tablename='vector_store';
SELECT extversion FROM pg_extension WHERE extname='vector';            -- 记录实测版本号
```
预期：`atttypmod` = 1024（与 `eval.embedding.dimensions` 默认 1024 相等，Java 侧做的是整数相等判断）；`indexdef` 同时含 `using hnsw` 与 `vector_cosine_ops`，索引名 `spring_ai_vector_index`。
失败停止：维度或索引口径不符 → 记录 `indexdef` 原文，**不要**现场手建索引来凑过检查（那会让报告里的 `vectorStoreConfig.indexDef` 与真实初始化脱钩）。`extversion` 只需如实记录；若版本低到不提供 `vector_dims()`，C9 会先失败，按 C9 停止。

## 10. C11 — 收尾状态与记录

```bash
docker compose -f docker-compose-eval.yml ps            # 期望 (healthy)
PGPASSWORD='<EVAL_RUNNER_PASSWORD>' psql -h 127.0.0.1 -p 5433 -U eval_runner -d interview_guide_eval \
  -c "SELECT count(*) FROM vector_store;"                # 期望 0
docker inspect --format '{{.Config.Image}} {{.State.StartedAt}}' interview-eval-postgres   # 记录镜像（含 digest 更佳）
```
预期：`vector_store` 仍为 0 行（本清单全程不允许出现非探针写入，探针已回滚）。停止方式：`docker compose -f docker-compose-eval.yml stop`（保留卷，供复核）。**不要求、也不要**在本轮 `down -v`。

## 11. 验收判据

- C1–C11 全部 PASS，且每项在 §12 有实测值（不是「符合预期」这类转述）；
- C8 的声明式与行为式两个口径分别记录，负向三条确实被拒绝；
- C9 回滚后 `vector_store` 与探针残留都为 0；
- 全程未发起 Embedding/HTTP、未运行 `evalP1cReal`、未连 5432 生产库；
- 结果连同镜像版本、`extversion`、容器 ID 写回根目录 `PROJECT_PROGRESS.md`（把 §5 对应项从「未验证」移入「真实环境已验证」，附本节记录表）。
以上任一未满足，只能记为「部分验证」，并明确列出仍未覆盖的项。

## 12. 记录表（执行时填写）

```
C1  端口5433空闲     实测:__________  PASS/FAIL  时间:______
C2  容器名/卷归属     实测:__________  PASS/FAIL  时间:______
C3  compose 渲染      实测:__________  PASS/FAIL  时间:______
C4  启动+healthcheck  实测:__________  PASS/FAIL  时间:______
C5  init 脚本执行日志 实测:__________  PASS/FAIL  时间:______
C6  陈旧卷/角色口令   实测:__________  PASS/FAIL  人工确认人:______
C7  库名/用户/标记/内部端口/空表  实测:__________  PASS/FAIL  容器ID比对:______
C8a 声明式权限(含 truncate=f)     实测:__________  PASS/FAIL
C8b 行为式负向(3条被拒)           实测:__________  PASS/FAIL
C9  事务探针+回滚后 total=0       实测:__________  PASS/FAIL
C10 atttypmod/indexdef/extversion 实测:__________  PASS/FAIL
C11 收尾(空表/镜像/停止方式)       实测:__________  PASS/FAIL
未发起 Embedding/未跑 evalP1cReal  确认:______
```

## 13. 引用点

- 代码：`app/src/test/java/interview/guide/eval/P1cRealRetrievalEvalTest.java`（`verifyIdentity()` / Phase 0 维度与索引 SQL / Phase 5 清理用 `DELETE`）
- 配置：`docker-compose-eval.yml`、`docker/postgres/eval-init.sql`、`docker/postgres/eval-user.sh`、`app/build.gradle`（`evalP1cReal`）
- 设计：`P1C-L1-DESIGN.md` §3 隔离与身份核验、§7 Phase 0、§13 运行前检查清单、§16 定点修订表
- 进度：仓库根 `PROJECT_PROGRESS.md` §3 B5/B6、§5 执行边界
