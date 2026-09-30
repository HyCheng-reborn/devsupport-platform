# P1-C 独立评测容器 · 无 API 验证清单

> 状态：**待执行**（计划 22:00–23:00 执行；前置条件是 Codex 对**本轮定点修订**的复核结论已收到）。
> 本文件里所有「预期」都是**设计与代码推导出的期望值，不是实测结果**。执行时把实测值写进 §12 记录表。§0 的静态核对与工具可用性探测是本文件唯一已实测的部分，已标注实测/推导。
> 范围：只启动评测 Postgres 容器 + 静态/SQL 核对。**禁止**：调用 Embedding API、运行 `:app:evalP1cReal`、执行正式 L1、连接生产库、启动生产 Compose、删除任何卷。

## 0. 本轮（白天）已做的静态核对

纯本地、零成本、可复跑。本轮定点修订（§14 的 #6、#7、#8）后重跑了下方代码块：**33 项断言全部 PASS，exit=0**（其中 15 项是 compose/init/user.sh/Java/Gradle 的配置与门禁断言，18 项是文档一致性与清单形态断言——上一轮是 25 项/10 项，把清单形态换成「口令只在容器内展开」后形态断言相应增加，#8 又补了两条：读 stdin 的 `docker exec` 必须带 `-i`、三条负向探针都要有会话存活行，故为 16→18）。下面的条目还额外记录了几项**不在代码块里**的核对（生产 Compose 无 `eval` 引用、`.gitignore`、口令字面量扫描、离线测试），逐条列明依据。

- `docker-compose-eval.yml`：`127.0.0.1:${EVAL_POSTGRES_PORT:-5433}:5432`（显式回环绑定）、`image: pgvector/pgvector:pg16`、独立 `container_name: interview-eval-postgres`、挂载 `10-eval-schema.sql` + `20-eval-user.sh`、两个密码均为 `${...:?}` 强制（无默认弱口令）、无 `network_mode: host`。
- `docker/postgres/eval-init.sql`：`CREATE EXTENSION IF NOT EXISTS vector`、`embedding vector(1024)`、`USING hnsw (embedding vector_cosine_ops)`、标记值与 Java 常量逐字一致、文件内不含任何用户/权限/口令语句、无 `DROP`/`TRUNCATE`。
- `docker/postgres/eval-user.sh`：`set -euo pipefail`、密码缺失即退出、口令经 `psql -v` + `:'runner_pw'` + `%L` 传参（文件内无明文口令）、`CREATE ROLE` 带 `WHERE NOT EXISTS`、授予项恰为 `CONNECT` / `USAGE` / `SELECT, INSERT, UPDATE, DELETE`（`vector_store`）/ `SELECT`（标记表）、**无** `ALL PRIVILEGES`、**无** `SUPERUSER`/`CREATEDB`。
- 装配与门禁：`initializeSchema(false)`、`current_user` 必须为 `eval_runner`、默认连接串 `jdbc:postgresql://127.0.0.1:5433/interview_guide_eval`、检查 `rolsuper`、检查全表为空；`:app:test` 排除 `real-eval`，`evalP1cReal` 的 `eval.p1c.realApi` 默认 `'false'`。
- 隔离与密钥：`docker-compose.yml` / `docker-compose.dev.yml` 中**不含**任何 `eval` 引用（生产栈不会挂载评测初始化脚本）；`.gitignore` 第 12–13 行忽略 `.env` 与 `.env.eval`，`git ls-files` 只有 `.env.eval.example`；对 eval 相关文件做口令字面量扫描命中 0。
- 离线测试：`GRADLE_USER_HOME=/c/temp/gradle-tmp ./gradlew :app:test --tests 'interview.guide.eval.*' --no-daemon --rerun` exit 0（114 通过 / 0 失败 / 0 错误 / 0 跳过）。本轮**未改任何代码**，所以这条记录的是 `f6aa629` 的状态，本轮没有重跑（边界：只做文档修订）。
- **清单命令形态的可执行性核验（本轮实测，零容器）**：把本清单所有 bash 代码块逐个喂给 `bash -n`（语法检查，不执行；必须用 Git Bash 的 `C:\Program Files\Git\bin\bash.exe`，裸调 `bash` 会解析到 `C:\WINDOWS\System32\bash.exe` 那个 WSL 启动器而把每个块都判成失败）——**24 个块，0 处语法错误**（上一轮 18 个，#6/#7 那两次定点后为 23 个，#8 再加 C5.1 形态冒烟自检一块）；C3.2、C2-post 的两条与 C2-pre-vol 的两条 `python` 过滤器，用**合成的假 JSON / 假命令输出**（各埋一个哨兵：标签字段 `SECRET`、哨兵口令值）实跑，exit 0、输出形状正确、哨兵值一个都没出现在输出里 → 证明过滤器只打印 ports / labels / mounts / 两个卷标签；§1 的存在性探针与正文全部 **14** 条 here-doc 命令，用「假 `docker` 函数 + 假 `psql` 脚本」做了离线干跑（细节见 §14.1）：宿主机侧 argv 里只出现变量名的字面文本、哨兵口令没有进入假 `docker` 的 argv、`env_present len=` 与 `env_ABSENT` 两个分支都能出。**本轮把干跑的模型改严了一档**：假 `docker` 现在按「参数里有没有 `-i`」决定是转发还是丢弃 stdin——上一轮那个无条件把 here-doc 交给假 `psql` 的模型恰好验的是自己设计的假设，而不是 CLI 的真实语义，这正是 #8 ① 没被上一轮发现的原因。对照实跑（同一条 C5.1 命令，只差 `-i`）：带 `-i` 时假 `psql` 收到 39 字节 SQL 并输出 `stdin_ok user=eval_runner`、exit 0；去掉 `-i` 只留 `-T` 时假 `psql` 收到 **0 字节、stdout 一个字符都没有、退出码仍然是 0**——缺陷 1 的「静默假 PASS」症状就此复现，也正是今晚 C5.1 分支 ② 要拦的那个形状；`date '+%Y-%m-%dT%H:%M:%S%z'` 与 C1 的 `netstat -ano | grep ":5433" | grep LISTEN`（exit 1 = 无匹配 = 端口空闲）本轮各重跑一次；上一轮因静态核验发现的两处缺陷里，「宿主机静默读取把提示语写进变量名」那条属 ksh/zsh 专有语法、Git Bash 会报错，本轮随宿主机录入被整体删除而不再适用，另一条（C7 曾引用不存在的列 `client_host`，正解 `client_addr`）仍是正文用词依据。这些是「命令能否跑」层面的实测，不涉及任何数据库行为结论。
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
# 本轮（C2 卷侧分支 + 口令不进 argv）定点：清单形态断言
ok('sh -c' in kk and 'PGPASSWORD="$EVAL_RUNNER_PASSWORD"' in kk and '-f -' in kk,'eval_runner 统一走容器内 sh -c + here-doc -f - 形态')
ok(('-e PGPASS' 'WORD') not in kk and ('PGPASSWORD="$PG' 'PASSWORD"') not in kk,'正文不再把宿主机口令变量透传进 docker argv')
ok('read -r' 's' not in kk and 'P1C_PW' not in kk and 'export PGPASSWORD;' in kk,'宿主机静默录入已彻底删除，export 只发生在容器内 sh')
ok('env_present len=' in kk and 'env_ABSENT' in kk,'口令存在性探针只打印长度（len），并给出 env_ABSENT 停止分支')
ok('{{json .Config.Env}}' in kk and 'printenv' in kk,'两条读取容器环境的硬禁令已写入正文')
ok('C2-pre-vol' in kk and 'CAND_COUNT' in kk and 'VOL_LABEL_MISSING' in kk,'C2 补了不依赖容器存在的卷侧归属')
ok(all(s in kk for s in ('| ① |','| ② |','| ③ |','| ④ |')),'C2-pre-vol 四状态判定表行齐')
ok('完全一致' in kk and '立即停止，不 `up`' in kk,'状态 ② 逐字比对卷名 / 状态 ④ 不 up 均已写明')
# 本轮 #8 定点：-T 不转发 stdin，读 stdin 的 docker exec 必须带 -i；三条负向探针都要有会话存活行
_kl=kk.splitlines()
bad_i=[l.strip()[:70] for i,l in enumerate(_kl)
       if l.lstrip().startswith('docker exec')
       and any('-f -' in x for x in _kl[i:i+4])
       and not re.search(r'exec\s+(?:-\S+\s+)*-i(?=\s|$)',l)]
ok(not bad_i,'读 stdin(here-doc + -f -) 的 docker exec 均带 -i，违例=%s'%(bad_i or '无'))
ok(kk.count("SELECT 'session_alive user='")>=3,'三条负向探针各自带会话存活行 %d/3'
   % kk.count("SELECT 'session_alive user='"))
PY
```

## 1. 执行边界与通用停止条件

- **只允许**显式 `-f docker-compose-eval.yml`。在仓库根目录裸跑 `docker compose up/down` 会命中生产 `docker-compose.yml`（服务名 `postgres`、端口 5432、库 `interview_guide`），这正是要防的误用。
- **每个 `docker compose` 子命令都要带 `--env-file .env.eval`**（`ps` / `logs` / `exec` / `stop` / `down` 都算）：compose 解析配置时会插值 `${EVAL_DB_PASSWORD:?…}`，缺 env 文件的命令可能直接报「Required environment …」而被误读成容器异常（是否对每个子命令都插值随 compose 版本而异，属推导；统一带上是最稳的写法，成本为零）。本清单上一版有多处示例漏了这个参数，本轮已全部补齐。唯一例外是 `config --no-interpolate`（故意不解析变量，因此不需要 `.env.eval`）。
- **宿主机没有 `psql` 与 `jq`**（本轮实测），所以 SQL 一律经 `docker exec` 在**容器内**执行 `psql`，JSON 过滤一律用 `python`。**读 here-doc 的命令一律 `docker exec -i -T`（`-i` 是转发 stdin 的前提，见下方「统一执行形态」）；不读 stdin 的命令保持 `docker exec -T`**——具体是 §1 的 `env_present len=` 存在性探针（内联 `sh -c '…'`，无 `-f -`）、C6.0/C6.1/C8a 的 `psql -U postgres … -c "SELECT …"`（容器内 unix socket 免密、超管声明式查询）、C6.0 兜底的 `cat /var/lib/postgresql/data/pg_hba.conf`，这四类给 `-i` 也没有任何作用。以 `eval_runner` 做核验时必须走容器内 TCP（`-h 127.0.0.1 -p 5432`），理由见 C6.0 与 C7。
- **凭据注入方式（本轮定点：宿主机根本不持有口令）**：口令只存在于本机 `.env.eval`，由 compose 在 `up` 时注入容器的 `EVAL_RUNNER_PASSWORD`。上一版清单里 `docker exec` 用 `-e` 把**宿主机 shell 的口令变量**透给容器，宿主机会先把该变量展开成真实口令，于是口令出现在 `docker` 进程的 argv 里（任务管理器 / `ps` / 进程列表可见），这正是本轮要消除的泄漏面；§1 里那套「宿主机静默读取（`read` 加不回显选项）+ `export`」的口令录入也随之整体删除。现在改成让容器内的 `/bin/sh` 去展开它自己环境里的那个变量——宿主机命令行里只出现**变量名的字面文本**，从头到尾没有口令值。会话开头只做一次存在性探针（只打印长度，不打印值）：

```bash
docker exec -T interview-eval-postgres sh -c 'if [ -n "$EVAL_RUNNER_PASSWORD" ]; then echo "env_present len=${#EVAL_RUNNER_PASSWORD}"; else echo env_ABSENT; fi'
```

  预期形如 `env_present len=32`（长度不是内容，可以记入 §12）；若输出 `env_ABSENT`，说明 `up` 时没带上 `.env.eval` → **停止并记录**，禁止退回「把口令写进命令行」的写法。C11 里对应地不再 `unset` 任何口令变量（宿主机从来没有设过），改为记录「本清单全程未在宿主机 argv / shell 历史 / 记录表里出现口令值」。
- **`eval_runner` 的统一执行形态（本轮定点；下面每条以 `eval_runner` 跑 SQL 的命令都照这个形态写，含 C6/C7/C8b/C9/C10/C11）**：

```bash
docker exec -i -T interview-eval-postgres sh -c \
  'PGPASSWORD="$EVAL_RUNNER_PASSWORD"; export PGPASSWORD; exec psql -h 127.0.0.1 -p 5432 -U eval_runner -d interview_guide_eval -X -q -v ON_ERROR_STOP=1 -f -' \
  <<'SQL'
SELECT 'client_login_ok user='||current_user||' db='||current_database();
SQL
```

  要点：外层 `sh -c` 的参数**必须用单引号**，这样 `$EVAL_RUNNER_PASSWORD` 不在宿主机展开；SQL 一律走 here-doc + `-f -`，**不要**改成用 `-c` 传 SQL——外层若用双引号宿主机就会展开变量，外层保持单引号时 `-c` 里的 SQL 又必然带单引号（字符串字面量），与外层单引号直接冲突，这是必须避免的 quoting 陷阱；`exec` 让 psql 顶替 sh 进程，少留一层进程；here-doc 的结束标记 `SQL` 必须**顶格**（§0 的断言会检查开闭配对）。各条原本选的 `ON_ERROR_STOP=0/1` 按原语义保留，不要统一成同一个值。需要 `postgres` 超管的项（C6.0 查 `pg_hba_file_rules`、C8a 声明式权限）本来就是容器内 unix socket 免密登录，保持 `docker exec -T interview-eval-postgres psql -U postgres …` 形态不动。
- **`-i` 是这条形态的前置条件，不是可选项（本轮定点，见 §14 #8）**：Docker CLI 文档对 `-i/--interactive` 的描述是 “**Keep STDIN open even if not attached**”，据此**不加 `-i` 时 here-doc 根本不会到达容器进程**；`-T` 只关闭伪 TTY 分配，**与 stdin 无关**，`-i -T` 才是「无 TTY 但转发 stdin」的组合。后果（推理链）：少了 `-i` → 容器内 `psql -f -` 立刻遇到 EOF → 不输出任何行、不报错、退出码 0 → 执行者把「无输出」写成 PASS，而实际一条 SQL 都没跑，**整份清单的 SQL 检查会静默假通过**。
  - 性质标注：以上是根据文档措辞推出的**推理**（本轮 Docker daemon 未运行，未做真机实测），今晚由 §5 末尾的**形态冒烟自检**直接证伪——自检跑不通就按那条的分支判 BLOCKED，不得凭本段文字判 PASS。
  - `-T` 这一面：`-T` 出现在 `docker run`/`docker compose` 的文档里，在 `docker exec` 的文档页上并未列出。今晚若某版 CLI 直接拒绝 `-T`（报 `unknown shorthand flag` 之类），**如实记录并按「形态缺陷」停**（与上面 ② 同级处理），不要现场改成 `bash -c`、去掉 `-T` 或换回 `-c` 传 SQL 之类的发挥——那会把「命令形态」问题伪装成「数据库行为」结论。
- **两条硬禁令（本轮定点）**：不得运行 `docker inspect --format '{{json .Config.Env}}' interview-eval-postgres`，不得运行 `docker exec -T interview-eval-postgres env` 或 `… printenv`。容器环境里同时带着 `POSTGRES_PASSWORD` 与 `EVAL_RUNNER_PASSWORD`，这两条都会把两个口令明文打到终端（下一条「不打印渲染后的完整 Compose 配置」的边界同样覆盖它们）。
- **口令新鲜度的诚实口径**：容器环境里的口令是 `up` 时刻从 `.env.eval` 读到的值。若执行者在 `up` 之后改过 `.env.eval`，登录测试反映的仍是 `up` 时刻的值，所以要把 `up` 时刻一并记进 §12。§5 分支 B 的口令新鲜度检查（B2）仍然成立，而且这个口径比宿主机传值更强：卷内角色口令与当前口令不一致时会直接登录失败，而不是静默地改用宿主机随手传进来的那个值。若某条命令报 `FATAL: password authentication failed`，先按 B2 判「卷与当前凭据不一致」，不要改成明文重试，也不要现场 `ALTER ROLE`。
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

C2 分三段跑：`up` 之前先分别看**容器侧存在性**（C2-pre）和**卷侧归属**（C2-pre-vol），据此决定走 §5 的「新卷」还是「旧卷」分支；`up` 之后做容器标签 ↔ 容器实际挂载 ↔ 卷标签的三方交叉核对（C2-post）。

**为什么必须拆出卷侧一段（本轮定点）**：C2-post 的第一条命令就是 `docker inspect interview-eval-postgres`，**容器不存在时它根本跑不了**。而「卷还在、容器已不在」恰恰是常见状态（上一次执行只 `down` 没 `-v`，或容器被 `docker rm`，卷却留着）——上一版写「有输出就先做完 C2-post 再 `up`」，在这个状态下无法执行，是清单自身的缺陷。所以 `up` 之前的归属判断，必须改用**不依赖容器存在**的卷侧证据来初判。

```bash
# C2-pre（up 之前）：容器侧存在性
docker ps -a --filter name=interview-eval-postgres --format '{{.Names}} | {{.State}} | {{.Status}} | {{.Ports}}'

# C2-pre-vol（up 之前）：卷侧候选，不依赖容器存在。卷真实名字带项目名前缀，所以不能只按精确名过滤
docker volume ls --format '{{.Driver}}|{{.Name}}' \
  | python -c "import sys;c=[l.rstrip().split('|',1)[1] for l in sys.stdin if '|' in l];c=[n for n in c if n=='eval_postgres_data' or n.endswith('_eval_postgres_data')];print('CAND_COUNT',len(c));[print('CAND',n) for n in c]"

# C2-pre-vol 第二步：对上一步列出的【每一个】候选卷分别跑一次（卷名逐个替换，不要一次性传多个名字）
docker volume inspect <单个候选卷名> --format '{{.Name}} | {{.CreatedAt}} | {{json .Labels}}' \
  | python -c "import json,sys;p=sys.stdin.read().split(' | ',2);L=json.loads(p[2]) if len(p)==3 else {};L=L if isinstance(L,dict) else {};print('VOL_NAME',p[0]);print('VOL_CREATED',p[1] if len(p)>1 else '(parse_fail)');[print('VOL_LABEL',k,'=',L[k]) for k in ('com.docker.compose.project','com.docker.compose.volume') if k in L];m=[k for k in ('com.docker.compose.project','com.docker.compose.volume') if k not in L];print('VOL_LABEL_MISSING',m or 'none')"
```
容器名预期恰为 `interview-eval-postgres`（生产栈是 `interview-postgres`，名字/端口/库名都不得混淆）。卷侧只提取 `com.docker.compose.project` / `com.docker.compose.volume` 两个标签和 `CreatedAt`，其余标签（如 `com.docker.compose.version`）如实记录即可，**不要**顺手打印 `{{json .}}` 全文。

**判定表（本轮定点，四种状态各自走哪条路）**：

| # | C2-pre 容器侧 | C2-pre-vol 候选卷 | 动作 |
|---|---------------|-------------------|------|
| ① | 无输出（容器不存在） | `CAND_COUNT 0` | **§5 分支 A（新卷首次初始化）**：直接 `up`，`up` 后做 C2-post 三方核对 |
| ② | 无输出（容器不存在） | 恰 1 个，且其 `project` 标签等于预期项目名、`volume` 标签为 `eval_postgres_data` | **§5 分支 B（旧卷复用）**——本轮补的情形：`up` 之前**只能凭卷侧标签初判归属**，C2-post 顺延到 `up` 之后立即补做，并核对补做时从容器 `Mounts` 读到的卷名与初判记录的名字**完全一致**（不一致说明 compose 实际用了别的卷，立即停止） |
| ③ | 有输出（容器存在，不论 running / exited / created） | 恰 1 个 | 可以先跑完整 C2-post 三方核对，再决定是否 `up`；分支仍按 §5 的卷 `CreatedAt` 与本次窗口日志判定 |
| ④ | 任意 | 候选卷 ≥ 2；或卷 `project` 标签与预期项目名不符；或两个标签缺失（`VOL_LABEL_MISSING` 非 `none`） | **立即停止，不 `up`**，把 `CAND_COUNT` 与每个候选卷的输出原样记入 §12 |

状态 ④ 不 `up` 的理由（必须照记）：compose 找不到与项目名匹配的卷时，`up` 会**新建第三个卷**，而任何后续 `down -v` 都是**按项目名删卷**的——歧义会在这个方向上扩大影响面，届时无法说清删掉的是哪一个。

**「预期项目名」从哪来（本轮定点，且不得越界）**：默认取 Compose 文件所在目录名（本仓库根 = `interview-guide`），可被 `docker compose -p <name>` 或环境变量 `COMPOSE_PROJECT_NAME` 覆盖。**不要**为了拿项目名去额外运行 `docker compose config`（C3 已把 `config` 限定为 `--quiet` 与「python 只取 ports 字段」两条命令，多跑一次完整 `config` 就是把已解析口令打进输出的老路）。今晚若用过 `-p` 或 `COMPOSE_PROJECT_NAME`，必须把**同一个取值**写进 §12，并在 `up` 之后以容器 Labels 里 `com.docker.compose.project` 的实测值闭环。**「项目名 + `_` + 卷短名」这条前缀规则属推导**（来自 Compose 的命名约定），今晚一律以实测标签与实测卷名原文为准——这也是上表状态 ② 要求「补做后逐字比对卷名」的原因。

**C2-pre / C2-pre-vol 的输出都要记进 §12**：容器侧原文、`CAND_COUNT`、每个候选卷的名字 / `CreatedAt` / 两个标签原文，这三项共同构成 `up` 之前的归属快照；没有这份快照，分支 B 的「旧卷」结论事后无从复核。

```bash
# C2-post：容器标签 ↔ 容器实际挂载 ↔ 卷标签，三方交叉核对（只打印标签与挂载，不打印环境）
# 状态 ③ 可以在 up 之前跑；状态 ② 必须顺延到 up 之后立即补做（容器不存在时这段无法执行）
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
- **状态 ②（卷在、容器不在）的额外一致性要求**：这里从 `Mounts` 读到的卷名，必须与 C2-pre-vol 初判时记录的候选卷名**逐字相同**。相同 → 归属闭环成立，分支 B 的卷侧前提成立；不同 → compose 用的是第三个卷，按状态 ④ 处理：立即停止并原样记录两个名字。

**判据修订说明（本轮定点）**：**不再要求卷上存在 `com.docker.compose.project.config_files` 标签**。`config_files` / `working_dir` 属于容器（及网络）的标签层，卷标签层通常只有 `com.docker.compose.project` 与 `com.docker.compose.volume`（不同 compose 版本可能附带 `version` 之类，如实记录即可）。沿用上一版判据会读到一个空值并把「卷不属于评测栈」这种误报写进结论；卷的归属改由「容器 Mounts 里的卷名 + 卷 project 标签 + 容器 project 标签」三者闭环证明。这一条是按 Compose 的标签分层约定推导的，今晚以实测标签原文为准。

失败停止：卷的 project 标签与容器不一致，或该卷名同时被另一个 Compose 项目引用，或命中上表状态 ④ → **立即停止**，不 `down`、不删卷、不改标签，不 `up`（状态 ④），把三方输出与 C2-pre-vol 快照原样记入 §12。

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
docker volume inspect <C2-post 得到的卷名；状态 ② 时即 C2-pre-vol 初判、C2-post 补做后逐字确认的那个名字> --format 'VolumeCreatedAt={{.CreatedAt}} | {{json .Labels}}'
docker compose -f docker-compose-eval.yml --env-file .env.eval logs --since 15m eval-postgres \
  | grep -E "initdb|docker-entrypoint-initdb.d|10-eval-schema|20-eval-user|database system is ready|waiting for server"
```
`--since 15m` 用来把证据限定在本次启动窗口内，避免把历史日志当成本次的证据；日志措辞随镜像版本变化，所以判定**只按分支规则**，不依赖某个具体词。

**分支 A —— 新卷首次初始化**（对应 §3 判定表状态 ①：C2-pre 无容器输出且 C2-pre-vol `CAND_COUNT 0`，并且 `VolumeCreatedAt` 落在本次 `up` 之后）。以下四项同时成立才允许写「本次启动执行过初始化脚本」：
1. `VolumeCreatedAt` ≈ 容器 `StartedAt`，且两者都晚于记录的 up 时刻；
2. 本次窗口日志里出现 `initdb` 与两个 init 文件名（`10-eval-schema.sql`、`20-eval-user.sh`）；
3. C6 对象与扩展齐全；
4. C7 用**当前** `.env.eval` 的口令经 TCP 以 `eval_runner` 登录成功（只有本次创建的卷，角色口令才会与当前 `.env.eval` 一致）。

**分支 B —— 旧卷复用**（`VolumeCreatedAt` 早于本次启动，或本次窗口日志里没有 init 文件名）。**本轮定点**：分支 B 现在明确包含 §3 判定表的**状态 ②「卷在、容器不在」**这一子情形——它必然落在分支 B（数据目录非空 → init 不会重跑），并且要求 `up` 之后补做的 C2-post 读到的卷名与 `up` 之前的卷侧初判逐字一致。**这不是 FAIL，但绝不能记「init 已执行」**；改判为「数据面就绪、init 归属未知」，并逐项完成：
- B1 对象存在性：C6 的表 / 角色 / 扩展；
- B2 凭据新鲜度：用**当前** `.env.eval` 口令以 `eval_runner` 经容器内 TCP 登录（C7）。`CREATE ROLE ... WHERE NOT EXISTS` 使旧卷保留旧口令，改了 `.env.eval` 也不轮换、不报错——登录失败说明**卷与当前凭据不一致**，属状态不明：停止并报告，不要现场 `ALTER ROLE` 凑过（那会掩盖卷的来源问题）。注意本清单的登录测试用的是**容器环境里 `up` 时刻注入的值**（见 §1），所以「当前口令」在这里要以 up 时刻为锚：`up` 之前刚写好 `.env.eval`、`up` 之后不再改，两者才等价；
- B3 数据面干净：`vector_store` 行数 = 0（C7），且 C9 探针回滚后仍为 0；
- B4 口径一致：C10 的 `atttypmod` / `indexdef` 与交付脚本一致（旧卷若由更早版本脚本创建，索引名或维度口径可能不同）；
- B5 时间线留痕：卷 `CreatedAt` 与容器 `StartedAt` 都写进 §12，作为「历史创建」的证据；状态 ② 还要附 C2-pre-vol 的卷侧快照（候选卷数、卷名、两个标签原文）。

### C5.1 形态冒烟自检（本轮定点，**强制**，排在任何权限 / 身份 / 维度类 SQL 之前）

C6 之后每一条结论都建立在「here-doc 里的 SQL 真的送到了容器内 psql」这个前提上，而这个前提本轮才刚被修（§1 的 `-i`）。所以先跑一条**只依赖客户端解析、不引用任何评测表结构**的 SQL 把形态本身钉住：它过了，后面的「无输出」才有资格被解释成别的原因；它不过，后面全部 SQL 检查会同时变成静默假 PASS。

```bash
# C5.1 形态冒烟自检：验证 stdin → 容器内 psql 这一段是否真的通
docker exec -i -T interview-eval-postgres sh -c \
  'PGPASSWORD="$EVAL_RUNNER_PASSWORD"; export PGPASSWORD; exec psql -h 127.0.0.1 -p 5432 -U eval_runner -d interview_guide_eval -X -q -v ON_ERROR_STOP=1 -f -' \
  <<'SQL'
SELECT 'stdin_ok user='||current_user;
SQL
echo "exit=$?"                                                       # 三分支要靠真实退出码，不能凭「看起来没报错」
```

判据（三分支，必须按实测原文选一支；本条未过之前不得开始 C6.0 及之后任何 SQL。口径先说清：下面「无任何输出」指**除自检自带的 `exit=` 那一行之外，没有来自 psql 的任何输出行**——`exit=0` 本身不算证据，它恰恰是缺陷 1 会一并给出的东西）：

| 实测看到 | 判定与动作 |
|----------|------------|
| ① psql 输出 `stdin_ok user=eval_runner`，且 `exit=0` | **形态正确**（stdin 已转发进容器进程），把整行原文记入 §12，继续 C6.0 |
| ② **无任何输出且 `exit=0`** | 正是 §1 描述的症状：`-i` 缺失，或该版 CLI 的 stdin 语义与文档措辞不符 → **BLOCKED（命令形态 / CLI 行为不符）**。停下，把 `docker --version` 与 `docker compose version` 的原文一并记进 §12。**不许写成 PASS、不许改判据、不许现场改成 `bash -c` / `-c` 传 SQL 之类来「绕过去」** |
| ③ 报 `FATAL:`（password authentication failed / role does not exist）或 `could not connect to server` | 属凭据或连接问题，按 C6 / C7 与分支 B 的 B2 处理（卷与当前凭据不一致 → 停止并报告），**不在此处得出任何权限结论** |

**为什么它必须在最前面**：本自检放在 C5（启动与时间线）之后、C6.0（第一条超管 SQL）之前，是 §11 的硬性验收项；它同时覆盖了「容器已起来」「`eval_runner` 能经容器内 TCP 登录」「here-doc 形态可用」三件事的形态面，但**不**替代 C6/C7 对库名、标记表、空表的核验。

```bash
# C6.0 先确认 unix socket 的认证方式，决定 C6 的超管查询要不要改走 TCP
docker exec -T interview-eval-postgres psql -U postgres -d postgres -X -q \
  -c "SELECT rule_number, type, auth_method, database, user_name FROM pg_hba_file_rules ORDER BY rule_number;"
```
- 若 `local`（unix socket）条目是 `trust` → C6 的只读命令可免密直跑；这同时解释了**为什么凭据核验绝不能走 socket**（socket 登录成功证明不了口令）。
- 若是 `scram-sha-256` / `md5` → 同样按 §1 的形态改由**容器内** shell 展开容器自己环境里的超管变量，宿主机不接触口令值：

```bash
docker exec -i -T interview-eval-postgres sh -c \
  'PGPASSWORD="$POSTGRES_PASSWORD"; export PGPASSWORD; exec psql -h 127.0.0.1 -p 5432 -U postgres -d interview_guide_eval -X -q -v ON_ERROR_STOP=1 -f -' \
  <<'SQL'
SELECT 'superuser_tcp_ok user='||current_user||' db='||current_database();
SQL
```

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

宿主机无 `psql`，所以经容器执行；**必须走 `-h 127.0.0.1 -p 5432` 的 TCP 路径**，因为 unix socket 可能是 `trust` 认证（见 C6.0），socket 登录成功证明不了 `.env.eval` 里的口令与卷内角色口令一致。口令由容器内的 `/bin/sh` 从容器环境展开（形态见 §1），宿主机不接触口令值；本条**不加** `-v ON_ERROR_STOP=1`（沿用上一版默认值 0 的语义：某一行报错时后面的查询仍会跑完，便于一次拿全六项）。

```bash
docker exec -i -T interview-eval-postgres sh -c \
  'PGPASSWORD="$EVAL_RUNNER_PASSWORD"; export PGPASSWORD; exec psql -h 127.0.0.1 -p 5432 -U eval_runner -d interview_guide_eval -X -q -f -' \
  <<'SQL'
SELECT 'db='||current_database()||' user='||current_user;
SELECT version();
SELECT marker_key, marker_value FROM eval_instance_identity ORDER BY marker_key;
SELECT 'server_port='||current_setting('port');
SELECT 'vector_store_rows='||count(*) FROM vector_store;
SELECT 'client_addr='||coalesce(client_addr::text,'(unix socket)') FROM pg_stat_activity WHERE usename='eval_runner';
SQL
```
预期（逐项）：
- 会话本身就是**一次以 `eval_runner` 完成的 TCP 登录**——上面六条能跑出来，等价于「TCP 口令认证通过」；
- `db=interview_guide_eval`，且与 Java 侧 `eval.datasource.url` 的库名一致；
- `user=eval_runner`（**不是** `postgres`）；能连上本身就证明**`.env.eval` 在 up 时刻注入容器的口令与卷内角色口令一致**（分支 B 的 B2 判据）；
- 标记表两行 = `instance_type | p1c-eval-isolated`、`instance_uuid | f47ac10b-58cc-4372-a567-0e0283c5d9e7`；
- `server_port=5432`（容器内部端口；宿主机映射才是 5433，容器内查不到 5433 是正常的）；
- `vector_store_rows=0`；
- `pg_stat_activity.client_addr` 为 `127.0.0.1`（证明是 TCP 会话，不是 socket）。
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

每条模板（`-v ON_ERROR_STOP=0` 是**故意**的：默认值即为 0，让预期报错之后的 `ROLLBACK` 仍然执行；here-doc 用引号定界，SQL 里的单引号不需要任何 shell 转义；外层 `sh -c` 用单引号，口令只由容器内展开——见 §1；命令一律带 `-i`，理由见 §1 与 §5 的 C5.1）。

**三条探针都带同一行会话存活 SELECT（`session_alive user='||current_user||' db='||current_database()` 那行，三处逐字相同）**：它在 probe-1/2/3 里都位于 `BEGIN;` **之前**——属于事务之外的自动提交语句，不参与探针的回滚语义。上一版只在 probe-1 里有这行，而下面的判据第 2 条却要求三条都有，属判据与命令文本自相矛盾：照那时的文本执行，probe-2/3 永远无法满足判据第 2 条。这行的**唯一作用**是证明「会话活着 + SQL 真的送到了 psql」（也就是 `-i` 的 stdin 确实转发进来了）：**它成功而后续语句被拒**，才是「权限拒绝」的证据；若这行缺失、或整块没有任何输出，一律判 **BLOCKED**（命令形态或会话问题）而**不是 FAIL**，更不许反过来把判据第 2 条删掉来迁就命令。

```bash
# probe-1：向标记表写入（期望被拒）
docker exec -i -T interview-eval-postgres sh -c \
  'PGPASSWORD="$EVAL_RUNNER_PASSWORD"; export PGPASSWORD; exec psql -h 127.0.0.1 -p 5432 -U eval_runner -d interview_guide_eval -X -q -v ON_ERROR_STOP=0 -f -' \
  <<'SQL'
SELECT 'session_alive user='||current_user||' db='||current_database();
BEGIN;
INSERT INTO eval_instance_identity (marker_key, marker_value) VALUES ('probe','x');
ROLLBACK;
SQL
```
```bash
# probe-1 残留复查（独立会话、自动提交，必须在事务之外）
docker exec -i -T interview-eval-postgres sh -c \
  'PGPASSWORD="$EVAL_RUNNER_PASSWORD"; export PGPASSWORD; exec psql -h 127.0.0.1 -p 5432 -U eval_runner -d interview_guide_eval -X -q -f -' \
  <<'SQL'
SELECT count(*) AS marker_total, count(*) FILTER (WHERE marker_key='probe') AS probe_rows FROM eval_instance_identity;
SQL
```
```bash
# probe-2：在 public 建表（期望被拒，PG15+ public 不再默认授 CREATE）
docker exec -i -T interview-eval-postgres sh -c \
  'PGPASSWORD="$EVAL_RUNNER_PASSWORD"; export PGPASSWORD; exec psql -h 127.0.0.1 -p 5432 -U eval_runner -d interview_guide_eval -X -q -v ON_ERROR_STOP=0 -f -' \
  <<'SQL'
SELECT 'session_alive user='||current_user||' db='||current_database();
BEGIN;
CREATE TABLE public.probe_t (id int);
ROLLBACK;
SQL
```
```bash
# probe-2 残留复查：不得留下 public.probe_t
docker exec -i -T interview-eval-postgres sh -c \
  'PGPASSWORD="$EVAL_RUNNER_PASSWORD"; export PGPASSWORD; exec psql -h 127.0.0.1 -p 5432 -U eval_runner -d interview_guide_eval -X -q -f -' \
  <<'SQL'
SELECT count(*) AS probe_t_leftover FROM pg_tables WHERE schemaname='public' AND tablename='probe_t';
SQL
```
```bash
# probe-3：TRUNCATE（期望被拒）
docker exec -i -T interview-eval-postgres sh -c \
  'PGPASSWORD="$EVAL_RUNNER_PASSWORD"; export PGPASSWORD; exec psql -h 127.0.0.1 -p 5432 -U eval_runner -d interview_guide_eval -X -q -v ON_ERROR_STOP=0 -f -' \
  <<'SQL'
SELECT 'session_alive user='||current_user||' db='||current_database();
BEGIN;
TRUNCATE vector_store;
ROLLBACK;
SQL
```
```bash
# probe-3 残留复查
docker exec -i -T interview-eval-postgres sh -c \
  'PGPASSWORD="$EVAL_RUNNER_PASSWORD"; export PGPASSWORD; exec psql -h 127.0.0.1 -p 5432 -U eval_runner -d interview_guide_eval -X -q -f -' \
  <<'SQL'
SELECT count(*) AS vs_rows FROM vector_store;
SQL
```
**为什么这里拆成六个块（本轮定点）**：每条探针与它的残留复查各自成一个 bash 块，让「一次会话 = 一次可整段复制的命令」——新形态带 here-doc 时，同一块里连着写两条 `docker exec` 更容易抄漏后半段（`SQL` 结束标记之后的内容），抄漏就把复查并进了探针会话。分块不改变会话边界：每个 `docker exec` 本来就是一个独立的 `psql` 进程。
预期：**每条探针先输出一行** `session_alive user=eval_runner db=interview_guide_eval`，随后 probe-1/2/3 分别输出 `ERROR: permission denied for table eval_instance_identity` / `ERROR: permission denied for schema public` / `ERROR: permission denied for table vector_store`；三次复查分别为 `marker_total=2, probe_rows=0`、`probe_t_leftover=0`、`vs_rows=0`。

**怎么确认是「权限拒绝」而不是连接或语法错误**（三条合起来才是判据，只看退出码会看错）：
1. 错误文本必须**逐字**是 `permission denied for <对象类型> <名字>`（SQLSTATE 42501 `insufficient_privilege`）；
2. `session_alive …` 那行必须**在每条探针里**都先出现（三条都带这行，不是只有 probe-1），证明连接与会话是活的、且 here-doc 真的经 `-i` 送到了 psql——排除 `FATAL:` / `could not connect` 一类；**这行缺失或整块无任何输出 → 判 BLOCKED（形态/会话问题），不判 FAIL、不许改成「无输出即视为被拒」**；
3. 复查计数符合预期，证明没有任何写入落地。

| 实际看到 | 判定与动作 |
|----------|------------|
| `ERROR: permission denied for table eval_instance_identity`（及对应另两条） | **PASS** |
| 没有 ERROR，且复查显示写入/建表生效 | **FAIL：权限被放大**。事务仍随本次 `ROLLBACK` 结束，先跑复查确认无残留并把输出记进 §12，然后**停止**；不要用 `eval_runner` 去 `DROP`/`DELETE` 自行善后（那会把「权限放大」和「自行清理」两件事混在一起，破坏取证） |
| 无 `session_alive` 行、无 ERROR、整块**零输出**且退出码 0 | **BLOCKED：命令形态缺陷**（stdin 未转发，SQL 根本没跑，见 §1 的 `-i` 说明与 §5 的形态冒烟自检）。这不是「权限被拒」，也**不是 FAIL**；记下 `docker --version` 后停止 |
| `ERROR: duplicate key value violates unique constraint …` | **FAIL**：能走到约束检查说明权限阶段已通过，同样是权限放大 |
| `FATAL: password authentication failed` / `FATAL: role "eval_runner" does not exist` / `could not connect to server` | **BLOCKED：连接或凭据问题**，不构成权限结论；回 C7 与分支 B2 |
| `ERROR: syntax error at or near …` / `ERROR: column … does not exist` | **BLOCKED：命令抄写缺陷**，改正命令后重跑；不改判据、不改脚本语义 |
| `ERROR: current transaction is aborted, commands ignored until end of transaction block` | 预期副作用（若你在报错后还追加了语句）：事务已 aborted，`ROLLBACK` 会结束它，不是新发现 |

**为什么这三条不会留下 `public.probe_t` 或探针行**：PostgreSQL 的 DDL 与 DML 都是事务性的，`CREATE TABLE` / `INSERT` / `TRUNCATE` 写在 `BEGIN … ROLLBACK` 内，即使权限被放大而实际执行成功，`ROLLBACK`（或会话因意外中断而断开）也会把它回滚掉。复查语句放在事务**之外**，就是把这条保证当成可核对的证据而不是口头假设。若某次复查读到 `probe_t_leftover=1`（例如误用了 `ON_ERROR_STOP=1` 又手工重跑过事务外的版本），按 §1 停止并原样记录，不要自行 `DROP`。

## 8. C9 — 事务内 INSERT/SELECT/UPDATE/DELETE 探针，回滚后全表仍为空

必须显式 `BEGIN … ROLLBACK`（`psql` 默认自动提交，不加 `BEGIN` 的探针会真的留下数据）。探针向量用 1024 维字面量，不经过 Embedding，零 API 成本。宿主机无 `psql`，同样经容器执行；SQL 走引号 here-doc，不需要反斜杠转义。

```bash
# C9.1 正向事务探针（这一步的所有语句都应当成功，所以 ON_ERROR_STOP=1）
docker exec -i -T interview-eval-postgres sh -c \
  'PGPASSWORD="$EVAL_RUNNER_PASSWORD"; export PGPASSWORD; exec psql -h 127.0.0.1 -p 5432 -U eval_runner -d interview_guide_eval -X -q -v ON_ERROR_STOP=1 -f -' \
  <<'SQL'
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
docker exec -i -T interview-eval-postgres sh -c \
  'PGPASSWORD="$EVAL_RUNNER_PASSWORD"; export PGPASSWORD; exec psql -h 127.0.0.1 -p 5432 -U eval_runner -d interview_guide_eval -X -q -f -' \
  <<'SQL'
SELECT count(*) AS total_rows FROM vector_store;
SELECT count(*) AS probe_leftovers FROM vector_store WHERE starts_with(content, '__p1c_perm_probe');
SQL
```
预期：`total_rows=0` 且 `probe_leftovers=0`。（用 `starts_with()` 而不是 `LIKE '__p1c_perm_probe%'`：`_` 在 `LIKE` 里是单字符通配符，会把匹配范围放大到非探针行。`content` 可能为 `NULL`，`starts_with(NULL,…)` 返回 NULL 而非 true，不会漏判也不会误判。）
失败停止：若残留 > 0（例如探针会话被提前中断），**必须**按 `starts_with(content, '__p1c_perm_probe')` 精确删除并复查，直到回到 0；不得留下探针行进入后续评测——Phase 0 的「全表为空」硬性检查会因此把整轮拒掉，而这属于环境污染，不是隔离结论。删除只允许针对该 `content` 前缀，禁止无条件 `DELETE`/`TRUNCATE` 全表。

## 9. C10 — 1024 维列、HNSW `vector_cosine_ops` 索引、扩展版本

```bash
# 以 eval_runner 经容器内 TCP 执行（Java Phase 0 用的就是这几条，口径保持一致）
docker exec -i -T interview-eval-postgres sh -c \
  'PGPASSWORD="$EVAL_RUNNER_PASSWORD"; export PGPASSWORD; exec psql -h 127.0.0.1 -p 5432 -U eval_runner -d interview_guide_eval -X -q -f -' \
  <<'SQL'
SELECT 'atttypmod='||atttypmod FROM pg_attribute JOIN pg_class ON attrelid=oid
  WHERE relname='vector_store' AND attname='embedding';
SELECT indexname, indexdef FROM pg_indexes WHERE tablename='vector_store';
SELECT 'extversion='||extversion FROM pg_extension WHERE extname='vector';
SQL
```
预期：`atttypmod` = 1024（与 `eval.embedding.dimensions` 默认 1024 相等，Java 侧做的是整数相等判断）；`indexdef` 同时含 `using hnsw` 与 `vector_cosine_ops`，索引名 `spring_ai_vector_index`。
失败停止：维度或索引口径不符 → 记录 `indexdef` 原文，**不要**现场手建索引来凑过检查（那会让报告里的 `vectorStoreConfig.indexDef` 与真实初始化脱钩）。`extversion` 只需如实记录；若版本低到不提供 `vector_dims()`，C9 会先失败，按 C9 停止。

## 10. C11 — 收尾状态与记录

```bash
docker compose -f docker-compose-eval.yml --env-file .env.eval ps        # 期望 (healthy)
docker exec -i -T interview-eval-postgres sh -c \
  'PGPASSWORD="$EVAL_RUNNER_PASSWORD"; export PGPASSWORD; exec psql -h 127.0.0.1 -p 5432 -U eval_runner -d interview_guide_eval -X -q -f -' \
  <<'SQL'
SELECT 'vector_store_rows='||count(*) FROM vector_store;
SQL
docker inspect interview-eval-postgres \
  --format 'image={{.Config.Image}} | imageID={{.Image}} | startedAt={{.State.StartedAt}}'
docker volume inspect <C2-post 的卷名> --format 'volumeCreatedAt={{.CreatedAt}}'
```
预期：`vector_store_rows=0`（本清单全程不允许出现非探针写入，探针已回滚）。§12 必须同时记录镜像名、镜像 ID（digest 更佳）、容器 `StartedAt` 与卷 `CreatedAt`——它们是分支 A/B 结论的时间线凭据。停止方式：`docker compose -f docker-compose-eval.yml --env-file .env.eval stop`（保留卷，供复核）。**不要求、也不要**在本轮 `down -v`。
口令收尾：本清单**不再**有 `unset PGPASSWORD` 这一步（宿主机从未设置过任何口令变量，`unset` 一个不存在的变量只会制造「已经清理过」的错觉）。改为在 §12 记录一行「本清单全程未在宿主机 argv / shell 历史 / 记录表里出现口令值」，并核对两件事：宿主机侧没有 export 过 `PGPASSWORD`；§12 里只出现过 `env_present len=` 这种长度信息。

## 11. 验收判据

- C1–C11 全部 PASS，且每项在 §12 有**实测值原文**（不是「符合预期」这类转述）；
- **§5 的「形态冒烟自检」必须留下 `stdin_ok` 原文**（实测行形如 `stdin_ok user=eval_runner`，退出码 0），它是 C6–C11 每一条 SQL 结论的前置：这条没留痕，后面所有「SQL 检查」都可能是 `-i` 缺失导致的空跑，一律不得算 PASS。若命中「无输出 + exit 0」分支，本条与 C6–C11 全部记 **BLOCKED**，并附 `docker --version` / `docker compose version` 原文；
- §5 的分支判定必须写明走了 **A（新卷首次初始化）** 还是 **B（旧卷复用）**，并附卷 `CreatedAt` / 容器 `StartedAt` / up 时刻三个时间点；只有分支 A 且四要件齐备，才允许出现「本次启动执行过初始化脚本」这句话；分支 B 只能写「数据面就绪、init 归属未知」；
- C2 的三方归属（容器 project 标签 / 容器 Mounts 卷名 / 卷 project+volume 标签）必须逐项记录实际字符串；
- C2 还必须写明命中 §3 判定表的哪一档（①/②/③/④）：**状态 ②（卷在、容器不在）**要在 `up` 之前留 C2-pre-vol 快照（`CAND_COUNT`、卷名、两个标签原文、`CreatedAt`），并在 `up` 之后立即补做 C2-post，核对从容器 `Mounts` 读到的卷名与快照**逐字一致**——两条都记录才算归属闭环，只记其一不算 PASS；状态 ④ 只能记 BLOCKED（未 `up`，无法继续 C3 之后各条），不得为了走完清单而 `up`；
- C8 的声明式与行为式两个口径分别记录；三条负向探针必须**各自独立会话**执行，§12 里记下每条的实际错误文本原文、**`session_alive` 行是否出现**与复查计数（缺错误文本或缺会话存活行 = 不能算 PASS；会话存活行缺失一律 BLOCKED 不判 FAIL）；
- C9 回滚后 `total_rows` 与 `probe_leftovers` 都为 0；
- C3 全程只输出过 `--quiet` 的退出码与 ports 一行，未把完整渲染配置写入终端记录、文件或 §12；
- **口令处理判据（本轮定点）**：§1 的存在性探针输出（`env_present len=` 或 `env_ABSENT`）已记入 §12；全程未在任何命令行、进程 argv、shell 历史或 §12 里出现口令值；未运行 `docker inspect` 读 `.Config.Env`、未运行容器内 `env` / `printenv`。若出现过口令值（例如临时退回旧写法），本条记 FAIL 并在备注里写明是哪条命令、口令是否已进入可回看的 shell 历史——不要事后涂改成 PASS；
- 全程未发起 Embedding/HTTP、未运行 `evalP1cReal`、未连 5432 生产库、**未删除任何卷**；
- 结果连同镜像名与 ID（digest 更佳）、`extversion`、容器 ID、卷时间线写回根目录 `PROJECT_PROGRESS.md`（把 §5 对应项从「未验证」移入「真实环境已验证」，附本节记录表）。
以上任一未满足，只能记为「部分验证」，并明确列出仍未覆盖的项。

## 12. 记录表（执行时填写）

```
C1  端口5433空闲            实测:__________  PASS/FAIL  时间:______
C2pre 容器侧输出(有/无)       实测:__________
C2pre-vol 候选卷数 CAND_COUNT  实测:______  卷名:__________  判定档: ① / ② / ③ / ④
C2pre-vol 每个候选卷的两个标签+CreatedAt  实测:__________  (project / volume / VOL_LABEL_MISSING)
C2pre-vol 预期项目名及来源(目录名 / -p / COMPOSE_PROJECT_NAME)  取值:______  来源:______
C2post 容器project标签       实测:__________
C2post 容器Mounts三条        实测:__________  PASS/FAIL
C2post 卷名/CreatedAt/标签   实测:__________  PASS/FAIL
C2post 状态②:up后读到的卷名 == C2pre-vol 初判卷名  实测:______ / ______  一致? 是/否/不适用
C3  config --quiet 退出码     exit=______  ports一行:__________  PASS/FAIL
C4  up+healthcheck+pg_isready 实测:__________  PASS/FAIL  up时刻:______
C5  容器StartedAt/卷CreatedAt/本次窗口日志  实测:__________
C5.1 形态冒烟自检 stdin_ok原文:__________  exit=______  分支判定: ①继续/②BLOCKED/③凭据分支  docker --version:______  docker compose version:______
口令探针 env_present len= / env_ABSENT  实测:______(只记长度，不记值)  PASS/BLOCKED
C6.0 unix socket auth_method  实测:__________（trust / scram / md5）
C6.1 tables / roles / ext 三行 实测:__________  PASS/FAIL  人工确认人(若需重建):______
C7  db/user/标记/server_port/空表/client_addr  实测:__________  PASS/FAIL  容器ID比对:______
C8a 声明式权限(含 truncate=f)  实测:__________  PASS/FAIL
C8b-1 INSERT标记表 session_alive行:有/无 + 错误文本+复查计数  实测:__________  PASS/FAIL/BLOCKED
C8b-2 CREATE TABLE session_alive行:有/无 + 错误文本+leftover   实测:__________  PASS/FAIL/BLOCKED
C8b-3 TRUNCATE session_alive行:有/无 + 错误文本+vs_rows        实测:__________  PASS/FAIL/BLOCKED
C9  事务探针 dims=1024 + 回滚后 total/probe_leftovers=0  实测:__________  PASS/FAIL
C10 atttypmod/indexdef/extversion      实测:__________  PASS/FAIL
C11 收尾(空表/镜像名与ID/时间线/stop未删卷) 实测:__________  PASS/FAIL
未发起 Embedding / 未跑 evalP1cReal / 未 down -v   确认:______
口令处理：宿主机 argv / shell 历史 / 本表全程无口令值；未读 .Config.Env、未跑 env|printenv   确认:______
分支: A新卷(状态①) / B旧卷(状态②或已有卷) / 未 up(状态④，记 BLOCKED)   判定:______
```

## 13. 引用点

- 代码：`app/src/test/java/interview/guide/eval/P1cRealRetrievalEvalTest.java`（`verifyIdentity()` / Phase 0 维度与索引 SQL / Phase 5 清理用 `DELETE` 带 `eval_run_id` 谓词 / `requireCredential("eval.datasource.password","EVAL_RUNNER_PASSWORD")`）
- 配置：`docker-compose-eval.yml`（`${...:?}` 强制两个口令、`127.0.0.1` 回环绑定、两个 init 文件）、`docker/postgres/eval-init.sql`（扩展 + 标记表 + `vector_store(metadata json)` + HNSW `vector_cosine_ops`；**不含用户/授权**）、`docker/postgres/eval-user.sh`（角色创建与授权，`WHERE NOT EXISTS` 守卫）、`app/build.gradle`（`evalP1cReal` 只在显式 `-P` 时转发）
- 设计：`P1C-L1-DESIGN.md` §2 compose 与 init 分工、§3 隔离与身份核验、§7 Phase 0、§13 运行前检查清单、§14 失败时清理规则、§16「v1.4 文档定点修订」
- 进度：仓库根 `PROJECT_PROGRESS.md` §3 B5/B6/B7、§4 T4 前置条件、§5 执行边界

## 14. 本轮（2026-09-30 离线文档轮）定点修订记录

按复核意见逐条对应，全部只改本文档；未启动容器、未连数据库、未调用 Embedding、未运行 `evalP1cReal`、未删除任何卷。#1–#5 是这一轮的第一次定点修订，#6–#7 是收到复核追加意见后的第二次，**#8 是本轮（R3）对 #7 形态改造自身缺陷的第三次定点**（下表与 §14.1 的 R1/R2/R3 轮次标注与之一一对应；#8 的两条不是复核意见，是本轮静态核验新发现）。

| # | 复核意见 | 修订 |
|---|----------|------|
| 1 | 卷标签判据不成立 | C2 改为读 `.Labels`，用 `com.docker.compose.project` + `com.docker.compose.volume` + 容器实际 `Mounts` + 容器 project 标签做三方归属；**取消**对卷上 `project.config_files` 的要求，并说明该标签属于容器/网络层 |
| 2 | 负向探针要可回滚、可区分拒绝原因、不留残留 | C8b 拆成三条独立会话、各自 `BEGIN … ROLLBACK`；每条后附事务之外的残留复查；给出 PASS / FAIL / BLOCKED 分类表（按错误文本 + `session_alive` 行 + 复查计数三重判据）；说明 PostgreSQL 的 DDL 事务性使 `public.probe_t` 不会留下，并解释上一版共用事务会造成「后两条没跑却像全被拒」的假 PASS |
| 3 | 不得输出完整配置或环境变量值 | C3 只留 `config --quiet` 与「python 只取 ports」两条命令，删除原 `config \| grep`；新增 §1 硬边界与 `--no-interpolate` 替代路径 |
| 4 | 对象存在 ≠ 本次执行过 init | §5 拆成两个命题表 + 分支 A（新卷四要件）/ 分支 B（旧卷 B1–B5 检查），要求记录卷 `CreatedAt`、容器 `StartedAt`、up 时刻与 `--since 15m` 的本次窗口日志；`down -v` 仍只作为人工确认后的手工动作，并补项目名核对要求 |
| 5 | 静态检查命令的目标环境可执行性 | 上一轮实测：宿主机**无 `psql`、无 `jq`**，Docker daemon 未运行 → C6/C7/C8/C9/C10/C11 的 SQL 全部改为经 `docker exec -T` 在容器内执行 `psql`，凭据核验走容器内 TCP（并解释 unix socket 可能是 `trust`，socket 成功证明不了口令）；JSON 过滤统一用 `python`；所有 `docker compose` 子命令补 `--env-file .env.eval`；口令改为宿主机静默读取 + `export`（**这一处理方式已被下面 #7 取代**） |
| 6 | C2-post 无法先于 `up` 执行：「卷存在但容器不存在」是常见状态，而 C2-post 依赖容器 | §3 新增 **C2-pre-vol**（`docker volume ls` 交给 python 只留名字等于 `eval_postgres_data` 或以 `_eval_postgres_data` 结尾的条目，再对每个命中项单独 `docker volume inspect`，只取两个 compose 标签与 `CreatedAt`）+ **四状态判定表**（①无容器无卷→分支 A；②无容器+恰 1 候选卷→分支 B，C2-post 顺延到 `up` 后立即补做并逐字比对卷名；③容器存在+恰 1 候选卷→可先做 C2-post；④候选卷 ≥ 2 / project 标签不符 / 标签缺失→立即停止不 `up`，理由是 `up` 可能新建第三个卷而 `down -v` 按项目名删卷）；写明「预期项目名」的来源与不得越界（默认为 Compose 文件所在目录名、可被 `-p`/`COMPOSE_PROJECT_NAME` 覆盖、**不**为取项目名另跑 `config`、`up` 后以容器 Labels 实测值闭环、前缀规则属推导）；§5 分支 A/B 与 §11/§12 做对应交叉引用（未重写 §5 既有证据要求） |
| 7 | `docker exec` 用 `-e` 把宿主机口令变量透进容器，会由宿主机先展开成明文并留在 `docker` 进程 argv 里 | §1 删除宿主机口令录入，改为**只打印长度**的存在性探针（`env_present len=` / `env_ABSENT` 即停止）；C6.0(scram 分支)、C7、C8b 六条、C9、C10、C11 的 `eval_runner` SQL 统一为 `docker exec -T … sh -c 'PGPASSWORD="$EVAL_RUNNER_PASSWORD"; … exec psql … -f -'` + 顶格 here-doc（外层单引号避免宿主机展开，SQL 走 here-doc 避免单引号冲突，`exec` 顶替 sh 进程，原 `ON_ERROR_STOP=0/1` 选择按语义保留）；**并修正 `-T` 不转发 stdin 的形态缺陷（给读 here-doc 的命令补 `-i`，见下面 #8 与 §14.1 的 R3）**；C6.0/C8a 的 `postgres` 超管项保持容器内 socket 免密形态；新增两条硬禁令（读 `.Config.Env`、容器内 `env`/`printenv`）；C11 取消 `unset` 改为记录「宿主机 argv / shell 历史 / 记录表无口令值」；§11/§12/§0 同步。**已知遗留**：`P1C-L1-DESIGN.md` §13 表里仍留着旧的 `-e` 透传写法，本轮按边界未动 |
| 8 | 本轮（R3）静态核验**新发现**的两条缺陷，均由上面 #7 那次形态改造引入或暴露：① #7 把 SQL 从 `-c "…"` 改成 here-doc + `-f -`，却沿用 `docker exec -T`——`-T` 只关 TTY，**不转发 stdin**，于是 `-f -` 立刻遇到 EOF：不输出任何行、不报错、退出码 0，**整份清单的 SQL 检查会静默假 PASS**（这是本清单最坏的一类生成器：它不是某条判据错了，而是让所有判据同时失去取证能力）；② §7 (b) 的判据第 2 条要求「`session_alive …` 那行必须先出现」，但正文只给 probe-1 写了这行，probe-2/3 永远无法满足该判据（且在 ① 修好之前，这个缺失恰好还会掩盖「会话根本没连上」） | ① 13 条读 stdin 的命令（判定口径：单引号程序文本里含 `-f -` 且其后紧跟带引号定界符的 here-doc）逐条改为 `docker exec -i -T`，**不读 stdin 的保持 `-T`**：§1 的 `env_present len=` 存在性探针、C6.0/C6.1/C8a 的 `psql -U postgres … -c "SELECT …"`（容器内 unix socket 免密、超管声明式查询）、C6.0 兜底的 `cat pg_hba.conf`——按规则逐条判断，未做全局替换；§1 补「为什么必须 `-i`」的说明（引 Docker CLI 对 `-i/--interactive` 的原文措辞、标注其为**推理**且今晚由自检实测证伪、写明 `-T` 与 stdin 无关、以及某版 CLI 拒收 `-T` 时按形态缺陷停不许现场发挥）；§5 末新增 **C5.1 形态冒烟自检**（强制，排在一切权限/身份/维度类 SQL 之前；三分支判据：`stdin_ok` 原文 = 继续 / **零输出 + exit 0 = BLOCKED 并记 `docker --version`** / `FATAL:`、`could not connect` = 回 C6-C7 凭据分支）；② 同一行会话存活 SQL 逐字加到 probe-2、probe-3 的 `BEGIN;` **之前**（事务外的自动提交语句，不把回滚语义带进探针），§7 写明它的唯一作用是证明「会话活着 + SQL 真送到了 psql」、缺失即判 BLOCKED 不判 FAIL，判定表新增「零输出 + exit 0 = 形态缺陷」一行；§11 加「必须留下 `stdin_ok` 原文」的验收项、§12 加 C5.1 行与三条探针的「session_alive 行:有/无」列、§0 新增两条形态断言（31→33）。**上一轮 R2 的干跑之所以「13/13 通过」，是因为它那个假 `docker` 无条件把 stdin 交给假 `psql`——它验的是自己设计的模型，不是 CLI 的真实语义，恰好没覆盖 ①；本轮把 stdin 转发语义本身作为干跑对象（见 §14.1 R3 的对照实验）** |

### 14.1 验证命令与退出码（零容器、零网络）

下表逐行标注轮次：**R1** = 更早那次静态核验（未重跑，结论不外推到之后的改动）；**R2** = #6/#7 两项定点完成时的实测；**R3** = 本轮 #8（补 `-i` + 三条探针的会话存活行）之后同一批命令的实际重跑。R2 各行里的 31 / 23 / 13 是**当时文本**的计数，#8 改动后已分别变为 33 / 24 / 14（以下方 R3 行为当前值）。

| 轮次 / 命令 | 退出码 | 结果 |
|------|--------|------|
| R2 §0 的 `python - <<'PY' … PY` 断言块（只读文件，用 `C:\Python313\python.exe` 真跑，cwd = 仓库根） | 0 | **31 项断言 → 31 PASS / 0 FAIL**（上一轮为 25 项）；here-doc 开闭配对 `13/13`；`--env-file` 违例 = 无。**这是 #8 之前的计数，同一命令本轮 #8 之后重跑为 33 项，以 R3 行为当前值** |
| R2 逐个 bash 围栏代码块喂 `bash -n`（Git Bash：`C:\Program Files\Git\bin\bash.exe`） | 0 | **23 个块 / 0 处语法错误**（上一轮 18 个块；本轮新增 C2-pre-vol 两条与「一条会话一个块」的拆分。#8 加 C5.1 后为 24 块，见 R3） |
| R2 §1 存在性探针 + 正文全部 13 条 here-doc 命令的**离线干跑**：定义 shell 函数 `docker` 模拟 `docker exec`、PATH 前置一个假 `psql` 脚本、容器侧环境给哨兵口令 | 0 | 14/14 通过：`docker` 收到的 argv 里只有变量名的字面文本（哨兵口令 0 命中）；假 `psql` 拿到 `-f -` 且 here-doc 的 SQL 全文进了 stdin；容器侧 `PGPASSWORD_is_set=yes len=12`（证明展开发生在容器内、不在宿主机）；探针两个分支分别输出 `env_present len=12` 与 `env_ABSENT`。**该模型的局限正是 #8 ① 漏网的原因**：那个假 `docker` 无条件把 stdin 交给假 `psql`，等于把「CLI 是否转发 stdin」这个待验命题当成了假设——本轮 R3 换成按 `-i` 有无来分流 stdin 的模型 |
| R2 C2-pre-vol 的两条 `python` 过滤器，输入为**合成的**假 `docker volume ls` / `docker volume inspect` 输出（其中一份埋哨兵标签、一份故意把 Labels 给成 `null`） | 0 | 候选卷数与命中名符合预期（精确名与 `_eval_postgres_data` 后缀都收，无关卷不收）；只打印卷名、`CreatedAt` 与两个 compose 标签，哨兵标签没出现；`Labels=null` 不崩且 `VOL_LABEL_MISSING` 列全两个标签——正是判定档 ④ 的输入形状 |
| R2 `date '+%Y-%m-%dT%H:%M:%S%z'` | 0 | `2026-09-30T19:20:42+0800`（C4/C5 记录的时间戳格式据此确定） |
| R2 `netstat -ano \| grep ":5433" \| grep LISTEN`（C1 预跑，非今晚正式执行） | 1 | 无匹配 = 端口空闲；1 是 grep 的「无命中」码，不是失败；今晚仍须重跑（状态会变） |
| **R3** §0 断言块（#8 之后重跑，命令与 R2 首行同） | 0 | **33 项断言 → 33 PASS / 0 FAIL**（#8 前 31 项）；here-doc 开闭配对 **`14/14`**；`--env-file` 违例 = 无；新增两条形态断言均 PASS：`-f -` ⇒ `-i` 违例 = 无（正文 14 条读 stdin 的命令全部带 `-i`）、会话存活行 **3/3** |
| **R3** 逐个 bash 围栏代码块喂 `bash -n`（Git Bash `C:\Program Files\Git\bin\bash.exe`，子进程字节模式 + `errors='replace'`） | 0 | **24 个块 / 0 处语法错误**（R2 为 23；新增的一块就是 C5.1 形态冒烟自检） |
| **R3** **stdin 语义对照干跑**（本轮关键证据）：假 `docker` 函数按「参数里有没有 `-i`」决定转发还是丢弃 stdin，丢弃用 `< /dev/null` 建模「不 keep open = 立刻 EOF」；假 `psql` 读 stdin 后按 SQL 里的字面量回显 | 0 | 同一条 C5.1 命令只差 `-i`：**带 `-i -T`** → 假 psql `stdin_bytes=39`、stdout 输出 `stdin_ok user=eval_runner`、exit 0；**只有 `-T`**（#8 修前的形态）→ 假 psql `stdin_bytes=0`、**stdout 零字节、exit 仍为 0**，缺陷 1 的静默假 PASS 症状完整复现，即今晚 C5.1 分支 ② 的输入形状。顺带复查假 `docker` 的 argv 记录：哨兵口令 0 命中，只有变量名的字面文本（补 `-i` 没有把口令带回 argv） |
| **R3** 新断言的**反向对照**（只在内存副本上做，文件未改动）：① 把 14 条 `-i -T` 改回 `-T` → 断言报 **14 条违例**；② 删掉 probe-2/3 的会话存活行 → 计数从 3 降到 **1**，`>=3` 断言 FAIL | 0 | 两条新断言**不是恒真**：回归时会立刻响，且违例片段打进消息便于定位。（上一轮 R2 的干跑正是缺这层反向对照，才让 `-i` 缺失漏网） |
| R1 C3.2 与 C2-post 的三条 `python -c` 过滤器（合成假 JSON，内含哨兵字段 `SECRET`） | 0 | 输出形状符合预期、`SECRET` 未出现 → 只打印 ports/labels/mounts。本轮未改动这三条命令，故未重跑 |
| R1 `docker info` | 非 0 | `failed to connect to the docker API at npipe:////./pipe/dockerDesktopLinuxEngine` → Docker Desktop 未运行，属 T4 前置条件。**本轮按边界没有重跑，也没有启动它** |

上一轮静态核验发现的两处文档缺陷，现状不同：宿主机静默读取那一处（把提示语写进变量名，属 ksh/zsh 专有语法，Git Bash 的 bash 报 `not a valid identifier`）随着本轮把宿主机口令录入整体删除而**连写法带回归断言一起退出正文**；C7 曾引用的不存在的列 `client_host`（正解 `client_addr`）上一轮只改了命令，本轮把 §6 预期行里残留的同一处旧词一并改正。本轮新增的形态断言会直接盯住两种旧写法：`docker exec` 的 `-e` 口令透传、以及宿主机侧任何静默读取录入——正文只在「描述缺陷」处提到它们，引用缺陷一律用描述而不是原样粘贴，否则这些断言会被自己的变更记录假 FAIL。

自指风险说明：本节的命令文本会被 §0 断言块扫到，所以断言只作用于「去掉自检脚本自身的清单正文」（`kk`）。上一轮曾因脚本搜索词匹配到自己而产生假 FAIL，也曾因变更记录里引用旧字面量而假 FAIL——引用缺陷时用描述而不是原样粘贴。`kk` 的剔除范围只覆盖 `python - <<'PY'` 到顶格 `PY` 之间，本节表格里对这条命令的**行内引用**不在剔除范围内，所以它同样受断言约束（例如这里出现的 `PGPASSWORD="$EVAL_RUNNER_PASSWORD"` 是本轮的合法新形态，而旧写法一律只用文字描述）。
