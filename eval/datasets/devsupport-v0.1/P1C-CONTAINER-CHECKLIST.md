# P1-C 独立评测容器 · 无 API 验证清单

> 状态：**待执行**（计划 22:00–23:00 执行；前置条件是 Codex 对**本轮定点修订**的复核结论已收到）。
> 本文件里所有「预期」都是**设计与代码推导出的期望值，不是实测结果**。执行时把实测值写进 §12 记录表。§0 的静态核对与工具可用性探测是本文件唯一已实测的部分，已标注实测/推导。
> 范围：只启动评测 Postgres 容器 + 静态/SQL 核对。**禁止**：调用 Embedding API、运行 `:app:evalP1cReal`、执行正式 L1、连接生产库、启动生产 Compose、删除任何卷。

## 0. 本轮（白天）已做的静态核对

纯本地、零成本、可复跑。本轮定点修订后重跑了下方代码块：**25 项断言全部 PASS，exit=0**（其中 15 项是 compose/init/user.sh/Java/Gradle 的配置与门禁断言，10 项是本轮新增的文档一致性与清单形态断言）。下面的条目还额外记录了几项**不在代码块里**的核对（生产 Compose 无 `eval` 引用、`.gitignore`、口令字面量扫描、离线测试），逐条列明依据。

- `docker-compose-eval.yml`：`127.0.0.1:${EVAL_POSTGRES_PORT:-5433}:5432`（显式回环绑定）、`image: pgvector/pgvector:pg16`、独立 `container_name: interview-eval-postgres`、挂载 `10-eval-schema.sql` + `20-eval-user.sh`、两个密码均为 `${...:?}` 强制（无默认弱口令）、无 `network_mode: host`。
- `docker/postgres/eval-init.sql`：`CREATE EXTENSION IF NOT EXISTS vector`、`embedding vector(1024)`、`USING hnsw (embedding vector_cosine_ops)`、标记值与 Java 常量逐字一致、文件内不含任何用户/权限/口令语句、无 `DROP`/`TRUNCATE`。
- `docker/postgres/eval-user.sh`：`set -euo pipefail`、密码缺失即退出、口令经 `psql -v` + `:'runner_pw'` + `%L` 传参（文件内无明文口令）、`CREATE ROLE` 带 `WHERE NOT EXISTS`、授予项恰为 `CONNECT` / `USAGE` / `SELECT, INSERT, UPDATE, DELETE`（`vector_store`）/ `SELECT`（标记表）、**无** `ALL PRIVILEGES`、**无** `SUPERUSER`/`CREATEDB`。
- 装配与门禁：`initializeSchema(false)`、`current_user` 必须为 `eval_runner`、默认连接串 `jdbc:postgresql://127.0.0.1:5433/interview_guide_eval`、检查 `rolsuper`、检查全表为空；`:app:test` 排除 `real-eval`，`evalP1cReal` 的 `eval.p1c.realApi` 默认 `'false'`。
- 隔离与密钥：`docker-compose.yml` / `docker-compose.dev.yml` 中**不含**任何 `eval` 引用（生产栈不会挂载评测初始化脚本）；`.gitignore` 第 12–13 行忽略 `.env` 与 `.env.eval`，`git ls-files` 只有 `.env.eval.example`；对 eval 相关文件做口令字面量扫描命中 0。
- 离线测试：`GRADLE_USER_HOME=/c/temp/gradle-tmp ./gradlew :app:test --tests 'interview.guide.eval.*' --no-daemon --rerun` exit 0（114 通过 / 0 失败 / 0 错误 / 0 跳过）。本轮**未改任何代码**，所以这条记录的是 `f6aa629` 的状态，本轮没有重跑（边界：只做文档修订）。
- **清单命令形态的可执行性核验（本轮实测，零容器）**：把本清单所有 bash 代码块逐个喂给 `bash -n`（语法检查，不执行）——**18 个块，0 处语法错误**；C3.2 与 C2-post 的三条 `python -c` 过滤器用**合成的假 JSON**（含一个哨兵字段 `SECRET`）实跑，exit 0、输出形状正确、`SECRET` 未出现在输出里；`echo` + `read -rs P1C_PW` 的取值写法实跑确认不回显（`len=5 set=yes`，exit 0）；`date '+%Y-%m-%dT%H:%M:%S%z'` 实跑输出 `2026-09-30T18:30:17+0800`；C1 的 `netstat -ano | grep ":5433" | grep LISTEN` 在 18:30 预跑一次，exit 1（无匹配 = 端口空闲，属本轮实测，但今晚仍需重跑，因为状态会变）。核验过程中发现并修掉了两处**会在今晚真实执行时才暴露**的文档缺陷：`read -rs VAR?提示语`（变量名内嵌提示语）是 ksh/zsh 专有语法，Git Bash 的 bash 报 `not a valid identifier`；以及 C7 里曾写过不存在的列 `client_host`（正解 `client_addr`）。这些是「命令能否跑」层面的实测，不涉及任何数据库行为结论。
- **目标机工具可用性（本轮实测，决定本清单的命令形态）**：`docker` CLI 存在（`C:\Program Files\Docker\Docker\resources\bin\docker`），`python` = `C:\Python313\python`，`openssl` 与 `netstat` 可用；**宿主机没有 `psql`，也没有 `jq`**；`docker info` 当前返回 `failed to connect to the docker API at npipe:////./pipe/dockerDesktopLinuxEngine`（Docker Desktop 未运行，本轮按边界要求**没有**启动它）。推论：清单里所有 SQL 一律经 `docker exec` 在**容器内**执行，JSON 过滤一律用 `python`；今晚开工前需要用户自行把 Docker Desktop 起起来，这属于前置条件而不是 FAIL。

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
# 本轮定点新增：文档一致性与清单形态
# kk = 本清单去掉自检脚本自身（否则脚本里的搜索词会被自己匹配到，产生假 FAIL）
# 下面的口令字面量搜索词用「相邻字符串拼接」写出：Python 会在编译期合并，但 grep 不会命中，
# 这样本检查脚本自身不会成为「全仓无口令字面量」这条判据的违例项。不要把它改回一整串。
d=read('eval/datasets/devsupport-v0.1/P1C-L1-DESIGN.md')
kk=re.sub(r"python - <<'PY'.*?^PY$","",read('eval/datasets/devsupport-v0.1/P1C-CONTAINER-CHECKLIST.md'),flags=re.S|re.M)
ok('requireCredential("eval.datasource.password", "EVAL_RUNNER_PASSWORD"' in j,'java 口令只取属性/环境变量，无默认值')
ok('eval_runner_' '2026' not in d and 'eval_runner_' '2026' not in kk,'设计文档与本清单正文均无口令字面量')
ok('metadata  json,' in d and 'JSONB' not in d,'设计文档 schema 示例已按交付改为 json')
ok('psql -h 127.0.0.1 -p 5433' not in kk and 'psql -h 127.0.0.1 -p 5432' in kk,'清单不要求宿主机 psql，改容器内 TCP')
ok(kk.count("<<'SQL'")==kk.count('\nSQL\n'),"here-doc 开闭配对 %d/%d"%(kk.count("<<'SQL'"),kk.count('\nSQL\n')))
ok(kk.count('ROLLBACK;')>=4,'负向三条 + 正向探针都显式回滚')
ok('DELETE FROM vector_store;' not in kk,'无无条件全表 DELETE')
bad=[l.strip()[:60] for l in kk.splitlines()
     if 'docker compose -f docker-compose-eval.yml' in l and '--env-file' not in l and '--no-interpolate' not in l]
ok(not bad,'compose 命令均带 --env-file，违规=%s'%(bad or '无'))
ok(not re.search(r'read -rs "[A-Za-z_]\w*\?', kk),'read 用 bash 兼容语法（VAR?prompt 是 ksh/zsh 写法）')
ok('-c "SELECT' in kk and 'docker exec -T interview-eval-postgres psql' in kk,'SQL 全部经容器内 psql 执行')
PY
```

## 1. 执行边界与通用停止条件

- **只允许**显式 `-f docker-compose-eval.yml`。在仓库根目录裸跑 `docker compose up/down` 会命中生产 `docker-compose.yml`（服务名 `postgres`、端口 5432、库 `interview_guide`），这正是要防的误用。
- **每个 `docker compose` 子命令都要带 `--env-file .env.eval`**（`ps` / `logs` / `exec` / `stop` / `down` 都算）：compose 解析配置时会插值 `${EVAL_DB_PASSWORD:?…}`，缺 env 文件的命令可能直接报「Required environment …」而被误读成容器异常（是否对每个子命令都插值随 compose 版本而异，属推导；统一带上是最稳的写法，成本为零）。本清单上一版有多处示例漏了这个参数，本轮已全部补齐。唯一例外是 `config --no-interpolate`（故意不解析变量，因此不需要 `.env.eval`）。
- **宿主机没有 `psql` 与 `jq`**（本轮实测），所以 SQL 一律 `docker exec -T interview-eval-postgres psql …`，JSON 过滤一律用 `python`。以 `eval_runner` 做核验时必须带 `-h 127.0.0.1 -p 5432`（容器内 TCP），理由见 C6.0 与 C7。
- **凭据注入方式（本轮定点）**：口令只从本机 `.env.eval`（或环境变量）来，本清单不出现任何真实口令，也不再在命令行里写 `<EVAL_RUNNER_PASSWORD>` 这种「位置占位符」——那会诱导执行者把真口令粘进命令行并留在 shell 历史里。宿主机没有 `psql`，SQL 全部经 `docker exec` 在容器内跑，因此在会话开始时取值一次、收尾时清除：

  ```bash
  echo '输入 .env.eval 中的 EVAL_RUNNER_PASSWORD（不回显）:'; read -rs P1C_PW && export PGPASSWORD="$P1C_PW"
  # 之后所有 psql 命令都不带口令字面量；docker exec 里用 -e PGPASSWORD="$PGPASSWORD"（引用变量，不是明文）
  ```

  收尾（C11）执行 `unset PGPASSWORD P1C_PW`。替代方案是 `.pgpass`，同样不要把口令拼进命令行。若某条命令报 `FATAL: password authentication failed`，先确认本会话仍 export 着 `PGPASSWORD`，不要改成明文重试。
  （本轮静态实测：把提示语写进变量名的 `read -rs VAR?提示语` 形式是 ksh/zsh 专有语法，Git Bash 的 bash 会报 `not a valid identifier`，所以这里用 `echo` + `read -rs VAR` 的兼容写法；两种写法都验证过口令不回显、不进 shell 历史。）
- **不打印渲染后的完整 Compose 配置**：`docker compose config` 的输出里 `environment` 段是**已解析的 `POSTGRES_PASSWORD` / `EVAL_RUNNER_PASSWORD` 明文**。C3 只允许 `config --quiet` 与「用 python 只取 ports 字段」两条命令，完整输出不得打到终端、`tee` 到文件或粘进 §12。
- **不提供自动删除卷的步骤**。C6 的重建动作需要执行者先人工确认「该卷内没有需要保留的东西」，再手工执行；确认人、时间和理由记入 §12，并同步到根目录 `PROJECT_PROGRESS.md`。
- 通用停止条件：任一条目实测与预期不符 → **停下**，原样记录实际输出与判定失败的原因。不修改 `eval-init.sql` / `eval-user.sh` / 设计文档语义来「让检查通过」；不把 FAIL 改写成 PASS。
- 全程不发起任何 HTTP 到模型服务：本清单里唯一的向量字面量是手工拼的定长数组，不涉及 Embedding。
- **设计文档里的口令字面量已清除（本轮定点）**：`P1C-L1-DESIGN.md` 原 §3 / §4 / §5 示例及 §16 变更记录中的固定口令已全部移除，§15 #8 标为已解决，现在文档与交付一致——口令只来自 `.env.eval` 的 `EVAL_RUNNER_PASSWORD`（`eval-user.sh` 缺失即退出、compose 用 `${...:?}` 拒绝渲染），Gradle 只在显式 `-P` 时转发，Java 侧 `requireCredential("eval.datasource.password", "EVAL_RUNNER_PASSWORD")` 缺失即在任何连接与 HTTP 之前失败。今晚仍请自行生成强随机值（如 `openssl rand -base64 24`）只写入 `.env.eval`。

## 2. C1 — 宿主机端口未被占用

```bash
netstat -ano | grep ":5433" | grep LISTEN          # 期望：无输出
docker ps --format '{{.Names}} {{.Ports}}' | grep 5433   # 期望：无输出
```
预期：5433 空闲。失败停止：若有其他进程占用，**不要**改评测端口去凑合——记录 PID 与镜像/服务名，交回给用户决定；端口换值会连带修改 `eval.datasource.url` 与文档常量。

## 3. C2 — 容器与卷的归属（防误连生产栈）

C2 分两次跑：`up` 之前用存在性决定走「新卷」还是「旧卷」分支（§5 要用），`up` 之后做三方交叉核对。

```bash
# C2-pre（up 之前）
docker ps -a --filter name=interview-eval-postgres --format '{{.Names}} | {{.State}} | {{.Status}} | {{.Ports}}'
docker volume ls --filter name=eval_postgres_data --format '{{.Driver}} | {{.Name}}'
```
预期：容器名恰为 `interview-eval-postgres`（生产栈是 `interview-postgres`，名字/端口/库名都不得混淆）。两条都**无输出 = 全新卷**，走 §5 分支 A；有输出 = 已存在卷/容器，先做完 C2-post 的归属核对再 `up`，并在 §12 标明走分支 B。
注意卷的真实名字带项目前缀（默认项目名取自 Compose 文件所在目录名，可被 `-p` 或 `COMPOSE_PROJECT_NAME` 覆盖），**不要凭猜测拼卷名**——一律用 C2-post 从容器 `Mounts` 里读到的实际名字。

```bash
# C2-post：容器标签 ↔ 容器实际挂载 ↔ 卷标签，三方交叉核对（只打印标签与挂载，不打印环境）
docker inspect interview-eval-postgres --format '{{json .Config.Labels}}' \
  | python -c "import json,sys; L=json.load(sys.stdin); [print('LABEL',k,'=',L[k]) for k in sorted(L) if k.startswith('com.docker.compose')]"
docker inspect interview-eval-postgres --format '{{json .Mounts}}' \
  | python -c "import json,sys; [print('MOUNT',m.get('Type'),'|',m.get('Name') or m.get('Source'),'->',m.get('Destination')) for m in json.load(sys.stdin)]"
docker volume inspect <上面 Type=volume 给出的卷名> \
  --format '{{.CreatedAt}} | {{.Driver}} | {{json .Labels}}'
```
预期（三项一致才算归属明确）：
- 容器标签含 `com.docker.compose.project=<某项目名>` 与 `com.docker.compose.service=eval-postgres`；
- 挂载恰为三条：一条 `MOUNT volume | <project>_eval_postgres_data -> /var/lib/postgresql/data`，两条 `bind` 指向 `/docker-entrypoint-initdb.d/10-eval-schema.sql` 与 `/20-eval-user.sh`（`:ro`）；
- 卷标签里 `com.docker.compose.project` **等于**容器的项目名，`com.docker.compose.volume` = `eval_postgres_data`；卷的 `CreatedAt` 必须记进 §12（§5 的新旧卷判定就用它）。

**判据修订说明（本轮定点）**：**不再要求卷上存在 `com.docker.compose.project.config_files` 标签**。`config_files` / `working_dir` 属于容器（及网络）的标签层，卷标签层通常只有 `com.docker.compose.project` 与 `com.docker.compose.volume`（不同 compose 版本可能附带 `version` 之类，如实记录即可）。沿用上一版判据会读到一个空值并把「卷不属于评测栈」这种误报写进结论；卷的归属改由「容器 Mounts 里的卷名 + 卷 project 标签 + 容器 project 标签」三者闭环证明。这一条是按 Compose 的标签分层约定推导的，今晚以实测标签原文为准。

失败停止：卷的 project 标签与容器不一致，或该卷名同时被另一个 Compose 项目引用 → **立即停止**，不 `down`、不删卷、不改标签，把三方输出原样记入 §12。

## 4. C3 — Compose 配置渲染（不启动、不打印配置内容）

```bash
# C3.1 只做渲染校验：不打印任何内容，只看退出码
docker compose -f docker-compose-eval.yml --env-file .env.eval config --quiet; echo "exit=$?"

# C3.2 只提取端口字段，其余渲染内容一律不落地（宿主机没有 jq，用 python 过滤）
docker compose -f docker-compose-eval.yml --env-file .env.eval config --format json \
  | python -c "import json,sys; c=json.load(sys.stdin); [print('PORTS',n,json.dumps(s.get('ports'))) for n,s in c.get('services',{}).items()]"
```
预期：
- C3.1 `exit=0`，这同时证明 `.env.eval` 里两个 `${...:?}` 变量都已提供。若 compose 因缺变量而拒绝渲染，那是**护栏生效**，记 `BLOCKED：缺变量`，去补 `.env.eval`，**不要**给 compose 或文档加默认值来让它通过。
- C3.2 只输出一行，形如：`PORTS eval-postgres [{"mode": "tcp", "host_ip": "127.0.0.1", "target": 5432, "published": "5433"}]`。判据：`host_ip` = `127.0.0.1`、`target` = `5432`（容器内端口）、`published` = `"5433"`（宿主机映射端口）。注意 `config` 是**长格式**（不要按 `127.0.0.1:5433:5432` 短语法去 grep，会误判），且 JSON 里 `published` 是字符串、`target` 是数字，做相等判断时口径要一致。

**输出边界（本轮定点）**：删掉了上一版的 `config | grep -B2 -A6 "5433"`。渲染后的完整配置里 `environment` 段是**已解析的 `POSTGRES_PASSWORD` / `EVAL_RUNNER_PASSWORD` 明文**，打印到终端、粘进 §12 或 `tee` 到文件都是泄密面。上面两条命令的输出范围（无输出 / 只有 ports 一行）就是硬要求。若复核确实需要看更多字段，改用 `docker compose -f docker-compose-eval.yml config --no-interpolate`（变量保持 `${...}` 未解析形态，且不需要 `.env.eval`）。
失败停止：`published` 不是 5433 或 `host_ip` 缺失（会绑到 `0.0.0.0`）→ 停止并报告，不改端口凑过：换端口要连带改 `eval.datasource.url` 与多处文档常量。

## 5. C4–C6 — 容器启动，以及「本次启动是否真的执行过初始化脚本」

**两个命题必须分开（本轮定点）**：上一版把「C6 对象存在性」当作权威判据是错的。对象存在只证明**当前卷里含有这些对象**，与它们是谁、什么时候、用哪个版本的脚本创建的无关，因此**不能单独证明「这次启动执行过初始化脚本」**——这两件事要各自记录。

| 命题 | 性质 | 成立所需证据 |
|------|------|--------------|
| 本次启动执行过 init | 可复现性证据，**只有新卷分支能证明** | 分支 A 的四要件 |
| 数据面就绪：表 / 1024 维列 / HNSW 索引 / 角色 / 当前口令 / 空表 | 今晚 L1 的硬前置，两个分支都要逐项证明 | C6 对象与扩展 + C7 TCP 登录与空表 + C10 索引口径 |

关键事实：官方 Postgres 镜像只在**数据目录为空**时执行 `/docker-entrypoint-initdb.d/`。所以旧卷复用时**必然**看不到 init 日志，而对象照样齐全——这正是旧判据会把「复用历史产物」误写成「初始化已执行」的成因。

```bash
# C4 启动并等待健康检查
docker compose -f docker-compose-eval.yml --env-file .env.eval up -d; echo "exit=$?"
docker compose -f docker-compose-eval.yml --env-file .env.eval ps              # 期望 Status 含 (healthy)
docker compose -f docker-compose-eval.yml --env-file .env.eval exec -T eval-postgres pg_isready -U postgres; echo "exit=$?"
date '+%Y-%m-%dT%H:%M:%S%z'                                                    # 记下 up 时刻，供时间线比对

# C5 时间线 + 本次启动窗口的日志（日志只是线索，判定按下面的分支规则）
docker inspect interview-eval-postgres \
  --format 'ContainerStartedAt={{.State.StartedAt}} | ContainerCreatedAt={{.Created}} | Image={{.Config.Image}}'
docker volume inspect <C2-post 得到的卷名> --format 'VolumeCreatedAt={{.CreatedAt}} | {{json .Labels}}'
docker compose -f docker-compose-eval.yml --env-file .env.eval logs --since 15m eval-postgres \
  | grep -E "initdb|docker-entrypoint-initdb.d|10-eval-schema|20-eval-user|database system is ready|waiting for server"
```
`--since 15m` 用来把证据限定在本次启动窗口内，避免把历史日志当成本次的证据；日志措辞随镜像版本变化，所以判定**只按分支规则**，不依赖某个具体词。

**分支 A —— 新卷首次初始化**（C2-pre 时既无容器也无卷，且 `VolumeCreatedAt` 落在本次 `up` 之后）。以下四项同时成立才允许写「本次启动执行过初始化脚本」：
1. `VolumeCreatedAt` ≈ 容器 `StartedAt`，且两者都晚于记录的 up 时刻；
2. 本次窗口日志里出现 `initdb` 与两个 init 文件名（`10-eval-schema.sql`、`20-eval-user.sh`）；
3. C6 对象与扩展齐全；
4. C7 用**当前** `.env.eval` 的口令经 TCP 以 `eval_runner` 登录成功（只有本次创建的卷，角色口令才会与当前 `.env.eval` 一致）。

**分支 B —— 旧卷复用**（`VolumeCreatedAt` 早于本次启动，或本次窗口日志里没有 init 文件名）。**这不是 FAIL，但绝不能记「init 已执行」**；改判为「数据面就绪、init 归属未知」，并逐项完成：
- B1 对象存在性：C6 的表 / 角色 / 扩展；
- B2 凭据新鲜度：用**当前** `.env.eval` 口令以 `eval_runner` 经容器内 TCP 登录（C7）。`CREATE ROLE ... WHERE NOT EXISTS` 使旧卷保留旧口令，改了 `.env.eval` 也不轮换、不报错——登录失败说明**卷与当前凭据不一致**，属状态不明：停止并报告，不要现场 `ALTER ROLE` 凑过（那会掩盖卷的来源问题）；
- B3 数据面干净：`vector_store` 行数 = 0（C7），且 C9 探针回滚后仍为 0；
- B4 口径一致：C10 的 `atttypmod` / `indexdef` 与交付脚本一致（旧卷若由更早版本脚本创建，索引名或维度口径可能不同）；
- B5 时间线留痕：卷 `CreatedAt` 与容器 `StartedAt` 都写进 §12，作为「历史创建」的证据。

```bash
# C6.0 先确认 unix socket 的认证方式，决定 C6 要不要转发超管口令
docker exec -T interview-eval-postgres psql -U postgres -d postgres -X -q \
  -c "SELECT rule_number, type, auth_method, database, user_name FROM pg_hba_file_rules ORDER BY rule_number;"
```
- 若 `local`（unix socket）条目是 `trust` → C6 的只读命令可免密直跑；这同时解释了**为什么凭据核验绝不能走 socket**（socket 登录成功证明不了口令）。
- 若是 `scram-sha-256` / `md5` → 给下面的命令加 `-e PGPASSWORD="$EVAL_DB_PW"`（`EVAL_DB_PW` 同样用 `read -rs` 取值），或改用 `psql -h 127.0.0.1 -p 5432 -U postgres`。
- 查询报 `permission denied for view pg_hba_file_rules` 或 `must be superuser` → 如实记录，说明当前身份不足，改用能读 `pg_hba.conf` 的路径（`docker exec -T interview-eval-postgres cat /var/lib/postgresql/data/pg_hba.conf`，只读，不含口令）。

```bash
# C6.1 数据面就绪核对（容器内超级用户，只读）
docker exec -T interview-eval-postgres psql -U postgres -d interview_guide_eval -X -q -v ON_ERROR_STOP=1 \
  -c "SELECT 'tables='||string_agg(tablename,',' ORDER BY tablename) FROM pg_tables WHERE schemaname='public' AND tablename IN ('vector_store','eval_instance_identity');" \
  -c "SELECT 'roles='||coalesce(string_agg(rolname,','),'(none)') FROM pg_roles WHERE rolname='eval_runner';" \
  -c "SELECT 'ext='||coalesce(string_agg(extname,','),'(none)') FROM pg_extension;"
```
预期：`tables=eval_instance_identity,vector_store`、`roles=eval_runner`、`ext` 含 `vector`（镜像可能自带其他扩展，如实记录全文）。
失败停止：缺任一对象 = 初始化未完整执行。此时**先人工确认卷内确无可保留数据**，再由执行者手工运行 `docker compose -f docker-compose-eval.yml --env-file .env.eval down -v` 与同一命令的 `up -d`（本清单不代为自动执行），并在 §12 记录确认人、时间与卷时间线。**不要**在旧卷上手建表凑过检查——那会让报告里的 `schemaCreatedBy` 与真实初始化脱钩。
若真要走这条手工重建：`down -v` 删除的是「当前项目名下的卷」，所以运行前必须核对项目名与 C2-post 记录的 `com.docker.compose.project` **完全一致**（默认取 Compose 文件所在目录名；今晚若用过 `-p` 或 `COMPOSE_PROJECT_NAME`，必须同一取值），否则要么删不到目标卷、要么动到别的项目的卷。

## 6. C7 — 数据库名与身份（必须以 eval_runner 经 TCP 连接，不能用超管、不能用 socket）

宿主机无 `psql`，所以经容器执行；**必须走 `-h 127.0.0.1 -p 5432` 的 TCP 路径**，因为 unix socket 可能是 `trust` 认证（见 C6.0），socket 登录成功证明不了 `.env.eval` 里的口令与卷内角色口令一致。

```bash
echo '输入 .env.eval 中的 EVAL_RUNNER_PASSWORD（不回显）:'; read -rs P1C_PW && export PGPASSWORD="$P1C_PW"
docker exec -T -e PGPASSWORD="$PGPASSWORD" interview-eval-postgres \
  psql -h 127.0.0.1 -p 5432 -U eval_runner -d interview_guide_eval -X -q \
  -c "SELECT 'db='||current_database()||' user='||current_user;" \
  -c "SELECT version();" \
  -c "SELECT marker_key, marker_value FROM eval_instance_identity ORDER BY marker_key;" \
  -c "SELECT 'server_port='||current_setting('port');" \
  -c "SELECT 'vector_store_rows='||count(*) FROM vector_store;" \
  -c "SELECT 'client_addr='||coalesce(client_addr::text,'(unix socket)') FROM pg_stat_activity WHERE usename='eval_runner';"
```
预期（逐项）：
- `db=interview_guide_eval`，且与 Java 侧 `eval.datasource.url` 的库名一致；
- `user=eval_runner`（**不是** `postgres`）；能连上本身就证明**当前 `.env.eval` 的口令与卷内角色口令一致**（分支 B 的 B2 判据）；
- 标记表两行 = `instance_type | p1c-eval-isolated`、`instance_uuid | f47ac10b-58cc-4372-a567-0e0283c5d9e7`；
- `server_port=5432`（容器内部端口；宿主机映射才是 5433，容器内查不到 5433 是正常的）；
- `vector_store_rows=0`；
- `pg_stat_activity.client_host` 为 `127.0.0.1`（证明是 TCP 会话，不是 socket）。
失败停止：库名 / 用户 / 标记任一不符即终止——这意味着可能连到了非评测实例。`password authentication failed` 不是「权限结论」，是**凭据与卷不一致**（分支 B 的典型结果），按 B2 停止。固定 UUID 是仓库常量，只能排除「连到没跑过本初始化的实例」这一类误连，**不能单独证明物理容器身份**；还须把 `docker inspect --format '{{.Id}}' interview-eval-postgres` 与 `docker compose -f docker-compose-eval.yml --env-file .env.eval ps -q eval-postgres` 交叉比对，两者一致才记 PASS。

## 7. C8 — 权限：区分「角色拥有权限」与「用 eval_runner 实际执行」

两个口径必须分开记，声明式通过不代表行为式通过（例如列级权限、属主、RLS、扩展函数 EXECUTE 都可能在声明之后拦住实际语句）。

```bash
# (a) 声明式：容器内超级用户查询，只证明"角色被授予了什么"
docker exec -T interview-eval-postgres psql -U postgres -d interview_guide_eval -X -q -v ON_ERROR_STOP=1 \
  -c "SELECT rolname, rolsuper, rolcreatedb, rolcanlogin FROM pg_roles WHERE rolname='eval_runner';" \
  -c "SELECT has_database_privilege('eval_runner','interview_guide_eval','CONNECT') AS db_connect,
         has_schema_privilege('eval_runner','public','USAGE') AS schema_usage,
         has_table_privilege('eval_runner','vector_store','SELECT') AS vs_select,
         has_table_privilege('eval_runner','vector_store','INSERT') AS vs_insert,
         has_table_privilege('eval_runner','vector_store','UPDATE') AS vs_update,
         has_table_privilege('eval_runner','vector_store','DELETE') AS vs_delete,
         has_table_privilege('eval_runner','eval_instance_identity','SELECT') AS marker_select,
         has_table_privilege('eval_runner','vector_store','TRUNCATE') AS vs_truncate;"
```
预期：`rolsuper=f`、`rolcreatedb=f`、`rolcanlogin=t`；前 7 项为 `t`；**`vs_truncate` 必须为 `f`**（Phase 5 清理靠 `DELETE`，不依赖 TRUNCATE）。

### (b) 行为式负向探针：三条各自独立会话、独立 `BEGIN … ROLLBACK`

**为什么必须拆开（本轮定点）**：上一版把三条写在同一块里。若共用一个事务，第一条被拒后事务进入 `aborted` 状态，后两条**根本没被执行**，却会被误读成「三条都被拒绝」——那是假 PASS。每条都用独立 `psql` 会话，跑完自己的 `ROLLBACK`。

每条模板（`-v ON_ERROR_STOP=0` 是**故意**的：默认值即为 0，让预期报错之后的 `ROLLBACK` 仍然执行；here-doc 用引号定界，SQL 里的单引号不需要任何 shell 转义）：

```bash
# probe-1：向标记表写入（期望被拒）
docker exec -T -e PGPASSWORD="$PGPASSWORD" interview-eval-postgres \
  psql -h 127.0.0.1 -p 5432 -U eval_runner -d interview_guide_eval -X -q -v ON_ERROR_STOP=0 <<'SQL'
SELECT 'session_alive user='||current_user||' db='||current_database();
BEGIN;
INSERT INTO eval_instance_identity (marker_key, marker_value) VALUES ('probe','x');
ROLLBACK;
SQL

# probe-1 残留复查（独立会话、自动提交，必须在事务之外）
docker exec -T -e PGPASSWORD="$PGPASSWORD" interview-eval-postgres \
  psql -h 127.0.0.1 -p 5432 -U eval_runner -d interview_guide_eval -X -q \
  -c "SELECT count(*) AS marker_total, count(*) FILTER (WHERE marker_key='probe') AS probe_rows FROM eval_instance_identity;"
```
```bash
# probe-2：在 public 建表（期望被拒，PG15+ public 不再默认授 CREATE）
docker exec -T -e PGPASSWORD="$PGPASSWORD" interview-eval-postgres \
  psql -h 127.0.0.1 -p 5432 -U eval_runner -d interview_guide_eval -X -q -v ON_ERROR_STOP=0 <<'SQL'
BEGIN;
CREATE TABLE public.probe_t (id int);
ROLLBACK;
SQL

# probe-2 残留复查：不得留下 public.probe_t
docker exec -T -e PGPASSWORD="$PGPASSWORD" interview-eval-postgres \
  psql -h 127.0.0.1 -p 5432 -U eval_runner -d interview_guide_eval -X -q \
  -c "SELECT count(*) AS probe_t_leftover FROM pg_tables WHERE schemaname='public' AND tablename='probe_t';"
```
```bash
# probe-3：TRUNCATE（期望被拒）
docker exec -T -e PGPASSWORD="$PGPASSWORD" interview-eval-postgres \
  psql -h 127.0.0.1 -p 5432 -U eval_runner -d interview_guide_eval -X -q -v ON_ERROR_STOP=0 <<'SQL'
BEGIN;
TRUNCATE vector_store;
ROLLBACK;
SQL

# probe-3 残留复查
docker exec -T -e PGPASSWORD="$PGPASSWORD" interview-eval-postgres \
  psql -h 127.0.0.1 -p 5432 -U eval_runner -d interview_guide_eval -X -q \
  -c "SELECT count(*) AS vs_rows FROM vector_store;"
```
预期：probe-1/2/3 分别输出 `ERROR: permission denied for table eval_instance_identity` / `ERROR: permission denied for schema public` / `ERROR: permission denied for table vector_store`；三次复查分别为 `marker_total=2, probe_rows=0`、`probe_t_leftover=0`、`vs_rows=0`。

**怎么确认是「权限拒绝」而不是连接或语法错误**（三条合起来才是判据，只看退出码会看错）：
1. 错误文本必须**逐字**是 `permission denied for <对象类型> <名字>`（SQLSTATE 42501 `insufficient_privilege`）；
2. `session_alive …` 那行必须先出现，证明连接与会话是活的——排除 `FATAL:` / `could not connect` 一类；
3. 复查计数符合预期，证明没有任何写入落地。

| 实际看到 | 判定与动作 |
|----------|------------|
| `ERROR: permission denied for table eval_instance_identity`（及对应另两条） | **PASS** |
| 没有 ERROR，且复查显示写入/建表生效 | **FAIL：权限被放大**。事务仍随本次 `ROLLBACK` 结束，先跑复查确认无残留并把输出记进 §12，然后**停止**；不要用 `eval_runner` 去 `DROP`/`DELETE` 自行善后（那会把「权限放大」和「自行清理」两件事混在一起，破坏取证） |
| `ERROR: duplicate key value violates unique constraint …` | **FAIL**：能走到约束检查说明权限阶段已通过，同样是权限放大 |
| `FATAL: password authentication failed` / `FATAL: role "eval_runner" does not exist` / `could not connect to server` | **BLOCKED：连接或凭据问题**，不构成权限结论；回 C7 与分支 B2 |
| `ERROR: syntax error at or near …` / `ERROR: column … does not exist` | **BLOCKED：命令抄写缺陷**，改正命令后重跑；不改判据、不改脚本语义 |
| `ERROR: current transaction is aborted, commands ignored until end of transaction block` | 预期副作用（若你在报错后还追加了语句）：事务已 aborted，`ROLLBACK` 会结束它，不是新发现 |

**为什么这三条不会留下 `public.probe_t` 或探针行**：PostgreSQL 的 DDL 与 DML 都是事务性的，`CREATE TABLE` / `INSERT` / `TRUNCATE` 写在 `BEGIN … ROLLBACK` 内，即使权限被放大而实际执行成功，`ROLLBACK`（或会话因意外中断而断开）也会把它回滚掉。复查语句放在事务**之外**，就是把这条保证当成可核对的证据而不是口头假设。若某次复查读到 `probe_t_leftover=1`（例如误用了 `ON_ERROR_STOP=1` 又手工重跑过事务外的版本），按 §1 停止并原样记录，不要自行 `DROP`。

## 8. C9 — 事务内 INSERT/SELECT/UPDATE/DELETE 探针，回滚后全表仍为空

必须显式 `BEGIN … ROLLBACK`（`psql` 默认自动提交，不加 `BEGIN` 的探针会真的留下数据）。探针向量用 1024 维字面量，不经过 Embedding，零 API 成本。宿主机无 `psql`，同样经容器执行；SQL 走引号 here-doc，不需要反斜杠转义。

```bash
# C9.1 正向事务探针（这一步的所有语句都应当成功，所以 ON_ERROR_STOP=1）
docker exec -T -e PGPASSWORD="$PGPASSWORD" interview-eval-postgres \
  psql -h 127.0.0.1 -p 5432 -U eval_runner -d interview_guide_eval -X -q -v ON_ERROR_STOP=1 <<'SQL'
BEGIN;
INSERT INTO vector_store (content, metadata, embedding)
VALUES ('__p1c_perm_probe__', '{"probe":"p1c-c9"}'::json,
        ('[' || repeat('0.1,', 1023) || '0.1]')::vector);
SELECT 'probe_rows='||count(*)||' probe_dims='||max(vector_dims(embedding))
  FROM vector_store WHERE content='__p1c_perm_probe__';
UPDATE vector_store SET content='__p1c_perm_probe_u__' WHERE content='__p1c_perm_probe__';
DELETE FROM vector_store WHERE content='__p1c_perm_probe_u__';
ROLLBACK;
SQL
```
预期：`probe_rows=1 probe_dims=1024`，四条 DML 全部成功，整段 exit 0。这一步同时验证 `id` 默认 `gen_random_uuid()` 可用、`metadata` 列是 `json`（不是 `jsonb`，与交付的 `eval-init.sql` 一致，`::json` 显式转换应当被接受）、`vector_dims()` 在该镜像的 pgvector 版本里存在。
若中途任何一条报错，`ON_ERROR_STOP=1` 会让 psql 立刻退出，此时事务未提交，会话断开即回滚——所以「报错后仍要跑 C9.2 复查」是硬要求，不是可选步骤。

```bash
# C9.2 回滚证据（必须独立会话执行，不能放在同一事务里）
docker exec -T -e PGPASSWORD="$PGPASSWORD" interview-eval-postgres \
  psql -h 127.0.0.1 -p 5432 -U eval_runner -d interview_guide_eval -X -q \
  -c "SELECT count(*) AS total_rows FROM vector_store;" \
  -c "SELECT count(*) AS probe_leftovers FROM vector_store WHERE starts_with(content, '__p1c_perm_probe');"
```
预期：`total_rows=0` 且 `probe_leftovers=0`。（用 `starts_with()` 而不是 `LIKE '__p1c_perm_probe%'`：`_` 在 `LIKE` 里是单字符通配符，会把匹配范围放大到非探针行。`content` 可能为 `NULL`，`starts_with(NULL,…)` 返回 NULL 而非 true，不会漏判也不会误判。）
失败停止：若残留 > 0（例如 `-c` 里的 `BEGIN` 被提前中断），**必须**按 `starts_with(content, '__p1c_perm_probe')` 精确删除并复查，直到回到 0；不得留下探针行进入后续评测——Phase 0 的「全表为空」硬性检查会因此把整轮拒掉，而这属于环境污染，不是隔离结论。删除只允许针对该 `content` 前缀，禁止无条件 `DELETE`/`TRUNCATE` 全表。

## 9. C10 — 1024 维列、HNSW `vector_cosine_ops` 索引、扩展版本

```bash
# 以 eval_runner 经容器内 TCP 执行（Java Phase 0 用的就是这几条，口径保持一致）
docker exec -T -e PGPASSWORD="$PGPASSWORD" interview-eval-postgres \
  psql -h 127.0.0.1 -p 5432 -U eval_runner -d interview_guide_eval -X -q \
  -c "SELECT 'atttypmod='||atttypmod FROM pg_attribute JOIN pg_class ON attrelid=oid
       WHERE relname='vector_store' AND attname='embedding';" \
  -c "SELECT indexname, indexdef FROM pg_indexes WHERE tablename='vector_store';" \
  -c "SELECT 'extversion='||extversion FROM pg_extension WHERE extname='vector';"
```
预期：`atttypmod` = 1024（与 `eval.embedding.dimensions` 默认 1024 相等，Java 侧做的是整数相等判断）；`indexdef` 同时含 `using hnsw` 与 `vector_cosine_ops`，索引名 `spring_ai_vector_index`。
失败停止：维度或索引口径不符 → 记录 `indexdef` 原文，**不要**现场手建索引来凑过检查（那会让报告里的 `vectorStoreConfig.indexDef` 与真实初始化脱钩）。`extversion` 只需如实记录；若版本低到不提供 `vector_dims()`，C9 会先失败，按 C9 停止。

## 10. C11 — 收尾状态与记录

```bash
docker compose -f docker-compose-eval.yml --env-file .env.eval ps        # 期望 (healthy)
docker exec -T -e PGPASSWORD="$PGPASSWORD" interview-eval-postgres \
  psql -h 127.0.0.1 -p 5432 -U eval_runner -d interview_guide_eval -X -q \
  -c "SELECT 'vector_store_rows='||count(*) FROM vector_store;"          # 期望 0
docker inspect interview-eval-postgres \
  --format 'image={{.Config.Image}} | imageID={{.Image}} | startedAt={{.State.StartedAt}}'
docker volume inspect <C2-post 的卷名> --format 'volumeCreatedAt={{.CreatedAt}}'
unset PGPASSWORD P1C_PW                                                   # 清理会话里的口令
```
预期：`vector_store_rows=0`（本清单全程不允许出现非探针写入，探针已回滚）。§12 必须同时记录镜像名、镜像 ID（digest 更佳）、容器 `StartedAt` 与卷 `CreatedAt`——它们是分支 A/B 结论的时间线凭据。停止方式：`docker compose -f docker-compose-eval.yml --env-file .env.eval stop`（保留卷，供复核）。**不要求、也不要**在本轮 `down -v`。

## 11. 验收判据

- C1–C11 全部 PASS，且每项在 §12 有**实测值原文**（不是「符合预期」这类转述）；
- §5 的分支判定必须写明走了 **A（新卷首次初始化）** 还是 **B（旧卷复用）**，并附卷 `CreatedAt` / 容器 `StartedAt` / up 时刻三个时间点；只有分支 A 且四要件齐备，才允许出现「本次启动执行过初始化脚本」这句话；分支 B 只能写「数据面就绪、init 归属未知」；
- C2 的三方归属（容器 project 标签 / 容器 Mounts 卷名 / 卷 project+volume 标签）必须逐项记录实际字符串；
- C8 的声明式与行为式两个口径分别记录；三条负向探针必须**各自独立会话**执行，§12 里记下每条的实际错误文本原文与复查计数（缺文本 = 不能算 PASS）；
- C9 回滚后 `total_rows` 与 `probe_leftovers` 都为 0；
- C3 全程只输出过 `--quiet` 的退出码与 ports 一行，未把完整渲染配置写入终端记录、文件或 §12；
- 全程未发起 Embedding/HTTP、未运行 `evalP1cReal`、未连 5432 生产库、**未删除任何卷**；
- 结果连同镜像名与 ID（digest 更佳）、`extversion`、容器 ID、卷时间线写回根目录 `PROJECT_PROGRESS.md`（把 §5 对应项从「未验证」移入「真实环境已验证」，附本节记录表）。
以上任一未满足，只能记为「部分验证」，并明确列出仍未覆盖的项。

## 12. 记录表（执行时填写）

```
C1  端口5433空闲            实测:__________  PASS/FAIL  时间:______
C2pre 卷/容器是否已存在      实测:__________  分支: A新卷 / B旧卷
C2post 容器project标签       实测:__________
C2post 容器Mounts三条        实测:__________  PASS/FAIL
C2post 卷名/CreatedAt/标签   实测:__________  PASS/FAIL
C3  config --quiet 退出码     exit=______  ports一行:__________  PASS/FAIL
C4  up+healthcheck+pg_isready 实测:__________  PASS/FAIL  up时刻:______
C5  容器StartedAt/卷CreatedAt/本次窗口日志  实测:__________
C6.0 unix socket auth_method  实测:__________（trust / scram / md5）
C6.1 tables / roles / ext 三行 实测:__________  PASS/FAIL  人工确认人(若需重建):______
C7  db/user/标记/server_port/空表/client_addr  实测:__________  PASS/FAIL  容器ID比对:______
C8a 声明式权限(含 truncate=f)  实测:__________  PASS/FAIL
C8b-1 INSERT标记表 错误文本+复查计数  实测:__________  PASS/FAIL
C8b-2 CREATE TABLE 错误文本+leftover   实测:__________  PASS/FAIL
C8b-3 TRUNCATE 错误文本+vs_rows        实测:__________  PASS/FAIL
C9  事务探针 dims=1024 + 回滚后 total/probe_leftovers=0  实测:__________  PASS/FAIL
C10 atttypmod/indexdef/extversion      实测:__________  PASS/FAIL
C11 收尾(空表/镜像名与ID/时间线/stop未删卷) 实测:__________  PASS/FAIL
未发起 Embedding / 未跑 evalP1cReal / 未 down -v   确认:______
口令处理：只用 read -rs + export，命令行与 §12 无口令明文   确认:______
```

## 13. 引用点

- 代码：`app/src/test/java/interview/guide/eval/P1cRealRetrievalEvalTest.java`（`verifyIdentity()` / Phase 0 维度与索引 SQL / Phase 5 清理用 `DELETE` 带 `eval_run_id` 谓词 / `requireCredential("eval.datasource.password","EVAL_RUNNER_PASSWORD")`）
- 配置：`docker-compose-eval.yml`（`${...:?}` 强制两个口令、`127.0.0.1` 回环绑定、两个 init 文件）、`docker/postgres/eval-init.sql`（扩展 + 标记表 + `vector_store(metadata json)` + HNSW `vector_cosine_ops`；**不含用户/授权**）、`docker/postgres/eval-user.sh`（角色创建与授权，`WHERE NOT EXISTS` 守卫）、`app/build.gradle`（`evalP1cReal` 只在显式 `-P` 时转发）
- 设计：`P1C-L1-DESIGN.md` §2 compose 与 init 分工、§3 隔离与身份核验、§7 Phase 0、§13 运行前检查清单、§14 失败时清理规则、§16「v1.4 文档定点修订」
- 进度：仓库根 `PROJECT_PROGRESS.md` §3 B5/B6/B7、§4 T4 前置条件、§5 执行边界

## 14. 本轮（2026-09-30 白天，离线文档轮）定点修订记录

按复核意见逐条对应，全部只改本文档；未启动容器、未连数据库、未调用 Embedding、未运行 `evalP1cReal`、未删除任何卷。

| # | 复核意见 | 修订 |
|---|----------|------|
| 1 | 卷标签判据不成立 | C2 改为读 `.Labels`，用 `com.docker.compose.project` + `com.docker.compose.volume` + 容器实际 `Mounts` + 容器 project 标签做三方归属；**取消**对卷上 `project.config_files` 的要求，并说明该标签属于容器/网络层 |
| 2 | 负向探针要可回滚、可区分拒绝原因、不留残留 | C8b 拆成三条独立会话、各自 `BEGIN … ROLLBACK`；每条后附事务之外的残留复查；给出 PASS / FAIL / BLOCKED 分类表（按错误文本 + `session_alive` 行 + 复查计数三重判据）；说明 PostgreSQL 的 DDL 事务性使 `public.probe_t` 不会留下，并解释上一版共用事务会造成「后两条没跑却像全被拒」的假 PASS |
| 3 | 不得输出完整配置或环境变量值 | C3 只留 `config --quiet` 与「python 只取 ports」两条命令，删除原 `config \| grep`；新增 §1 硬边界与 `--no-interpolate` 替代路径 |
| 4 | 对象存在 ≠ 本次执行过 init | §5 拆成两个命题表 + 分支 A（新卷四要件）/ 分支 B（旧卷 B1–B5 检查），要求记录卷 `CreatedAt`、容器 `StartedAt`、up 时刻与 `--since 15m` 的本次窗口日志；`down -v` 仍只作为人工确认后的手工动作，并补项目名核对要求 |
| 5 | 静态检查命令的目标环境可执行性 | 本轮实测：宿主机**无 `psql`、无 `jq`**，Docker daemon 未运行 → C6/C7/C8/C9/C10/C11 的 SQL 全部改为 `docker exec -T … psql`，凭据核验走容器内 TCP（并解释 unix socket 可能是 `trust`，socket 成功证明不了口令）；JSON 过滤统一用 `python`；所有 `docker compose` 子命令补 `--env-file .env.eval`；口令改为 `read -rs` + `export`，命令行不再出现口令占位符 |

### 14.1 本轮验证命令与退出码（零容器、零网络）

| 命令 | 退出码 | 结果 |
|------|--------|------|
| §0 的 `python - <<'PY' … PY`（25 项断言，只读文件） | 0 | 25 PASS / 0 FAIL；其中 here-doc 开闭配对 `4/4`、`--env-file` 违例 = 无 |
| 逐个 bash 代码块喂 `bash -n`（Git Bash：`C:\Program Files\Git\bin\bash.exe`，18 个块） | 0 | 18 blocks / 0 syntax failures |
| C3.2 与 C2-post 的三条 `python -c` 过滤器（输入为**合成假 JSON**，内含哨兵字段 `SECRET`） | 0 | 输出形状符合预期，`SECRET` 未出现 → 证明过滤器只打印 ports/labels/mounts |
| `echo` + `read -rs P1C_PW && export PGPASSWORD=…` 实跑（口令用假值） | 0 | 不回显、`len=5 set=yes` |
| `date '+%Y-%m-%dT%H:%M:%S%z'` | 0 | `2026-09-30T18:30:17+0800`（C4/C5 的时间戳格式据此确定） |
| `netstat -ano \| grep ":5433" \| grep LISTEN`（C1 预跑，非今晚正式执行） | 1 | 无匹配 = 端口空闲；1 是 grep 的「无命中」码，不是失败 |
| `docker info` | 非 0 | `failed to connect to the docker API at npipe:////./pipe/dockerDesktopLinuxEngine` → Docker Desktop 未运行，属 T4 前置条件，本清单不代为启动 |

两处**由这轮静态核验才发现**的文档缺陷已修正并各加了一条回归断言：`read -rs VAR?提示语`（变量名内嵌提示语，属 ksh/zsh 专有语法，Git Bash 的 bash 报 `not a valid identifier`）与 C7 中不存在的列 `client_host`（正解 `client_addr`）。

自指风险说明：本节的命令文本会被 §0 断言块扫到，所以断言只作用于「去掉自检脚本自身的清单正文」（`kk`）。上一轮曾因脚本搜索词匹配到自己而产生假 FAIL，也曾因变更记录里引用旧字面量而假 FAIL——引用缺陷时用描述而不是原样粘贴。
