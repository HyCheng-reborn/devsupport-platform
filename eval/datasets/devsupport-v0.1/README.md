# devsupport-v0.1 — 研发项目知识助手检索评测「开发集草案」

> 状态：**draft（开发集草案）**。**不是独立测试集**，不作为最终评测基线。
> P1-B v3.1 修订完成：OfflineChunker 已统一走生产解析路径（`DocumentParseService.parseContent()` = Tika + clean），多 chunk 判定改为交集定义，重复运行校验比较全部 4 个工件并在 finally 中恢复，Tika 验证退出码据实记录为 1。未调用 Embedding / Chat / Rerank，未启动应用或基础设施，未上传入库，未改生产代码，未实现 Hybrid。

## 1. 目的

为「研发项目知识助手」（文档理解、配置/接口查询、故障排查）准备一批**真实文档语料**与**问题草案**，供后续离线检索评测（P1-A 指标核心已就绪）在真实语料上做开发迭代。

## 2. 来源与冻结信息

- 仓库：InterviewGuide（当前工作区）。
- 快照提交 `snapshotCommit`：`d2fd5e633248b2c008cf387ab82651a56cca8502`（P1-A 提交后的 HEAD）。
- 每份文档另记 `sourceCommit`（该文件最近一次改动提交，`git log -1`）。
- 源许可证：AGPL-3.0（见根 README「许可证」）。
- 语料为仓库文档的**逐字节副本**（`cp`，未改写、未删节、未重排）。已用 sha256 校验副本与原文件一致。

## 3. 准备方式

1. 从仓库中筛选与「启动/部署、配置/数据库/Redis/对象存储、文档上传与知识库使用、项目结构与故障排查」相关的**真实文档**，优先 README、部署说明与业务技术文档。
2. 整文件复制到 `corpus/`，文件名使用稳定 `docId`（`excerptMode=whole-file`，故 `excerptSha256 == originalSha256`）。
3. 在 `corpus-manifest.json` 记录 docId、原始路径、来源提交、原文件与节选 sha256、节选范围、行/字符数、主题。
4. 在 `queries.draft.json` 出 20 道题，每题带稳定 queryId、question、answerability、题型；可答题给出 evidence（docId + **原文引句** + 章节 + 行号）与预期答案要点。
5. P1-B 修订：用 Java 生产类（`DocumentParseService.parseContent()` → Tika + `TextCleaningService.cleanText()` + `TokenTextSplitter.builder().build()`）完成离线切分（28 chunks），建立 evidence→chunk 映射（51/51 已映射，0 跨 chunk），整理 16 道可答题候选 gold。
6. P1-B v3 修订：修正 q01/q03 `supportsAnswerPoints`、补充 q07 SpringDoc 证据、实测 Tika 差异（6/7 无影响，ig-readme-root 有 107 字符 HTML 标签差异）、重复运行校验改为实际可执行比较、离线工具移到 test 作用域。
7. P1-B v3.1 修订：OfflineChunker 统一走生产解析路径（`DocumentParseService.parseContent(byte[], fileName)`），不再跳过 Tika；多 chunk 判定改为交集定义（11 道多要点题中 4 道需多 chunk 组合）；重复运行校验比较全部 4 个工件并在 finally 中恢复；Tika 验证退出码据实记录为 1。

### 明确排除（不作为语料）

- `.env` / `.env.example`（密钥占位与配置）、日志、个人资料、构建产物、缓存。
- `AGENTS.md`、`CLAUDE.md`、`.claude/rules/*.md`（**Agent 操作指令**，非产品知识）。
- `frontend/README.md`（Vite 官方模板样板文，无项目专有内容）。
- `app/src/main/resources/skills/**`（面试题库/技能参考，非本项目研发文档）。
- `interview-guide_本机环境与启动关闭说明.md`（本机环境说明，未跟踪）。

## 4. 语料清单（7 份，合计 39076 字符 / 1415 行 / 58266 字节）

| docId | 原始路径 | 主题覆盖 | 字符 | sourceCommit |
| --- | --- | --- | --- | --- |
| ig-readme-root | README.md | 启动/部署、配置、DB、Redis、对象存储、知识库、结构、FAQ | 19800 | 7ee0ff2 |
| ig-voice-arch | docs/voice-interview-architecture.md | 架构、配置、部署、API、语音、FAQ | 10902 | 28e97a3 |
| ig-db-migration-readme | app/src/main/resources/db/migration/README.md | 数据库、Flyway、配置 | 472 | f42f750 |
| ig-setup-api-keys | SETUP_API_KEYS.md | 密钥配置、语音模型、验证、FAQ（仅占位密钥，无真实密钥） | 2535 | 28e97a3 |
| ig-githooks-readme | .githooks/README.md | 开发流程、提交规范、命令 | 343 | f42f750 |
| ig-kb-question-gen-async-tdd | .claude/tdd/knowledge-base-question-generation-async.tdd.md | 知识库题目异步生成可靠性 | 3165 | a63bb34 |
| ig-kb-interview-capacity-tdd | .claude/tdd/knowledge-base-interview-capacity.tdd.md | 知识库面试严格追问容量、错误码 | 1859 | 0b99aaa |

> 关于两份 `.claude/tdd/*.md`：它们是**工程/TDD 证据文档**（描述功能行为、状态机、错误码与测试保证），**不是 Agent 操作指令**，故纳入产品知识语料；被排除的是 `AGENTS.md` 与 `.claude/rules/*`。若评审认为整个 `.claude/**` 都应排除，则语料降到 5 份（<6），需另选替代来源——见「待审核事项」。

## 5. 问题草案（20 道）

- 题型分布：精确查找 6 / 自然语言改述 6 / 多证据组合 4 / 无答案 4。
- answerability：ANSWERABLE 16 / NO_ANSWER 4。
- evidence 总数：51 条（含 5 条 nearMissEvidence）。
- 标注原则：
  - **预期答案只能来自选定语料**，不用模型常识补全。
  - **chunkId 已建立**：用生产 Java 类离线切分后，evidence 已映射到具体 chunkId（见 `evidence-chunk-map.json`）。
  - **多要点 vs 多 chunk 区分**：11 道题有多个答案要点（`multipleAnswerPoints`），其中 4 道需要多 chunk 组合（`multipleChunkCombinationRequired`，判定标准：各要点 supportingChunkIds 的交集为空），7 道虽有多要点但存在单个 chunk 同时属于所有要点的支持集合。
  - 无答案题遵循「不把'暂时没找到'当无答案」：每题写明语料**缺少什么**（`missingFromCorpus`）并指出真实答案位于语料之外的何处（`answerSourceOutsideCorpus`）。

### 无答案题依据（已核对源码，确认真实答案在语料之外）

| queryId | 问题要点 | 语料缺失 | 真实答案所在（非语料） |
| --- | --- | --- | --- |
| q17 | RAG 相似度阈值默认值与配置项名 | 阈值/TopK 具体数值与键名 | `application.yml`：`APP_AI_RAG_TOPK_SHORT/MEDIUM/LONG` 默认 20/12/8；阈值需查检索源码 |
| q18 | 向量化 chunk 大小与 overlap | chunk/overlap 数值 | `KnowledgeBaseVectorService.java:51`（TokenTextSplitter 默认，约 800 tokens、无重叠） |
| q19 | MinIO createbuckets 具体命令与访问策略 | 初始化脚本内容 | `docker-compose.yml:102-116`（`mc mb` / `mc anonymous set public`） |
| q20 | 语音 WS 各消息类型完整字段结构 | payload 字段定义 | 源码 `VoiceInterviewWebSocketHandler` 与前端 WS 类型定义 |

## 6. 文档与源码不一致 / 需人工确认（未擅自改原文）

1. **前端指南引用失配（较明确）**：`ig-voice-arch` 第 546 行「相关文档」把 `../frontend/README.md` 标为「前端开发指南」，但该文件实际是 **Vite 官方模板样板文**（React+TypeScript+Vite 通用说明），**无任何项目专有前端指导**。→ 已因此把 `frontend/README.md` 排除出语料；建议评审决定是否修正该引用或补写真实前端文档。
2. **音频编码 opus vs pcm（待确认，非必然矛盾）**：`ig-voice-arch` 音频规格块第 493 行 `codec: opus`，而 ASR/TTS 配置（457、469 行）`format: pcm`。可能分别描述「WebSocket 传输编码」与「模型输入格式」，需人工确认是否一致。
3. **PostgreSQL 版本表述（非矛盾，仅记录）**：`ig-readme-root` 环境要求写「PostgreSQL 14+」，同文件技术栈表写「Compose 默认 PG16」；`docker-compose.dev.yml` 实际镜像为 `pgvector/pgvector:pg16`。14+ 为最低要求、PG16 为默认，二者不冲突。
4. **Redis 版本表述（非矛盾，仅记录）**：README 写「Redis 6+」，`docker-compose.dev.yml` 实际 `redis:7`，符合「6+」。

## 7. Tika 差异实测结果

通过 `TikaDifferenceVerifier.java` 逐文件对比 `cleanText(rawText)` 与 `parseContent(rawBytes, fileName)`（Tika + clean）：

| 文档 | 结果 | 说明 |
|------|------|------|
| ig-db-migration-readme | PASS | 完全一致 |
| ig-githooks-readme | PASS | 完全一致 |
| ig-kb-interview-capacity-tdd | PASS | 完全一致 |
| ig-kb-question-gen-async-tdd | PASS | 完全一致 |
| ig-readme-root | **DIFF** | cleanOnly=18008 vs tikaThenClean=17901，差 107 字符 |
| ig-setup-api-keys | PASS | 完全一致 |
| ig-voice-arch | PASS | 完全一致 |

**差异原因**：Tika AutoDetectParser 将 ig-readme-root.md 开头的 `<div align="center">...</div>` HTML 标签剥离，语义内容不受影响。

**决策**：OfflineChunker 已统一走生产解析路径（`DocumentParseService.parseContent(byte[], fileName)` = Tika + clean），与生产上传完全一致。`TikaDifferenceVerifier` 退出码 1 表示存在差异，已据实记录在 `chunk-manifest.json` 的 `parsingPolicy.tikaVerification.exitCode` 中。

## 8. 待审核事项

- [ ] 是否接受把 `.claude/tdd/*.md` 作为产品知识语料（若否，需补充第 6 份非 `.claude` 文档）。
- [ ] 4 道无答案题的判定是否认可（真实答案确在语料之外，语料仅定性提及相关主题）。
- [ ] Tika 差异处理：ig-readme-root.md 被 Tika 剥离 107 字符 HTML 标签，OfflineChunker 已统一走生产解析路径（Tika + clean），退出码 1 已据实记录，是否可接受。
- [ ] 是否需要为长文档（README、voice-arch）按标题做章节级切分，以提升检索区分度（当前为整文件切分）。

## 9. 目录结构

```
eval/datasets/devsupport-v0.1/
├── corpus/                      # 7 份文档逐字节副本（docId.md）
├── corpus-manifest.json         # docId/路径/来源提交/sha256/范围/主题
├── queries.draft.json           # 20 道问题草案 + evidence 标注（51 条）
├── chunks.jsonl                 # 28 个 chunks（生产解析路径 Tika + clean + 切分）
├── chunk-manifest.json          # 切分配置与文档统计（含 Tika 验证结果，exitCode=1）
├── evidence-chunk-map.json      # 51 条 evidence→chunk 映射
├── candidate-gold.json          # 16 道可答题候选 gold
├── README.md                    # 本文件
├── P1B-REPORT.md                # P1-B v3.1 修订完成报告
├── evidence_chunk_mapper.py     # 证据映射工具
├── candidate_gold_builder.py    # 候选 gold 整理工具（v3.1：交集定义多 chunk）
└── validate_p1b.py              # 综合验证脚本（v3.1：7 项检查，含完整流水线重复运行 + Tika 退出码）

app/src/test/java/interview/guide/eval/
├── OfflineChunker.java          # Java 离线切分工具（test 作用域，走生产解析路径）
└── TikaDifferenceVerifier.java  # Tika 差异验证工具（test 作用域）

app/build.gradle
├── evalOfflineChunk task        # Gradle JavaExec：运行离线切分
└── evalVerifyTika task          # Gradle JavaExec：验证 Tika 差异
```

### 运行命令

```bash
# 离线切分
./gradlew :app:evalOfflineChunk

# Tika 差异验证
./gradlew :app:evalVerifyTika

# 证据映射
cd eval/datasets/devsupport-v0.1
python evidence_chunk_mapper.py

# 候选 gold 整理
python candidate_gold_builder.py

# 综合验证（7 项检查，含完整流水线重复运行 + Tika 退出码）
python validate_p1b.py
```

> 本草案**暂不提交**，与 P1-A 提交分开，等待审核。
