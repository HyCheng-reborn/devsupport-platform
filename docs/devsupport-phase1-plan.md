# DevSupport 第一阶段改造方案

> 调研基线: `4fd2de0`（方案修订: `5c2426d`）
> 本方案只读调研产出，未修改任何代码。所有结论均有源码核实依据。

---

## 一、当前调用链与可复用能力

### 1.1 文档入库调用链（完整可复用）

```
用户上传 → KnowledgeBaseController.uploadKnowledgeBase()
  ├─ 同步阶段: FileValidation → Tika解析(DocumentParseService) → SHA-256去重(FileHashService)
  │           → RustFS/S3存储(FileStorageService) → DB保存元数据(PENDING)
  └─ 异步阶段 (Redis Stream):
       VectorizeStreamConsumer → KnowledgeBaseVectorService.vectorizeAndStore()
         → TokenTextSplitter 分块(~800 tokens) → DashScope text-embedding-v3 生成1024维向量
         → pgvector vector_store 写入(HNSW + COSINE) → 状态更新 COMPLETED
```

**涉及核心类**:
- [KnowledgeBaseUploadService](../app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseUploadService.java)
- [KnowledgeBaseVectorService](../app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseVectorService.java)
- [DocumentParseService](../app/src/main/java/interview/guide/infrastructure/file/DocumentParseService.java)
- [VectorizeStreamConsumer](../app/src/main/java/interview/guide/modules/knowledgebase/listener/VectorizeStreamConsumer.java)
- [VectorRepository](../app/src/main/java/interview/guide/modules/knowledgebase/repository/VectorRepository.java)

### 1.2 RAG 问答调用链（可复用，需增强来源标注）

```
RagChatController.sendMessageStream()
  → RagChatSessionService → KnowledgeBaseQueryService.answerQuestionStream()
    ├─ 查询改写(LLM rewrite) → 动态 topK/minScore 检索
    ├─ vectorService.similaritySearch(query, kbIds, topK, minScore)
    ├─ 构建 context = 拼接检索文档
    ├─ System Prompt(knowledgebase-query-system.st) + User Prompt(knowledgebase-query-user.st)
    └─ LLM 流式生成(dashscope/qwen3.5-flash) → SSE 返回
```

**涉及核心类**:
- [KnowledgeBaseQueryService](../app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseQueryService.java)
- [RagChatSessionService](../app/src/main/java/interview/guide/modules/knowledgebase/service/RagChatSessionService.java)
- [KnowledgeBaseQueryProperties](../app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseQueryProperties.java)

### 1.3 可直接复用的能力清单

| 能力 | 实现位置 | 复用程度 | 源码依据 |
|------|----------|----------|----------|
| 文档上传/解析/去重 | `KnowledgeBaseUploadService` + `infrastructure/file/` | 100% 复用 | `KnowledgeBaseUploadService.uploadKnowledgeBase()` |
| 异步向量化 (Redis Stream) | `VectorizeStreamProducer/Consumer` | 100% 复用 | `VectorizeStreamConsumer.consume()` → `KnowledgeBaseVectorService.vectorizeAndStore()` |
| pgvector 向量检索 | `KnowledgeBaseVectorService` + `VectorRepository` | 100% 复用 | `KnowledgeBaseVectorService.similaritySearch()` 返回 `List<Document>` |
| RAG 流式问答 | `KnowledgeBaseQueryService` + `RagChatSessionService` | 需增强来源返回 | `KnowledgeBaseQueryService.answerQuestionStream()` → `ChatClient.prompt().stream().content()` |
| 多会话聊天管理 | `rag_chat_sessions` + `rag_chat_messages` | 100% 复用 | `RagChatSessionService.completeStreamMessage()` |
| 知识库 CRUD/分类/搜索 | `KnowledgeBaseListService/DeleteService` | 100% 复用 | `KnowledgeBaseController`（14 个端点），分类：`getAllCategories()` → `SELECT DISTINCT k.category` |
| LLM 多 Provider 管理 | `LlmProviderRegistry` | 100% 复用 | `LlmProviderRegistry.getChatClientOrDefault()` |
| 前端知识库管理页 | `KnowledgeBaseManagePage` | 100% 复用 | `frontend/src/pages/KnowledgeBaseManagePage.tsx` |
| 前端 RAG 聊天页 | `KnowledgeBaseQueryPage` | 需改 UI 文案 | `frontend/src/pages/KnowledgeBaseQueryPage.tsx` |
| 前端上传页 | `KnowledgeBaseUploadPage` | 100% 复用 | `frontend/src/pages/KnowledgeBaseUploadPage.tsx` |
| Prompt 模板 (3个KB相关) | `prompts/knowledgebase-*.st` | 需改写为 DevSupport 场景 | `resources/prompts/knowledgebase-query-system.st` 等 |

### 1.4 不可复用的能力（第一阶段移除）

| 能力 | 涉及模块 | 处置 |
|------|----------|------|
| 文字面试 | `modules/interview/` | 后端保留但不暴露入口 |
| 面试安排 | `modules/interviewschedule/` | 后端保留但不暴露入口 |
| 语音面试 | `modules/voiceinterview/` | 后端保留但不暴露入口 |
| 简历管理 | `modules/resume/` | 后端保留但不暴露入口 |
| 知识库题目生成 | `KnowledgeBaseQuestion*` | 后端保留但不暴露入口 |
| 知识库面试 | `KnowledgeBaseInterviewController` | 后端保留但不暴露入口 |
| 前端面试相关页面 | 6+ 页面 + 组件 | 从导航移除，暂不删除 |

---

## 二、第一阶段功能边界与用户操作流程

### 2.1 功能边界

**纳入第一阶段**:
- 研发文档/故障现象/排查步骤的知识库上传与管理
- 基于知识库的 RAG 检索问答（带来源引用）
- 多会话聊天（支持绑定多个知识库、历史记录、置顶）
- 文档分类管理（保留现有 category 字段，自由文本，不新增分类体系）

**明确排除**:
- 面试/简历/语音相关功能（仅隐藏入口，不删除代码）
- 题目生成与题库管理（隐藏入口）
- 工单/事件系统、runbook、oncall（后续阶段）
- P1-C 真实 L1 评测执行（独立于 E2E 冒烟，需 Gate 0a-0d 全部通过）

### 2.2 用户操作流程

```
1. 用户上传研发文档（PDF/DOCX/TXT/MD）
   → 系统自动解析(Tika) → 去重(SHA-256) → 存储(RustFS) → 异步向量化(Redis Stream + DashScope)
   → 前端轮询显示向量化进度

2. 用户在知识库管理页面查看文档列表
   → 按分类筛选 / 按名称搜索 / 按状态排序
   → 查看向量化状态（PENDING → PROCESSING → COMPLETED）

3. 用户进入"问答助手"页面
   → 左侧选择/新建会话
   → 右侧勾选要查询的知识库（支持多选）
   → 中间输入问题 → 流式获取带来源的回答
   → 回答中标注引用来源（文档名 + 相关片段）

4. 会话管理
   → 修改标题 / 置顶 / 切换绑定知识库 / 删除
```

### 2.3 与原"面试指南"场景的具体区别

| 维度 | 原 interview-guide | 改造后 DevSupport Phase 1 |
|------|--------------------|---------------------------|
| **数据模型** | 14张表（含面试/简历/语音6张表），`knowledge_base_questions` 含大量面试题目字段 | 核心使用4张表：`knowledge_bases`、`vector_store`（Spring AI 管理，无 JPA Entity）、`rag_chat_sessions`、`rag_chat_messages`；共 14 张表（14 个 @Entity + @Table 类 + vector_store）；面试相关表保留但不写入新数据 |
| **后端接口** | 3个KB Controller（含面试/题目生成共 ~25 个端点） | 保留 `KnowledgeBaseController`（14 个端点）+ `RagChatController`（8 个端点）+ `KnowledgeBaseInterviewController`（10 个端点）= 32 个活跃端点；前端不再访问面试相关端点，后端保持原样 |
| **检索流程** | RAG 问答 + 查询改写 + 动态 topK/minScore | 完全复用，无变化；Prompt 模板从"面试知识问答"改为"研发知识/故障排查问答" |
| **来源标注** | Prompt 中有"引用来源"指令（`system.st` 第 60 行），但 API 响应不返回结构化来源；`Message` 接口只有 id/type/content/timestamp | **新增**: SSE 流结束后发送 `event: sources` 事件，data 为 `List<SourceReference>` JSON（kbId、documentName、content、score）；`Document.getScore()` 返回 `Double`（Spring AI 2.0.0），P1-C 测试已实际调用；`documentName` 通过 `Document.getMetadata()` 中的 `kb_id` 反查 `knowledge_bases` 表获取 `originalFilename`（原始文件名，不可被用户修改） |
| **权限** | 无认证（单机部署） | 第一阶段不变，保持无认证 |
| **前端交互** | 导航含面试中心、简历上传、语音面试、知识库、设置 | 导航简化为：知识库管理 + 问答助手 + 设置；面试相关页面从导航移除，旧路由重定向到首页 |
| **项目命名** | `interview-guide` / `interview.guide` / `ai-interview-platform` | 见第四节迁移策略 |
| **Prompt 语义** | “你是一个面试知识助手” | “你是一个研发团队知识与故障排查助手” |

### 2.4 最小 DevSupport 数据与交互能力

#### 最小数据模型

- 知识库上传：文件 + 可选名称（已有）
- 分类：保留现有 category 字段（`String(100)`, nullable, 有普通索引 `idx_kb_category`），但**不新增分类体系**
  - Phase 1 的分类仍是自由文本，用户自行约定命名（如“故障排查”、“部署文档”、“API 参考”）
  - 不引入预定义分类枚举、不新增分类层级
  - 上传时仍无分类输入（`uploadKnowledgeBase(file, name)` 无 category 参数），分类在管理页面事后编辑（保持现状）
  - 筛选是下拉 `<select>`，选项来自 `findAllCategories()` 动态查询
  - 如果后续需要“项目/模块/故障类型”结构化分类，作为独立任务在 Phase 1 之后

#### 最小交互能力（可验收例子）

1. 上传 `eval/datasets/devsupport-v0.1/corpus/ig-readme-root.md`（README.md），等待向量化完成
2. 在问答助手中提问：“项目的后端端口是多少？” → 回答应包含“8080”并标注来源为 README.md
3. 提问：“如何启动开发环境的数据库？” → 回答应包含 docker compose 命令并标注来源
4. 上传第二份文档（如 ig-voice-arch.md），提问跨文档问题：“语音面试的架构设计是什么？” → 来源应指向 voice-interview-architecture.md
5. 切换会话、删除会话、修改标题 → 正常工作

#### 验收标准

- 来源标注在 AI 回答下方可见
- 来源面板显示文档名和相关文本片段
- 点击来源可展开查看完整 chunk

---

## 三、按依赖排序的改造任务

### Task 0: P1-C 分段门槛（4 个独立 Gate，每个独立记录通过/未通过）

#### Gate 0a: 无 API 容器核验

- **前置**: 用户手动启动 Docker Desktop
- **操作**: `docker compose -f docker-compose-eval.yml --env-file .env.eval up -d`
- **验收**: 容器健康检查通过、`eval_instance_identity` 表存在、`vector_store` 表存在（1024维）
- **不涉及**: Embedding API、付费调用
- **涉及文件**: `docker-compose-eval.yml`, `docker/postgres/eval-init.sql`, `.env.eval.example`
- **工作量**: 约 10 分钟

#### Gate 0b: 离线复核

- **前置**: Gate 0a 通过
- **操作**: 确认 114 条离线用例在新容器环境下仍全绿（`./gradlew :app:test --no-daemon`，不含 real-eval tag）
- **验收**: 离线测试全绿
- **不涉及**: 真实 Embedding
- **工作量**: 约 10 分钟

#### Gate 0c: 付费冒泡

- **前置**: Gate 0b 通过 + 用户手动创建 `.env.eval` 填入 `AI_BAILIAN_API_KEY` 和 `EVAL_RUNNER_PASSWORD`
- **说明**: 现有 `P1cRealRetrievalEvalTest` 只有 1 个 `@Test` 方法 `realRetrievalEval()` 跑全部 20 题，无 `@ParameterizedTest`，无系统属性控制单题模式，**不支持单题运行**
- **操作**: **新增代码**（test 作用域）——新增一个独立的冒泡测试方法（如 `@Test void smokeSingleQuery()`），或新增系统属性 `eval.p1c.smokeQueryId` 控制只跑指定题目。运行 1 道 exact_lookup 题的真实 Embedding + 检索，验证端到端通路
- **验收**: 返回非空检索结果，确认 Embedding API 可达、pgvector 写入成功
- **冒泡完成后清理**: 删除冒泡测试方法或重置系统属性
- **费用**: 约 0.002 元
- **工作量**: 约 5 分钟（含新增冒泡代码）

#### Gate 0d: 正式 L1

- **前置**: Gate 0c 通过
- **操作**: 执行完整 20 题 L1 评测（`-Peval.p1c.realApi=true`）
- **验收**: `p1c-l1-report.json` 生成，含非 null 的 Hit@K / MRR@K / APC@K
- **费用**: 约 0.02 元
- **工作量**: 约 15 分钟

**P1-C 技术事实**:
- 14 个 P1c 测试文件，入口 `P1cRealRetrievalEvalTest.java` (869行)
- 入口方法有 `assumeTrue("true".equals(System.getProperty("eval.p1c.realApi")))` 门控
- 类标注 `@Tag("real-eval")`，被 `build.gradle` `excludeTags` 排除
- 前置条件：评测容器启动 + `eval.p1c.realApi=true` + `AI_BAILIAN_API_KEY` + `EVAL_RUNNER_PASSWORD`
- `docker-compose-eval.yml` 容器名 `interview-eval-postgres`，端口 5433

**依赖**: 无，但需要用户手动执行
**阻塞**: 无（Gate 0d 为"执行完整 L1"的唯一入口，Task 7b 已删除）

### Task 1: 产品展示层改名（基础层，无运行时依赖）

- **内容**: Phase 1 只改展示层名称，保留所有数据面/基础设施名称不变
- **Phase 1 执行的改名**:

| 改动项 | 文件 | 当前值 → 新值 |
|--------|------|----------------|
| 前端 package name | `frontend/package.json:2` | `ai-interview-frontend` → `devsupport-frontend` |
| 前端页面标题 | `frontend/index.html:7` | `AI智能面试官` → `DevSupport - 研发知识平台` |
| Layout 导航标题 | `frontend/src/components/Layout.tsx` | 更新为 DevSupport |
| App 标题 | `frontend/src/App.tsx` | 更新为 DevSupport |
| 文件头注释 | `app/build.gradle`, `application.yml`, `App.java` | 更新为 DevSupport |

- **以下迁移明确延后到后续阶段**（Phase 1 不改）:
  - 主库名 `interview_guide` → 保留
  - S3 bucket `interview-guide` → 保留
  - 容器名前缀 `interview-*` → 保留
  - Java 包名 `interview.guide` → 保留（涉及数百文件，需单独 PR）
  - JPA 表名 → 保留（需 Flyway 迁移脚本）
  - P1-C 评测实例名 → 保留
  - Gradle rootProject.name / group → 保留
  - Spring application.name → 保留
  - 本地配置路径 `.interview-guide/` → 保留

- **迁移策略说明**:
  - 当前 `ddl-auto: validate`（`application.yml` 第 50 行），Hibernate 只校验不建表
  - Flyway 已启用（`baseline-on-migrate: true`, `baseline-version: 1`），所有 schema 变更必须通过 Flyway 迁移脚本
  - `initialize-schema: false`（pgvector schema 由 Flyway 管理）
  - 现有 Flyway 迁移文件 5 个：V1__init_schema.sql (306行), V20260722, V20260723, V20260724, V20260803
  - 如果后续阶段需要改表名，必须写 `ALTER TABLE RENAME` 迁移脚本（新增 `V20260XXX__xxx.sql`）

- **验收**: `./gradlew :app:compileJava` 通过；前端 `pnpm run build` 通过；页面标题和导航显示 DevSupport
- **工作量**: 1 小时
- **依赖**: 无

### Task 2: 前端导航与路由精简

- **内容**: 从导航中移除面试相关入口，保留知识库管理 + RAG 问答 + 设置；旧路由重定向到首页
- **前端隐藏入口**（不用“注释路由”）:
  - `Layout.tsx` 导航菜单：移除“面试准备”分组全部 4 项 + “知识库”分组中的“知识库面试”
  - `App.tsx` 路由表：保留路由定义但添加重定向 — 访问 `/interview-hub`、`/voice-interview` 等旧路由时重定向到 `/knowledgebase`（首页）
  - 不删除任何页面组件文件，只是从导航入口移除
- **涉及文件**:
  - `frontend/src/App.tsx` — 旧路由添加重定向到 `/knowledgebase`
  - `frontend/src/components/Layout.tsx` — 导航菜单简化为：知识库管理、问答助手、设置
  - `frontend/src/constants/routes.ts` — 保留常量定义（不删除）
  - `frontend/src/pages/KnowledgeBaseManagePage.tsx` — 移除“面试”相关导航按钮（如有）
- **验收**: 前端构建通过；导航只有知识库管理 + 问答助手 + 设置三个入口；访问旧路由重定向到首页
- **工作量**: 1-2 小时
- **依赖**: 无（可与 Task 1 并行）

### Task 3: RAG 来源标注增强（核心新功能）

- **内容**: 让 RAG 回答返回结构化的来源信息

#### 后端 DTO 设计

```
SourceReference {
  kbId: Long          // 知识库 ID
  documentName: String // 取自 KnowledgeBaseEntity.originalFilename（原始文件名，不可被用户修改）
                      // 获取方式：检索后通过 Document.getMetadata() 中的 kb_id 反查 knowledge_bases 表
  content: String     // chunk 文本片段（截断至 200 字符）
  score: Double       // 检索相似度分数（Spring AI 2.0.0 Document.getScore() 返回 Double）
}
```

#### SSE 事件协议与调用链重设计

**问题说明**：当前检索结果 `List<Document>` 在 `KnowledgeBaseQueryService.answerQuestionStream()` 内部被消费为纯文本 context（第 225-227 行只取 `Document::getText`，metadata 丢弃），方法返回 `Flux<String>` 后 metadata 完全丢失。`RagChatSessionService.getStreamAnswer()` 也只返回 `Flux<String>`，Controller 层无法拿到检索来源。

**解决方案**：
1. **新增结果契约类** `RetrievalResult`（放在 `KnowledgeBaseQueryService` 同包或 model 包）：
   ```
   record RetrievalResult(Flux<String> contentStream, List<Document> sourceDocuments)
   ```
2. **修改 `KnowledgeBaseQueryService.answerQuestionStream()` 返回类型**：从 `Flux<String>` 改为 `RetrievalResult`，将 `relevantDocs` 与 `responseFlux` 一起返回
3. **级联修改**：
   - `RagChatSessionService.getStreamAnswer()` 返回类型改为 `RetrievalResult`
   - `RagChatController.sendMessageStream()` 从 `RetrievalResult` 中取 `contentStream` 做 SSE 包装，从 `sourceDocuments` 构建来源列表
4. **SSE 发送顺序**：
   - 先发送所有 `event: data` 文本 chunk（现有行为不变）
   - 流完成后（`.doOnComplete()` 之前），发送一个 `event: sources` 事件，data 为 `List<SourceReference>` 的 JSON
   - 最后发送 `event: done` 事件标记结束
5. **注意**：`KnowledgeBaseController.queryKnowledgeBaseStream()` 也调用 `answerQuestionStream()`，需要同步适配（可暂时忽略来源，只取 `contentStream`）

#### 消息持久化

- `rag_chat_messages` 表新增列 `sources_json TEXT`（nullable），存储 JSON 序列化的来源列表
- `completeStreamMessage()` 方法增加 sources 参数
- 历史消息回显：`getSessionDetail()` 返回的消息中包含 sources_json，前端解析后显示

#### 边界情况处理与消息状态区分

**消息完成状态**：`rag_chat_messages` 表新增 `status` 字段（替代或补充 `completed` Boolean），枚举值：
- `COMPLETED` — 正常完成，LLM 流式输出完毕
- `NO_RESULTS` — 检索命中 0 条文档，LLM 未调用（或调用了但 context 为空）
- `MODEL_FAILED` — LLM 调用失败（`onErrorResume` 触发或 `.doOnError()` 触发）
- `CLIENT_DISCONNECTED` — 客户端中途断开（SSE 订阅取消）

**来源与状态绑定持久化**：
- `sources_json` 与 `status` 一起持久化
- `MODEL_FAILED` 时：`sources_json` 仍可保存（检索已完成，LLM 失败），前端展示时标记为"回答生成失败，以下为检索到的参考文档"
- `CLIENT_DISCONNECTED` 时：`sources_json` 保存，`status=CLIENT_DISCONNECTED`，前端标记为"回答中断"
- `NO_RESULTS` 时：`sources_json = []`，前端显示"未找到相关文档"
- `COMPLETED` 时：正常展示来源

**错误文本区分**：当前 `onErrorResume` 将错误转为 `"【错误】..."` 普通文本，上层无法区分。建议：
- 方案 A（推荐）：`answerQuestionStream()` 返回 `RetrievalResult` 时增加 `errorFlag` 字段，标记是否发生过错误
- 方案 B：保留 `onErrorResume` 但在 metadata 中设置错误标记，Controller 层检查

**多文档来源**：sources 列表按检索排序展示，前端限制最多显示 5 条，超出折叠

**历史回显**：`getSessionDetail()` 返回消息时包含 `status` 和 `sources_json`，前端按 status 决定展示样式

#### 前端改动

- `stream.ts` 的 `StreamSseOptions` 新增 `onSources?: (data: string) => void` 回调
- `stream.ts` 的 `processEventBlock` 增加 `eventName === 'sources'` 分支
- `ragChat.ts` 的 `sendMessageStream` 透传 `onSources` 回调
- `KnowledgeBaseQueryPage.tsx` 的 `Message` 接口新增 `sources?: SourceReference[]` 和 `status?: 'completed' | 'no_results' | 'model_failed' | 'client_disconnected'`
- AI 消息渲染组件在内容下方显示来源引用面板（可折叠），按 status 决定展示样式

#### 涉及文件

- 后端 `KnowledgeBaseQueryService.java` — 返回类型改为 `RetrievalResult`，在检索阶段收集 `sources` 列表
- 后端 `RagChatSessionService.java` — 返回类型改为 `RetrievalResult`，SSE 流结束后发送 `sources` 事件
- 新增 record: `RetrievalResult(Flux<String> contentStream, List<Document> sourceDocuments)`
- 新增 DTO: `SourceReference`（kbId, documentName, content, score）
- Flyway 迁移脚本：`rag_chat_messages` 新增 `sources_json TEXT` 列 + `status VARCHAR(32)` 列
- 前端 `KnowledgeBaseQueryPage.tsx` — 在 AI 回答下方渲染来源引用列表，按 status 决定展示样式
- 前端 `api/ragChat.ts` — 解析 SSE 中的 sources 事件，透传 onSources 回调
- 前端 `api/stream.ts` — StreamSseOptions 新增 onSources 回调，processEventBlock 新增 sources 分支

- **验收**: 提问后回答下方显示引用来源（文档名 + 相关文本片段）
- **工作量**: 4-6 小时
- **依赖**: 无（可与 Task 1、2 并行）

### Task 4: Prompt 模板适配 DevSupport 场景

- **内容**: 改写 3 个 KB 相关 Prompt 模板的语义
- **涉及文件**:
  - `resources/prompts/knowledgebase-query-system.st` — 系统角色从"面试知识助手"改为"研发团队知识与故障排查助手"；增加"优先引用排查步骤"指令
  - `resources/prompts/knowledgebase-query-user.st` — 调整 context 注入格式，增加文档类型标注
  - `resources/prompts/knowledgebase-query-rewrite.st` — 改写示例从面试语境改为研发/运维语境
- **验收**: 用 devsupport-v0.1 语料手动测试 3-5 个问题，回答语义正确
- **工作量**: 1-2 小时
- **依赖**: 无（可与 Task 1-3 并行）

### Task 5: 后端接口处理（Phase 1 不禁用）

- **内容**: Phase 1 **不禁用任何后端端点** — 保留所有 32 个端点可用
- **方案说明**:
  - 面试相关端点（10 个 KB Interview + 其他面试模块端点）只是前端不再访问，后端保持原样
  - 项目无 Spring Security，无认证机制，无 `@Profile` 使用，无 `SecurityFilterChain` / `@EnableWebSecurity`
  - 后端端点暴露不影响功能；后续如需禁用可通过 `@Profile` 或 `@ConditionalOnProperty` 实现
- **验收**: `/api/knowledgebase/*` 和 `/api/rag-chat/*` 正常工作；面试相关端点保持可用但前端不访问
- **工作量**: 0（无需改动）
- **依赖**: 无

### Task 6: 文档更新

- **内容**: 更新项目文档以反映 DevSupport 定位
- **涉及文件**:
  - `README.md` — 重写为 DevSupport 项目介绍
  - `AGENTS.md` — 更新标题和场景描述，保留技术规则
  - `PROJECT_PROGRESS.md` — 登记改造进度
  - `.env.example` — 更新注释
- **验收**: 文档内容与 DevSupport 定位一致
- **工作量**: 2-3 小时
- **依赖**: Task 1（命名确定后更新文档）

### Task 7a: E2E 功能冒烟

- **内容**: 启动完整 dev 环境，执行端到端流程验证
- **步骤**:
  1. `docker compose -f docker-compose.dev.yml up -d`
  2. 上传 devsupport-v0.1 corpus 中的 2-3 份文档
  3. 等待向量化完成
  4. 在问答助手中提问（参考 §2.4 的 5 个验收例子）
  5. 验证来源标注正确显示
- **验收**: 5 个验收例子全部通过（详见 §2.4）
- **API 成本说明**: **生产链路 API 冒烟，有外部调用**（Embedding + LLM）。上传文档触发向量化（调用 DashScope Embedding API），RAG 问答调用 LLM，因此有少量费用，估算约 0.01 元（2-3 次 Embedding + 2-3 次 LLM 调用）。如需真正零 API 测试，需实现桩服务（MockEmbeddingModel / MockChatModel），这不在 Phase 1 范围内
- **工作量**: 1-2 小时
- **依赖**: Task 1-6 全部完成 + Gate 0a-0c 通过（不需要 0d，因为 7a 是功能冒烟不是质量评测）

### 依赖关系图

```
Task 0a-0c (P1-C 门槛, 手动) ─────────────────────→ Task 7a (E2E 冒烟)
                                                       ↑
Task 1 (展示层改名) ─────────────────────→ Task 6 (文档) │
                                                       │
Task 2 (前端精简) ────────────────────────────────────→ Task 7a
                                                       │
Task 3 (来源标注) ───────────────────────────────────→ Task 7a
                                                       │
Task 4 (Prompt适配) ─────────────────────────────────→ Task 7a

Task 0d (正式 L1) ── 独立执行，生成 p1c-l1-report.json（不再阻塞其他任务）
```

> 注：原 Task 7b（P1-C 检索质量评测）已删除，与 Gate 0d 重复。Gate 0d 保持为"执行完整 L1"的唯一入口。

### 并行策略

**可并行的任务组**:
- **Group A**（基础设施层）: Task 1（展示层改名）
- **Group B**（前端层）: Task 2（导航精简）
- **Group C**（后端层）: Task 3（来源标注）、Task 4（Prompt 适配）
- **Group D**（手动操作）: Task 0（P1-C 门槛 Gate 0a-0d）

Group A/B/C/D 四组可同时开工。Task 6 等 Task 1 完成后执行。Task 7a 等 Task 1-6 完成且 Gate 0a-0c 通过后执行。Gate 0d（正式 L1）独立执行，不阻塞其他任务。

---

## 四、迁移策略

### 4.1 Phase 1 迁移范围（展示层改名）

Phase 1 只改展示层名称：前端 package name、页面标题、导航标题、文件头注释。所有数据面/基础设施名称保留不变：
- 主库名 `interview_guide`、S3 bucket `interview-guide`、容器名前缀 `interview-*`、Java 包名 `interview.guide`、JPA 表名、P1-C 评测实例名、Gradle rootProject.name/group、Spring application.name

### 4.2 后续阶段迁移原则

Java 包名 `interview.guide` → `devsupport` 涉及数百文件的 `package` 和 `import` 语句，建议：
1. Phase 1 功能交付后，单独开一个 "Rename Package" PR
2. 使用 IntelliJ IDEA 的 Refactor → Rename Package 功能
3. 同步更新 `application.yml` 的 `spring.autoconfigure.base-packages`
4. 更新测试包名

### 4.3 表名迁移原则

14 张表中，6 张面试表在 DevSupport 中不再使用。Phase 1 不改动表名，避免数据迁移风险。后续如需清理：
- 当前 `ddl-auto: validate`，Hibernate 只校验不建表
- 所有 schema 变更必须通过 Flyway 迁移脚本（新增 `V20260XXX__xxx.sql`）
- 必须写 `ALTER TABLE ... RENAME TO ...` 迁移脚本，不能 drop 重建
- `knowledge_bases`、`rag_chat_*`、`vector_store` 表名无需改动

### 4.4 仓库名称

- 当前远程: `HyCheng-reborn/devsupport-platform`（已正确）
- 上游只读: `Snailclimb/interview-guide`（不推送）
- 本地 `.git/config` 中的 remote 名称无需改动

---

## 五、与 P1-C 真实 L1 的关系

| 场景 | 说明 |
|------|------|
| Task 0a-0d 通过 | 获得 L1 基线数字（Hit@K / MRR@K / APC@K），可作为 Task 7a E2E 验证的检索质量参照 |
| Task 0a-0d 未通过 | 说明 Embedding/向量检索有底层问题，需先修复再进入 Task 7a |
| Prompt 改动对 L1 的影响 | P1-C L1 绕过 Service 层直接调 `VectorStore.similaritySearch`，不受 Prompt 改动影响；但 Task 7a 的端到端验证会受 Prompt 影响 |
| 命名迁移对评测的影响 | Phase 1 不改容器名/库名，评测环境无影响；评测代码在 test 作用域，不受 main 包名影响 |

**结论**: Task 1-6 的编码工作不依赖 Task 0 的结果，可以先行推进。Task 7a 的 E2E 冒烟需要 Gate 0a-0c 通过；Gate 0d 的正式 L1 评测独立执行。

---

## 六、风险与注意事项

1. **不推送 origin**: 后续提交只推送到 `devsupport`（`HyCheng-reborn/devsupport-platform`），绝不推送到上游 `Snailclimb/interview-guide`
2. **评测隔离**: P1-C 评测代码全部在 test 作用域，不新增 main 源码；评测容器独立端口 5433，不连生产库
3. **面试模块保留**: 第一阶段不删除面试模块代码，前端从导航入口隐藏，后端保持所有 32 个端点可用，降低回滚风险
4. **P1-C 状态**: 当前"代码已写 + 离线通过 · 真实环境零验证"，114 条离线用例全绿，但无检索质量数字
5. **不设计预期为结果**: 方案中所有检索质量指标需等 Task 0 执行后才能填写
