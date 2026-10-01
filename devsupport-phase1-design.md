# DevSupport Phase 1 设计文档

> 调研基线: `2cc5834`（Codex 六次定点源码复核通过）
> 状态: **方案待 Codex 复核**
> 本轮只产出设计，不修改业务代码、不启动容器、不调用付费 API。

---

## 1. 现有可复用能力与缺口

### 1.1 已实现（源码核实，HEAD 2cc5834）

| 能力 | 实现位置 | 源码依据 |
|------|----------|----------|
| 文档上传/解析/去重 | `KnowledgeBaseUploadService.uploadKnowledgeBase()` | Tika → SHA-256 → S3 → Redis Stream 异步向量化 |
| 异步向量化 | `VectorizeStreamConsumer` → `KnowledgeBaseVectorService.vectorizeAndStore()` | TokenTextSplitter ~800 tokens → DashScope text-embedding-v3 → pgvector 1024 维 COSINE |
| RAG 流式问答 SSE 三事件 | `RagChatController.sendMessageStream()` 第 160-211 行 | data → sources → done(status)，AtomicBoolean 协调三条终止路径 |
| 来源传递契约 | `RetrievalResult` record | 绑定 `Flux<String> contentStream` + `List<Document> sourceDocuments` |
| 消息状态区分 | `MessageStatus` 枚举 | COMPLETED / NO_RESULTS / MODEL_FAILED / CLIENT_DISCONNECTED |
| 来源持久化与回显 | `rag_chat_messages.sources_json` + `status` | Flyway V20260930，`completeStreamMessage()` 四参数签名 |
| 多会话聊天 | `RagChatSessionService` | `rag_chat_sessions` + `rag_chat_messages` + 中间表 `rag_session_knowledge_bases` |
| 知识库 CRUD/分类/搜索 | `KnowledgeBaseListService` / `DeleteService` | 14 个端点，支持 category/keyword/sort |
| 前端来源面板 | `KnowledgeBaseQueryPage.tsx` 第 608-634 行 | 按 status 分 grounded/degraded/none/pending 模式渲染 |
| Prompt DevSupport 适配 | 3 个 `.st` 文件 | 角色改为"研发知识故障排查助手"，示例面向运维语境 |
| 前端导航 | `Layout.tsx` 第 84-100 行 | 仅保留知识库管理 + 问答助手 + 设置 |
| 测试覆盖 | 13 个新测试 | SourceReference(3) + RagChatSessionService(4) + KnowledgeBaseQueryService(6) |

### 1.2 需改造

| 缺口 | 当前状态 | 源码依据 |
|------|----------|----------|
| 服务/环境标签 | 只有 `category`（varchar(100)，自由文本） | `KnowledgeBaseEntity.java` 第 30-31 行，无 service/environment 字段 |
| 上传参数 | 只接收 file + name + category | `KnowledgeBaseController.java` 第 200-208 行 |
| 前端上传表单 | 连 category 都不传 | `KnowledgeBaseUploadPage.tsx` 第 20 行只传 file + name |
| RAG 检索过滤 | 只按 kbIds 列表过滤 | `KnowledgeBaseVectorService.similaritySearch()` 第 125 行，filter 只用 `kb_id` |
| vector metadata | 只有 kb_id | `KnowledgeBaseVectorService.java` 第 108-114 行，3 个字段全是 kb 相关 |

### 1.3 计划以后实现

- Runbook / 事件工单系统
- Hybrid/Rerank 检索
- 权限认证
- 多模型 Embedding 切换
- 结构化标签体系（替代自由文本 category）

---

## 2. 第一阶段用户流程与功能边界

### 2.1 用户流程

1. **上传文档**：选择文件 → 填写名称（可选）→ 选择服务（如"用户中心"、"支付网关"）和环境（如"生产"、"预发"、"通用"）→ 提交
2. **管理文档**：按服务/环境筛选 → 行内编辑服务/环境 → 查看向量化状态
3. **RAG 问答**：选择知识库（自动继承服务/环境）→ 输入问题 → 流式获取带来源的回答 → 来源面板显示文档名 + 服务/环境标签
4. **会话管理**：会话绑定知识库 → 历史消息含来源和状态 → 刷新后恢复

### 2.2 功能边界

**Phase 1 包含**：
- 文档上传 + 服务/环境分类
- RAG 问答 + 来源展示 + 刷新恢复
- 按服务/环境筛选文档和检索

**Phase 1 不包含**：
- Runbook 自动化
- 事件/工单集成
- 权限认证
- Hybrid/Rerank

---

## 3. 后端分层方案

### 3.1 Controller 层（路由、校验、委托）

- `KnowledgeBaseController`（14 端点）— 需新增 service/environment 参数
- `RagChatController`（8 端点）— SSE 契约已完善，无需改动

### 3.2 Service 层（业务编排）

- `KnowledgeBaseUploadService` — 上传方法增加 service/environment 参数
- `KnowledgeBaseQueryService` — 检索前按 service/environment 查出 kbIds，再传入向量检索
- `KnowledgeBaseListService` — 新增 `listByService()`、`listByEnvironment()` 方法
- `RagChatSessionService` — 已完善，无需改动

### 3.3 Repository 层

- `KnowledgeBaseRepository` — 新增 `findByServiceAndEnvironment()`、`findAllServices()`、`findAllEnvironments()` 查询
- `VectorRepository` — 不变（仍按 kb_id 过滤）

### 3.4 候选 API 变更

| 端点 | 变更 |
|------|------|
| `POST /api/knowledgebase/upload` | 增加 `@RequestParam("service")` + `@RequestParam("environment")` |
| `GET /api/knowledgebase/list` | 增加 `?service=&environment=` 查询参数 |
| `GET /api/knowledgebase/services` | 新增，返回所有已使用的服务名 |
| `GET /api/knowledgebase/environments` | 新增，返回所有已使用的环境名 |

### 3.5 数据模型变更

- `KnowledgeBaseEntity` 新增：
  - `service`（varchar(100)，nullable，索引 idx_kb_service）
  - `environment`（varchar(50)，nullable，索引 idx_kb_environment）
- Flyway 迁移：`V20261001__add_service_environment.sql`

### 3.6 事务与异步边界

- `@Transactional` 只在 Service 层
- LLM/S3/Embedding 调用不在事务内
- Redis Stream 异步处理向量化任务

---

## 4. RAG 路径

### 4.1 SSE 契约（已实现，HEAD 2cc5834）

```
event: data     → 文本 chunk（换行转义）
event: sources  → 来源 JSON（持久化成功后发送）
event: done     → {"status":"<finalStatus>"}
```

- `AtomicBoolean finalized` 协调三条终止路径（正常完成/模型错误/客户端取消）
- 持久化成功后才发 sources + done
- 持久化失败返回 `Flux.error(BusinessException)`，不发 done

### 4.2 纯文本 Flux<String> 端点

`KnowledgeBaseController.queryKnowledgeBaseStream()` 取 `RetrievalResult.contentStream()` 后需要 `onErrorResume` 兜底（当前已有，见 `bab3a62` 修复）。

### 4.3 各状态处理

| 状态 | 触发条件 | sources | 持久化 | 前端展示 |
|------|----------|---------|--------|----------|
| COMPLETED | 正常完成 | 正常来源 | status=COMPLETED | 蓝色"引用来源" |
| NO_RESULTS | 检索 0 条 | `"[]"` | status=NO_RESULTS | 黄色"未找到相关文档" |
| MODEL_FAILED | LLM 异常 | 仍可保存 | status=MODEL_FAILED | 琥珀色"仅供参考" |
| CLIENT_DISCONNECTED | 客户端取消 | 保存 | status=CLIENT_DISCONNECTED | 灰色"回答中断" |

---

## 5. 前端最小改动范围

### 5.1 可复用（无需改动）

- `KnowledgeBaseQueryPage.tsx` — 来源面板已完善
- `stream.ts` — SSE 解析（onSources/onDone）已完善
- `ragChat.ts` — SourceReference 类型已定义

### 5.2 需新增改动

| 文件 | 改动 |
|------|------|
| `KnowledgeBaseUploadPage.tsx` / `FileUploadCard.tsx` | 上传表单增加服务/环境输入 |
| `KnowledgeBaseManagePage.tsx` | 增加服务/环境筛选列和筛选下拉 |
| `api/knowledgebase.ts` | 上传 API 增加 service/environment 参数；列表 API 增加筛选参数 |
| 来源面板 | 显示服务/环境标签（可选增强） |

---

## 6. 命名与迁移决策表

| 项目 | 当前值 | 决策 | 理由 |
|------|--------|------|------|
| Java 根包 `interview.guide` | 数百文件 | **保留** | 改名成本极高，无功能收益 |
| 数据库名 `interview_guide` | 配置/环境变量 | **保留** | 需 pg_dump/restore，风险大 |
| S3 bucket `interview-guide` | 创建后不可改 | **保留** | 技术上不可行 |
| 容器名前缀 `interview-*` | compose 文件 | **保留** | 改后旧卷不兼容 |
| JPA 表名（面试相关 6 张） | 后端代码仍在 | **保留** | 改名需 Flyway + 数据迁移 |
| 前端展示名 | 已改为 DevSupport | **已完成** | 用户可见层已改 |
| Prompt 模板 | 已改为 DevSupport | **已完成** | 语义已改 |
| README/AGENTS.md | 仍为面试官标题 | **Phase 1 内更新** | 文档级改动，低风险 |
| 包名/表名/库名 | 全部 interview | **后续阶段** | 单独迁移 PR |

---

## 7. 开发批次

### 批次 A：服务/环境数据模型

- **文件**：`KnowledgeBaseEntity.java`、`V20261001__add_service_environment.sql`、`KnowledgeBaseRepository.java`
- **依赖**：无
- **验收**：`./gradlew :app:compileJava` 通过；Flyway 迁移在 `ddl-auto: validate` 下兼容
- **回退**：删除迁移脚本和新字段
- **未验证**：真实数据库迁移

### 批次 B：上传与筛选 API

- **文件**：`KnowledgeBaseUploadService.java`、`KnowledgeBaseController.java`、`KnowledgeBaseListService.java`
- **依赖**：批次 A
- **验收**：`./gradlew :app:test --no-daemon` 通过
- **回退**：恢复旧方法签名
- **未验证**：前端联调

### 批次 C：前端服务/环境交互

- **文件**：`KnowledgeBaseUploadPage.tsx`、`FileUploadCard.tsx`、`KnowledgeBaseManagePage.tsx`、`api/knowledgebase.ts`
- **依赖**：批次 B
- **验收**：`cd frontend && pnpm run build` 通过
- **回退**：git revert
- **未验证**：真实上传端到端

### 批次 D：RAG 检索按服务/环境过滤

- **文件**：`KnowledgeBaseQueryService.java`、`RagChatSessionService.java`
- **依赖**：批次 A
- **验收**：现有测试仍通过 + 新增过滤测试
- **回退**：恢复旧检索逻辑
- **未验证**：真实检索质量

### 批次 E：文档与进度更新

- **文件**：`README.md`、`AGENTS.md`、`PROJECT_PROGRESS.md`、`CHANGES.md`
- **依赖**：批次 A-D
- **验收**：文档内容与实现一致

---

## 8. P1-C 检索评测定位

- P1-C 是**组件级检索基线**（直接调 `VectorStore.similaritySearch`，绕过 Service 层）
- P1-C **不等于** DevSupport 功能已落地
- **Hybrid/Rerank 未实现**
- **付费 L1 未执行**（Gate 0a-0d 均未通过）
- **真实生产数据未入库**
- P1-C 评测代码在 test 作用域，不影响 main 源码
- P1-C 语料仍为面试平台文档，改造后需新语料

---

## 9. 反例审查

### 9.1 跨服务/跨环境串检索

- **风险**：用户选了"用户中心/生产"的知识库，但检索时 kbIds 过滤错误地包含了其他服务的文档
- **防护**：`similaritySearch()` 的 `filterExpression` 只传入按 service/environment 筛选后的 kbIds
- **测试点**：上传两个不同服务的文档，检索时验证只返回选中服务的文档

### 9.2 旧文档无标签

- **风险**：已有知识库没有 service/environment 值，检索时被完全排除
- **防护**：查询时 `service IS NULL` 的文档视为"通用"，参与所有服务的检索
- **测试点**：上传无标签文档，验证检索可命中

### 9.3 来源与实际检索范围不一致

- **风险**：sources 中的 documentName 来自不在本次检索范围内的知识库
- **防护**：`buildSourceReferences()` 只处理本次检索返回的 `List<Document>`
- **测试点**：验证每个 SourceReference.kbId 在请求的 kbIds 列表中

### 9.4 SSE 中断后刷新恢复

- **风险**：客户端断开后重新加载会话，消息状态丢失或显示为正常完成
- **防护**：`doOnCancel` 持久化 `CLIENT_DISCONNECTED` 状态，`getSessionDetail` 返回持久化状态
- **测试点**：模拟中断后查询消息状态，验证 status=CLIENT_DISCONNECTED

### 9.5 历史数据迁移造成的信息混用

- **风险**：后续引入权限时，历史数据未标权限导致信息泄露
- **防护**：Phase 1 不涉及权限，数据模型预留扩展点（service/environment 字段可为 null）
- **测试点**：Phase 1 不测试（无权限功能）

---

## 10. 未解决的问题

1. **service/environment 字段设计**：用两个独立 varchar 字段（类似 category），还是合并为 JSON tags 字段？独立字段查询更简单，JSON tags 更灵活
2. **旧文档的 service 默认值**：是 NULL（视为"通用"）还是要求用户补填？
3. **环境枚举**：是否预定义环境列表（生产/预发/测试/通用），还是自由文本？
4. **vector metadata 是否增加 service/environment**：如果增加，可在向量层直接过滤（性能更好）；如果不增加，通过 kbIds 间接过滤（改动更小）
5. **Controller 直接持有 Repository**：Code Review 指出 `RagChatController` 注入 `KnowledgeBaseRepository` 违反架构约定，是否在本轮修正？
6. **`queryKnowledgeBaseStream` 错误兜底**：适配 `RetrievalResult` 后的兜底是否完整？
