# DevSupport Phase 1 设计文档

> 调研基线: `2cc5834`（Codex 六次定点源码复核通过）
> 状态: **第三轮修订待 Codex 复核**
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
| 来源引用结构 | `SourceReference` record | 4 字段（kbId, documentName, contentSnippet, score），Phase 1 第三轮决策新增 service + environment 快照字段 |
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
3. **RAG 问答**：选择知识库（可参考服务/环境标签筛选）→ 输入问题 → 流式获取带来源的回答 → 来源面板显示文档名 + 提问时的服务/环境快照
4. **会话管理**：会话绑定知识库 → 历史消息含来源和状态 → 刷新后恢复

### 2.2 功能边界

**Phase 1 包含**：
- 文档上传 + 服务/环境分类
- RAG 问答 + 来源展示（含提问时标签快照）+ 刷新恢复
- 按服务/环境筛选文档（管理页面）

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
- `KnowledgeBaseQueryService` — 不改（检索范围由 kbIds 决定，不按 service/environment 过滤）
- `KnowledgeBaseListService` — 新增 `listByService()`、`listByEnvironment()` 方法
- `RagChatSessionService` — `buildSourceReferences()` 新增 service/environment 快照字段获取

### 3.3 Repository 层

- `KnowledgeBaseRepository` — 新增 `findByServiceAndEnvironment()`、`findAllServices()`、`findAllEnvironments()` 查询
- `VectorRepository` — 不变（仍按 kb_id 过滤）

### 3.4 服务/环境作为组织标签的请求链

**核心定位**：service/environment 是**管理页面的分类与组织标签**，用于帮助用户发现和整理知识。问答会话的检索范围**仍然由用户显式勾选的知识库（kbIds）决定**，服务端不按 service/environment 做强制隔离。

**为什么不做服务端强制隔离**：
- 现有会话机制（`rag_session_knowledge_bases` 中间表）已提供精确的 KB 级范围控制
- 引入 service/environment 作为服务端强制过滤会增加复杂度（需校验会话 KB、处理 KB 改标签后的会话一致性等），收益不大
- 用户通过勾选 KB 已能精确控制检索范围，service/environment 只是帮助筛选哪些 KB 可见

**用户选择范围的位置**：
- 在问答助手（`KnowledgeBaseQueryPage`）右栏选择知识库时，每个知识库卡片显示其 service/environment 标签
- 用户通过勾选知识库间接选择范围（不新增独立的 service/environment 选择器）
- 范围是**会话持久字段**（存在 `rag_session_knowledge_bases` 中间表），不是每次提问参数
- 前端管理页面提供 service/environment 筛选帮助用户找到 KB，但勾选后范围由 kbIds 决定

**服务端不校验**：
- `RagChatController`、`RagChatSessionService`、`KnowledgeBaseQueryService` 不做 service/environment 校验
- 不校验会话中的 KB 是否属于某 service/environment
- 检索范围完全由 kbIds 决定

**涉及文件清单**：

| 层 | 文件 | 改动 |
|---|---|---|
| Controller | `RagChatController` | 不改 |
| Service | `RagChatSessionService` | 不改 |
| Service | `KnowledgeBaseQueryService` | 不改 |
| Repository | `KnowledgeBaseRepository` | 新增 `findByService()`、`findByEnvironment()`、`findAllServices()`、`findAllEnvironments()` |
| Controller | `KnowledgeBaseController` | 列表端点增加 `?service=&environment=` 查询参数 |
| 前端 | `KnowledgeBaseManagePage.tsx` | 增加 service/environment 筛选下拉和行内编辑 |
| 前端 | `KnowledgeBaseQueryPage.tsx` | 右栏知识库列表显示 service/environment 标签 |
| 前端 | `api/knowledgebase.ts` | 列表 API 增加筛选参数，返回 service/environment 字段 |

### 3.5 候选 API 变更

| 端点 | 变更 |
|------|------|
| `POST /api/knowledgebase/upload` | 增加 `@RequestParam("service")` + `@RequestParam("environment")` |
| `GET /api/knowledgebase/list` | 增加 `?service=&environment=` 查询参数 |
| `GET /api/knowledgebase/services` | 新增，返回所有已使用的服务名 |
| `GET /api/knowledgebase/environments` | 新增，返回所有已使用的环境名 |

### 3.6 数据模型变更

**迁移脚本**（不设 DEFAULT，旧行保持 NULL）：
```sql
ALTER TABLE knowledge_bases ADD COLUMN service VARCHAR(100);
ALTER TABLE knowledge_bases ADD COLUMN environment VARCHAR(50);
CREATE INDEX idx_kb_service ON knowledge_bases(service);
CREATE INDEX idx_kb_environment ON knowledge_bases(environment);
```

**三种场景规则**：

| 场景 | service | environment | 检索行为 |
|------|---------|-------------|----------|
| 旧行（迁移前已存在） | NULL | NULL | 视为“未分类”，只在用户未选任何筛选条件时显示 |
| 新上传（未填标签） | NULL | NULL | 同旧行，视为“未分类” |
| 新上传（填了标签） | 用户填写值 | 用户填写值或“通用” | 按标签筛选时参与 |

**新旧客户端上传缺失标签时的行为**：
- 旧客户端（不传 service/environment）：后端收到 null，存为 NULL
- 新客户端（传了 service 但没传 environment）：service 存用户值，environment 存 NULL
- 前端上传表单：service 和 environment 都是可选字段

**唯一性/去重语义**：不变。fileHash 仍全局唯一，同一文件只存一条记录。

**编辑行为**：用户可在管理页面修改 service/environment（类似现有 category 行内编辑）。

**验证用例**（H2/Mockito 可测）：
- 上传不传标签 → entity.service = null, entity.environment = null
- 上传传 service="支付网关" → entity.service = "支付网关"
- 列表筛选 service="支付网关" → 只返回 service 匹配的 KB（不含 NULL）

### 3.7 同一文件跨服务的产品边界

**Phase 1 不支持**同一文件跨服务独立归属。fileHash 全局唯一约束保持不变。

**重复上传到另一服务时的行为**：
- 用户已为“支付网关”上传了 `deploy.md`（fileHash=abc123）
- 另一用户尝试为“用户中心”上传同一文件（fileHash=abc123）
- 后端检测到 fileHash 重复 → 返回已有记录信息，**不修改原记录的 service/environment**
- 前端显示提示：“该文件已存在（名称: deploy.md，服务: 支付网关），如需为不同服务建立独立条目，请修改文件内容后重新上传”
- **不静默返回原 KB，不偷偷改原标签**

**理由**：
- 解除 fileHash 唯一约束需要改动去重逻辑、向量 metadata、会话关联、来源快照，影响面太大
- Phase 1 明确不支持，后续阶段评估

### 3.8 事务与异步边界

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

### 4.1.1 来源快照策略

**核心语义**：来源面板显示**提问当时的标签快照**，不是 KB 当前标签。

**具体规则**：

| 场景 | 来源面板显示 |
|------|-------------|
| 正常回答 | 显示提问时的 service/environment（快照） |
| KB 后来改了标签 | 旧回答仍显示旧标签（快照不变） |
| KB 被删除 | 显示“已删除的文档”（kbId 查不到记录） |
| lookup 失败（DB 异常） | 降级显示 documentName（originalFilename），不显示 service/environment |

**实现方式**：在 `SourceReference` record 中新增两个可选字段：
```java
public record SourceReference(
    Long kbId,
    String documentName,
    String contentSnippet,
    Double score,
    String service,      // 新增：提问时的 service 快照
    String environment   // 新增：提问时的 environment 快照
) {}
```

**sourcesJson 快照**：`buildSourceReferences()` 在构建来源时一并查询并写入 service/environment。历史消息直接读取 sourcesJson，不实时查 KB 表。

**历史 sourcesJson 缺少 service/environment 标签时的显示**：来源面板显示"无标签"（不是空白），让用户知道该来源存在但标签信息缺失。

**KB 被删除后的降级显示**：KB 被删除后，仅靠快照中的 kbId 无法识别删除状态——需要前端查询 KB 是否存在来降级显示（如显示"已删除的文档"）。快照只能保证来源信息不回溯，不能单独判断 KB 是否仍存在。

**与 sourcesJson 快照不同步是产品约定**：明确声明来源标签是提问时刻的快照，后续 KB 标签变更不影响已持久化的来源。

**批量获取和刷新恢复**：
- `getSessionDetail()` 返回的消息已包含 sourcesJson（含 service/environment 快照）
- 前端从 sourcesJson 直接解析，无需额外 API 调用
- 刷新页面后重新加载会话详情即可恢复

**DTO/API/前端改动范围**：
- 后端 `SourceReference.java`：新增 service + environment 字段
- 后端 `RagChatSessionService.buildSourceReferences()`：查询时一并获取 service/environment
- 前端 `ragChat.ts` 的 `SourceReference` 接口：新增 service + environment 字段
- 前端 `KnowledgeBaseQueryPage.tsx` 来源面板：显示 service/environment 标签

**SSE 契约不变**：data → 持久化 → sources → done(status)

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

- `stream.ts` — SSE 解析（onSources/onDone）已完善

### 5.2 需新增改动

| 文件 | 改动 |
|------|------|
| `KnowledgeBaseUploadPage.tsx` / `FileUploadCard.tsx` | 上传表单增加服务/环境输入（可选字段） |
| `KnowledgeBaseManagePage.tsx` | 增加服务/环境筛选列和筛选下拉 |
| `api/knowledgebase.ts` | 上传 API 增加 service/environment 参数；列表 API 增加筛选参数 |
| `api/ragChat.ts` | `SourceReference` 接口新增 service + environment 字段 |
| `KnowledgeBaseQueryPage.tsx` | 来源面板显示 service/environment 快照标签；右栏知识库列表显示标签 |

---

## 6. 命名与迁移决策表

| 项目 | 当前值 | 决策 | 理由 |
|------|--------|------|------|
| Java 根包 `interview.guide` | 数百文件 | **保留** | 改名成本极高，无功能收益 |
| 数据库名 `interview_guide` | 配置/环境变量 | **保留** | 需 pg_dump/restore，风险大 |
| S3 bucket `interview-guide` | 创建后不可改 | **保留** | S3/MinIO 不支持 bucket rename 命令，但可通过"新建 bucket → 复制对象 → 删除旧 bucket → 更新 `app.storage.bucket` 配置"实现迁移。成本在于数据搬迁和停机窗口，非技术不可能 |
| 容器名前缀 `interview-*` | compose 文件 | **保留** | 改后旧卷不兼容 |
| JPA 表名（面试相关 6 张） | 后端代码仍在 | **保留** | 改名需 Flyway + 数据迁移 |
| 前端展示名 | 已改为 DevSupport | **已完成** | 用户可见层已改 |
| Prompt 模板 | 已改为 DevSupport | **已完成** | 语义已改 |
| README/AGENTS.md | 仍为面试官标题 | **Phase 1 内更新** | 文档级改动，低风险 |
| 包名/表名/库名 | 全部 interview | **后续阶段** | 单独迁移 PR |

---

## 7. 开发批次

### 批次 A：服务/环境数据模型

- **文件**：`KnowledgeBaseEntity.java`、`V20261001__add_service_environment.sql`、`KnowledgeBaseRepository.java`、`KnowledgeBaseEntityTest.java`、`KnowledgeBaseRepositoryTest.java`
- **内容**：新增 service/environment 列，不设 DEFAULT；旧行保持 NULL；新增查询方法；新增 Entity 和 Repository 测试
- **依赖**：无
- **验收**：
  - 编译通过（`./gradlew :app:compileJava` exit 0，**已验证**）
  - Flyway 迁移需真实 PostgreSQL 验证（**未验证**，Docker 未启动）
  - 单元测试因 GradleWorkerMain 环境问题无法执行（**未验证**）
- **待办（移至批次 B）**：上传带标签、列表筛选的端到端验收
- **回退**：已应用的 Flyway 迁移不能通过删除脚本回退，需新增反向迁移脚本。未应用的迁移可以安全删除

### 批次 B：上传 API + 管理页面筛选

- **文件**：`KnowledgeBaseUploadService.java`、`KnowledgeBaseController.java`、`KnowledgeBaseListService.java`、`KnowledgeBaseUploadPage.tsx`、`FileUploadCard.tsx`、`KnowledgeBaseManagePage.tsx`、`api/knowledgebase.ts`
- **内容**：上传方法增加 service/environment 参数（可选）；列表端点增加筛选参数；前端上传表单增加可选字段；管理页面增加筛选下拉和行内编辑
- **依赖**：批次 A
- **验收**：`./gradlew :app:test --no-daemon` 通过 + `cd frontend && pnpm run build` 通过
- **测试**：H2/Mockito 可测（上传带/不带标签、列表筛选）
- **待办**：
  - 扩展重复上传响应：当 fileHash 重复时，返回已有记录的 service/environment 信息
  - 测试原标签不变：重复上传不修改原 KB 的 service/environment
  - 访问次数现有行为：`accessCount` 和 `lastAccessedAt` 在 `downloadKnowledgeBase()` 时更新（已有逻辑，无需新增）
- **回退**：恢复旧方法签名
- **未验证**：真实上传端到端

### 批次 C：SourceReference 增加 service/environment 字段 + 前端来源面板

- **文件**：`SourceReference.java`、`RagChatSessionService.java`（`buildSourceReferences()`）、`api/ragChat.ts`、`KnowledgeBaseQueryPage.tsx`
- **内容**：SourceReference 新增 service + environment 字段；`buildSourceReferences()` 查询时一并获取 service/environment；前端来源面板显示快照标签
- **依赖**：批次 A
- **验收**：`./gradlew :app:test --no-daemon` 通过 + `cd frontend && pnpm run build` 通过
- **测试**：H2/Mockito 可测（buildSourceReferences 返回含 service/environment 的 SourceReference）
- **回退**：恢复旧 SourceReference 签名
- **未验证**：真实检索端到端

### 批次 D：文档更新

- **文件**：`README.md`、`AGENTS.md`、`PROJECT_PROGRESS.md`、`CHANGES.md`
- **内容**：文档内容与实现一致
- **依赖**：批次 A-C
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

- **风险**：用户选了“用户中心”的知识库，但检索时 kbIds 过滤错误地包含了其他服务的文档
- **防护**：service/environment 是组织标签，检索范围由用户勾选的 kbIds 决定。`similaritySearch()` 的 `filterExpression` 只传入用户勾选的 kbIds
- **服务端不校验**：不校验会话中的 KB 是否属于某 service/environment
- **测试点**：上传两个不同服务的文档，检索时验证只返回用户勾选的 KB 对应的文档

### 9.2 旧文档 NULL 检索语义

**三种场景规则**（与 §3.6 一致）：

| 文档标签 | 管理页面筛选行为 |
|----------|----------|
| service=NULL, environment=NULL（未分类） | 只在用户未选任何筛选条件时显示 |
| service=A, environment=NULL | 选择 service=A 时显示 |
| service=A, environment=生产 | 选择 service=A + environment=生产 时显示 |

**注意**：service/environment 是管理页面的分类标签，不影响向量检索本身。检索范围始终由 kbIds 决定。

### 9.3 VectorService fallback 的 Phase 1 策略

**主路径**：`buildKbFilterExpression(kbIds)` 构建 pgvector 过滤表达式，在数据库层面限制检索范围。

**Fallback 路径**：主路径过滤表达式失败时（如 pgvector 扩展异常），执行无前置过滤的全局候选检索（topK*3 扩大召回），然后在应用层按 `kb_id` 做本地过滤。

**Fallback 的局限（未经实测的假设）**：检索执行范围（recall）不受 kbIds 约束，只有最终返回结果受约束。这意味着向量索引中不属于目标 KB 的文档可能参与了距离计算，**可能**影响排序质量。以上 recall 和排序质量影响为理论分析，**未经真实数据验证**。

**Phase 1 允许 fallback 存在**：
- Fallback 是容错机制，改为"直接失败"会降低可用性
- 当前 kbIds 来自用户显式勾选，范围已经很小（通常 < 10 个 KB）
- KB 数量对排序影响的表述是**未经实测的假设**，实际影响取决于数据分布和 KB 数量
- 排序质量影响在实际场景中**假设**可接受（待真实数据验证）

**反例测试**（H2/Mockito 可测）：
- Mock VectorStore 主路径抛异常 → 验证 fallback 路径只返回 kbIds 范围内的文档
- 构造不属于 kbIds 的 Document → 验证 fallback 本地过滤将其排除

### 9.4 来源与实际检索范围不一致

- **风险**：sources 中的 documentName 来自不在本次检索范围内的知识库
- **防护**：`buildSourceReferences()` 只处理本次检索返回的 `List<Document>`
- **测试点**：验证每个 SourceReference.kbId 在请求的 kbIds 列表中

### 9.5 SSE 中断后刷新恢复

- **风险**：客户端断开后重新加载会话，消息状态丢失或显示为正常完成
- **防护**：`doOnCancel` 持久化 `CLIENT_DISCONNECTED` 状态，`getSessionDetail` 返回持久化状态
- **测试点**：模拟中断后查询消息状态，验证 status=CLIENT_DISCONNECTED

### 9.6 历史数据迁移造成的信息混用

- **风险**：后续引入权限时，历史数据未标权限导致信息泄露
- **防护**：Phase 1 不涉及权限，数据模型预留扩展点（service/environment 字段可为 null）
- **测试点**：Phase 1 不测试（无权限功能）

---

## 10. 未解决的问题

1. **环境枚举**：是否预定义环境列表（生产/预发/测试/通用），还是自由文本？
2. **后续阶段是否解除 fileHash 唯一约束**：Phase 1 不支持同一文件跨服务独立归属，后续阶段评估
3. **vector metadata 是否增加 service/environment**：如果增加，可在向量层直接过滤（性能更好）；当前通过 kbIds 间接过滤（改动更小）
4. **权限系统**：后续是否需要基于 service/environment 的权限控制？
5. **`queryKnowledgeBaseStream` 错误兜底**：适配 `RetrievalResult` 后的兜底是否完整？
