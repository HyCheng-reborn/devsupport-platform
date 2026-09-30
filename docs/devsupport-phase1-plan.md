# DevSupport 第一阶段改造方案

> 当前 HEAD: `5cd99949` (master)，远程 `HyCheng-reborn/devsupport-platform`
> 本方案只读调研产出，未修改任何代码。

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
- [KnowledgeBaseUploadService](file:///c:/IdeaProjects/interview-guide/app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseUploadService.java)
- [KnowledgeBaseVectorService](file:///c:/IdeaProjects/interview-guide/app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseVectorService.java)
- [DocumentParseService](file:///c:/IdeaProjects/interview-guide/app/src/main/java/interview/guide/infrastructure/file/DocumentParseService.java)
- [VectorizeStreamConsumer](file:///c:/IdeaProjects/interview-guide/app/src/main/java/interview/guide/modules/knowledgebase/listener/VectorizeStreamConsumer.java)
- [VectorRepository](file:///c:/IdeaProjects/interview-guide/app/src/main/java/interview/guide/modules/knowledgebase/repository/VectorRepository.java)

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
- [KnowledgeBaseQueryService](file:///c:/IdeaProjects/interview-guide/app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseQueryService.java)
- [RagChatSessionService](file:///c:/IdeaProjects/interview-guide/app/src/main/java/interview/guide/modules/knowledgebase/service/RagChatSessionService.java)
- [KnowledgeBaseQueryProperties](file:///c:/IdeaProjects/interview-guide/app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseQueryProperties.java)

### 1.3 可直接复用的能力清单

| 能力 | 实现位置 | 复用程度 |
|------|----------|----------|
| 文档上传/解析/去重 | `KnowledgeBaseUploadService` + `infrastructure/file/` | 100% 复用 |
| 异步向量化 (Redis Stream) | `VectorizeStreamProducer/Consumer` | 100% 复用 |
| pgvector 向量检索 | `KnowledgeBaseVectorService` + `VectorRepository` | 100% 复用 |
| RAG 流式问答 | `KnowledgeBaseQueryService` + `RagChatSessionService` | 需增强来源返回 |
| 多会话聊天管理 | `rag_chat_sessions` + `rag_chat_messages` | 100% 复用 |
| 知识库 CRUD/分类/搜索 | `KnowledgeBaseListService/DeleteService` | 100% 复用 |
| LLM 多 Provider 管理 | `LlmProviderRegistry` | 100% 复用 |
| 前端知识库管理页 | `KnowledgeBaseManagePage` | 100% 复用 |
| 前端 RAG 聊天页 | `KnowledgeBaseQueryPage` | 需改 UI 文案 |
| 前端上传页 | `KnowledgeBaseUploadPage` | 100% 复用 |
| Prompt 模板 (3个KB相关) | `prompts/knowledgebase-*.st` | 需改写为 DevSupport 场景 |

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
- 文档分类管理（按项目/模块/故障类型分类）

**明确排除**:
- 面试/简历/语音相关功能（仅隐藏入口，不删除代码）
- 题目生成与题库管理（隐藏入口）
- 工单/事件系统、runbook、oncall（后续阶段）
- P1-C 真实 L1 评测执行（需用户手动启动 Docker + 配置 `.env.eval`）

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
| **数据模型** | 14张表（含面试/简历/语音6张表），`knowledge_base_questions` 含大量面试题目字段 | 核心使用4张表：`knowledge_bases`、`vector_store`、`rag_chat_sessions`、`rag_chat_messages`；面试相关表保留但不写入新数据 |
| **后端接口** | 3个KB Controller（含面试/题目生成共 ~25 个端点） | 保留 `KnowledgeBaseController`（14端点）+ `RagChatController`（8端点）= 22个活跃端点；移除面试/题目端点的路由暴露 |
| **检索流程** | RAG 问答 + 查询改写 + 动态 topK/minScore | 完全复用，无变化；Prompt 模板从"面试知识问答"改为"研发知识/故障排查问答" |
| **来源标注** | Prompt 中有"引用来源"指令，但 API 响应不返回结构化来源 | **新增**: API 响应增加 `sources[]` 字段（文档名、chunk 内容、相似度分数） |
| **权限** | 无认证（单机部署） | 第一阶段不变，保持无认证 |
| **前端交互** | 导航含面试中心、简历上传、语音面试、知识库、设置 | 导航简化为：知识库管理 + 问答助手 + 设置；面试相关页面从路由表移除 |
| **项目命名** | `interview-guide` / `interview.guide` / `ai-interview-platform` | 见第四节迁移策略 |
| **Prompt 语义** | "你是一个面试知识助手" | "你是一个研发团队知识与故障排查助手" |

---

## 三、按依赖排序的改造任务

### Task 0: P1-C 真实环境验证（前置条件，手动操作）

- **内容**: 用户手动启动 Docker Desktop → 创建 `.env.eval` → 运行 `docker-compose-eval.yml` → 执行 `evalP1cReal` 获取 L1 基线数字
- **涉及文件**: `docker-compose-eval.yml`, `docker/postgres/eval-init.sql`, `.env.eval.example`
- **验收**: `p1c-l1-report.json` 生成，含非 null 的 Hit@K / MRR@K / APC@K 指标
- **工作量**: 约 30 分钟（含费用约 0.02 元）
- **依赖**: 无，但需要用户手动执行
- **阻塞**: Task 7（端到端评测验证）

### Task 1: 项目命名与配置迁移（基础层，无运行时依赖）

- **内容**: 改名不改逻辑，纯机械替换
- **涉及文件与改动**:

| 改动项 | 文件 | 当前值 → 新值 |
|--------|------|----------------|
| Gradle rootProject.name | `settings.gradle:22` | `interview-guide` → `devsupport-platform` |
| Gradle group | `app/build.gradle:12` | `com.interview` → `com.devsupport` |
| Spring application.name | `application.yml:32` | `ai-interview-platform` → `devsupport-platform` |
| 前端 package name | `frontend/package.json:2` | `ai-interview-frontend` → `devsupport-frontend` |
| 前端页面标题 | `frontend/index.html:7` | `AI智能面试官` → `DevSupport - 研发知识平台` |
| S3 bucket 默认值 | `application.yml:196`, `.env`, `.env.example` | `interview-guide` → `devsupport` |
| 容器名前缀 | 3个 compose 文件 | `interview-*` → `devsupport-*` |
| 数据库名 | `application.yml:42`, compose 文件, `.env*` | `interview_guide` → `devsupport` |
| 本地配置路径 | `application.yml:108-109` | `.interview-guide/` → `.devsupport/` |
| 文件头注释 | `app/build.gradle`, `application.yml`, `App.java` | 更新为 DevSupport |

- **暂不改动（高成本，单独迁移任务）**:
  - Java 包名 `interview.guide` → 涉及数百文件，建议用 IDE Refactor → Rename Package 单独处理
  - JPA 表名 → 需要 Flyway 迁移脚本 + 数据迁移
  - 评测数据库名 `interview_guide_eval` → 跟随主库一起改
- **验收**: `./gradlew :app:compileJava` 通过；`docker compose -f docker-compose.dev.yml up -d` 容器名正确；前端 `pnpm run build` 通过
- **工作量**: 2-3 小时
- **依赖**: 无

### Task 2: 前端导航与路由精简

- **内容**: 从导航和路由表中移除面试相关入口，保留知识库管理 + RAG 问答 + 设置
- **涉及文件**:
  - `frontend/src/App.tsx` — 移除 `/interview-hub`, `/interview/*`, `/voice-interview`, `/knowledgebase-interview/*`, `/resumes/*`, `/interview-schedule/*` 路由
  - `frontend/src/components/Layout.tsx` — 导航菜单简化为：知识库管理、问答助手、设置
  - `frontend/src/constants/routes.ts` — 移除面试相关路由常量
  - `frontend/src/pages/KnowledgeBaseManagePage.tsx` — 移除"面试"相关导航按钮（如有）
- **验收**: 前端构建通过；浏览器访问只有知识库管理 + 问答助手 + 设置三个入口
- **工作量**: 1-2 小时
- **依赖**: 无（可与 Task 1 并行）

### Task 3: RAG 来源标注增强（核心新功能）

- **内容**: 让 RAG 回答返回结构化的来源信息
- **涉及文件**:
  - 后端 `KnowledgeBaseQueryService.java` — 在检索阶段收集 `sources` 列表（kb_id → 查 `knowledge_bases` 表获取 name，chunk content，similarity score）
  - 后端 `RagChatSessionService.java` — SSE 流结束后附加 `sources` 元数据事件
  - 新增 DTO: `SourceReference`（kbId, kbName, content, score）
  - 前端 `KnowledgeBaseQueryPage.tsx` — 在 AI 回答下方渲染来源引用列表（可折叠展示文档名和相关片段）
  - 前端 `api/ragChat.ts` — 解析 SSE 中的 sources 事件
- **验收**: 提问后回答下方显示引用来源（文档名 + 相关文本片段 + 相似度分数）
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

### Task 5: 后端接口清理与分类标注

- **内容**: 不删除面试模块代码，但通过配置或路由调整使其不在 DevSupport 中暴露
- **方案选择**:
  - **方案 A（推荐）**: 在 `KnowledgeBaseController` 中移除题目生成相关端点的 `@RequestMapping`（注释保留），前端已不访问这些端点
  - **方案 B**: 添加 `@Profile("interview")` 注解，默认不激活面试模块
- **涉及文件**:
  - `KnowledgeBaseInterviewController.java` — 禁用或 Profile 隔离
  - 题目生成相关端点 — 同上
- **验收**: 启动后访问 `/api/knowledgebase-interviews/*` 返回 404；`/api/knowledgebase/*` 和 `/api/rag-chat/*` 正常工作
- **工作量**: 1 小时
- **依赖**: Task 1（命名迁移完成后统一调整）

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

### Task 7: 端到端验证

- **内容**: 启动完整 dev 环境，执行端到端流程验证
- **步骤**:
  1. `docker compose -f docker-compose.dev.yml up -d`（新容器名）
  2. 上传 devsupport-v0.1 corpus 中的 2-3 份文档
  3. 等待向量化完成
  4. 在问答助手中提问（参考 eval 的 20 道题中 2-3 道）
  5. 验证来源标注正确显示
- **验收**: 完整流程走通，回答带来源
- **工作量**: 1-2 小时
- **依赖**: Task 1-6 全部完成；Task 0（P1-C 真实 L1 结果用于对比检索质量）

### 依赖关系图

```
Task 0 (P1-C 验证, 手动) ──────────────────────────────┐
                                                         │
Task 1 (命名迁移) ──→ Task 5 (接口清理) ──→ Task 6 (文档) │
                                                         │
Task 2 (前端精简) ──────────────────────────→ Task 7 (E2E 验证)
                                                         │
Task 3 (来源标注) ──────────────────────────→ Task 7     │
                                                         │
Task 4 (Prompt适配) ────────────────────────→ Task 7     │
                                                         │
Task 0 ─────────────────────────────────────→ Task 7 ───┘
```

### 并行策略

**可并行的任务组**:
- **Group A**（基础设施层）: Task 1（命名迁移）
- **Group B**（前端层）: Task 2（导航精简）、Task 3（来源标注前端部分）
- **Group C**（后端层）: Task 3（来源标注后端部分）、Task 4（Prompt 适配）
- **Group D**（手动操作）: Task 0（P1-C 验证）

Group A/B/C/D 四组可同时开工。Task 5 等 Task 1 完成后执行。Task 6 等 Task 1 + Task 5 完成后执行。Task 7 等所有前置完成后执行。

---

## 四、迁移策略

### 4.1 包名迁移（建议延后到 Phase 1 功能交付后）

Java 包名 `interview.guide` → `devsupport` 涉及数百文件的 `package` 和 `import` 语句，建议：
1. Phase 1 功能交付后，单独开一个 "Rename Package" PR
2. 使用 IntelliJ IDEA 的 Refactor → Rename Package 功能
3. 同步更新 `application.yml` 的 `spring.autoconfigure.base-packages`
4. 更新测试包名

### 4.2 表名迁移（建议延后）

14 张表中，6 张面试表（`interview_sessions`, `interview_answers`, `interview_schedule`, `resumes`, `resume_analyses`, `voice_interview_*`）在 DevSupport 中不再使用。建议：
1. Phase 1 不改动表名，避免数据迁移风险
2. 后续如需清理，通过新 Flyway 迁移脚本 `ALTER TABLE ... RENAME TO ...`
3. `knowledge_bases`、`rag_chat_*`、`vector_store` 表名无需改动

### 4.3 旧数据处理

- 开发环境: `ddl-auto: update`，可以直接 drop 旧表重建
- 生产环境: 需要 `pg_dump` → 改表名 → `pg_restore`
- S3 bucket: 新建 `devsupport` bucket，旧 `interview-guide` bucket 数据手动迁移或保留

### 4.4 仓库名称

- 当前远程: `HyCheng-reborn/devsupport-platform`（已正确）
- 上游只读: `Snailclimb/interview-guide`（不推送）
- 本地 `.git/config` 中的 remote 名称无需改动

---

## 五、与 P1-C 真实 L1 的关系

| 场景 | 说明 |
|------|------|
| Task 0 通过 | 获得 L1 基线数字（Hit@K / MRR@K / APC@K），可作为 Task 7 E2E 验证的检索质量参照 |
| Task 0 未通过 | 说明 Embedding/向量检索有底层问题，需先修复再进入 Task 7 |
| Prompt 改动对 L1 的影响 | P1-C L1 绕过 Service 层直接调 `VectorStore.similaritySearch`，不受 Prompt 改动影响；但 Task 7 的端到端验证会受 Prompt 影响 |
| 命名迁移对评测的影响 | `docker-compose-eval.yml` 中的容器名/库名需跟随 Task 1 一起改；评测代码在 test 作用域，不受 main 包名影响 |

**结论**: Task 1-6 的编码工作不依赖 Task 0 的结果，可以先行推进。Task 7 的 E2E 验证需要 Task 0 的 L1 基线作为参照。

---

## 六、风险与注意事项

1. **不推送 origin**: 后续提交只推送到 `devsupport`（`HyCheng-reborn/devsupport-platform`），绝不推送到上游 `Snailclimb/interview-guide`
2. **评测隔离**: P1-C 评测代码全部在 test 作用域，不新增 main 源码；评测容器独立端口 5433，不连生产库
3. **面试模块保留**: 第一阶段不删除面试模块代码，只从前端入口隐藏，降低回滚风险
4. **P1-C 状态**: 当前"代码已写 + 离线通过 · 真实环境零验证"，114 条离线用例全绿，但无检索质量数字
5. **不设计预期为结果**: 方案中所有检索质量指标需等 Task 0 执行后才能填写
