<div align="center">

**DevSupport** - 研发团队知识与故障排查平台

基于大语言模型的文档知识库 + RAG 流式问答，服务研发工程师、SRE 和技术负责人

[![Java](https://img.shields.io/badge/Java-25-orange?logo=openjdk)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1-green?logo=springboot)](https://spring.io/projects/spring-boot)
[![React](https://img.shields.io/badge/React-18.3-blue?logo=react)](https://react.dev/)
[![TypeScript](https://img.shields.io/badge/TypeScript-5.6-blue?logo=typescript)](https://www.typescriptlang.org/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-pgvector-336791?logo=postgresql)](https://www.postgresql.org/)

</div>


---

## 项目介绍

DevSupport 是面向研发团队的**知识管理与故障排查平台**：把服务文档、部署手册、复盘记录、接口说明等上传为知识库，通过 RAG 检索增强问答获得带来源引用的回答，并按**服务**和**环境**（生产 / 预发 / 通用）两个维度组织与筛选。

**来源与改造关系**：本项目 fork 自上游 [Snailclimb/interview-guide](https://github.com/Snailclimb/interview-guide)（一个 AI 面试平台）。DevSupport Phase 1 复用了上游的**文档解析 / 向量化 / RAG 流式问答 / 多 Provider 管理 / PostgreSQL + pgvector 存储**等基础设施，把用户可见的语义重定位为研发知识 & 故障排查；上游面试 / 简历 / 语音模块代码保留在后端但不作为 Phase 1 交付面（详见后文「上游面试能力保留说明」）。

## Phase 1 用户流程

1. **上传文档**：选择文件 → 可选填写名称、服务（如"支付网关"）、环境（如"生产"） → 提交；系统自动 Tika 解析、SHA-256 去重、S3 存储、Redis Stream 异步向量化
2. **管理文档**：知识库管理页支持按服务 / 环境 / 分类 / 关键字组合筛选、行内编辑标签、查看向量化状态
3. **RAG 问答**：问答助手页勾选知识库 → 输入问题 → 流式获取带来源的回答 → 来源面板显示**提问当时**的服务 / 环境标签快照 + 文档名 + 相关片段
4. **会话管理**：多会话切换、绑定知识库集合、置顶、删除、刷新恢复

## 系统架构

Phase 1 沿用上游面试项目的技术栈与运行时基础设施，用户可见层从「面试」重新定位到「研发知识」：

```
用户上传 → KnowledgeBaseController.uploadKnowledgeBase(file, name, service, environment)
  ├─ 同步: Tika 解析 → SHA-256 去重 → RustFS/S3 存储 → DB 保存元数据 (PENDING)
  └─ 异步 (Redis Stream): VectorizeStreamConsumer → KnowledgeBaseVectorService.vectorizeAndStore()
        → TokenTextSplitter (~800 tokens) → DashScope text-embedding-v3 → pgvector 1024 维 COSINE

提问 → RagChatController SSE 流式
  → KnowledgeBaseQueryService.answerQuestionStream(kbIds, question)
    → 查询改写 (LLM rewrite) → 动态 topK/minScore 检索
    → 拼接 context → System/User Prompt (knowledgebase-query-*.st)
    → LLM 流式生成 → SSE: data → 持久化 → sources (含 service/environment 标签快照) → done(status)
```

> **架构说明**：上游原项目提供的架构图外链（`oss.javaguide.cn/...`）本轮**未核验可访问性**，为不误导读者已不再引用；如需了解原始组件视图请参考上游仓库。

## 技术栈

### 后端技术

| 技术                  | 版本  | 说明                          |
| --------------------- | ----- | ----------------------------- |
| Spring Boot           | 4.1.0 | 应用框架                      |
| Java                  | 25    | 开发语言（虚拟线程）          |
| Spring AI             | 2.0.0 | AI 集成框架、OpenAI 兼容模型接入 |
| Spring AI Agent Utils | 0.10.0 | Skill 资源加载、Advisor 能力扩展 |
| PostgreSQL + pgvector | 14+   | 关系数据库 + 向量存储（Compose 默认 PG16） |
| Redis + Redisson      | 6+ / 4.0.0 | 缓存 + 消息队列（Stream） |
| Apache Tika           | 2.9.2 | 文档解析                      |
| iText 8               | 8.0.5 | PDF 导出                      |
| MapStruct             | 1.6.3 | 对象映射                      |
| SpringDoc OpenAPI     | 3.0.2 | API 接口文档                  |
| DashScope SDK         | 2.22.7 | Embedding + Chat 模型接入     |
| AWS S3 SDK            | 2.29.51 | S3 兼容对象存储（MinIO/RustFS）|
| Gradle                | 9.6.1 | 构建工具                      |

**技术选型说明**：

1. **PostgreSQL + pgvector**：关系数据与向量一体化，Phase 1 不引入独立向量库以精简组件拓扑。
2. **Redis**：`Redisson` 承担缓存与限流；`Redis Stream` 承担文档向量化等异步任务解耦。
3. **Gradle**：构建工具，`libs.versions.toml` 集中版本管理。

### 前端技术

| 技术              | 版本  | 说明           |
| ----------------- | ----- | -------------- |
| React             | 18.3  | UI 框架        |
| TypeScript        | 5.6   | 开发语言       |
| Vite              | 5.4   | 构建工具       |
| Tailwind CSS      | 4.1   | 样式框架       |
| React Router      | 7.11  | 路由管理       |
| Framer Motion     | 12.23 | 动画库         |
| Recharts          | 3.6   | 图表库         |
| Lucide React      | 0.468 | 图标库         |
| React Virtuoso    | 4.18  | RAG 聊天虚拟列表 |
| pnpm              | 10.26 | 前端包管理器   |

## 功能特性（Phase 1 已交付）

以下条目**均有离线单元测试或集成测试覆盖**（截至提交 `54c2efd`，本地 `./gradlew :app:test --no-daemon` 全量 499 tests / 0 failures / 0 errors / 50 skipped；详见 `PROJECT_PROGRESS.md`）。真实数据库 / 真实 LLM / 真实 SSE 端到端**未验证**，见后文「未验证事项」。

### 知识库管理

- **多格式解析**：Tika 支持 PDF / DOCX / Markdown / TXT 等；SHA-256 内容哈希去重（fileHash 全局唯一）。
- **服务 / 环境标签**：`KnowledgeBaseEntity` 新增 `service VARCHAR(100)` + `environment VARCHAR(50)` 列（Flyway `V20261001__add_service_environment.sql`），各带独立索引；旧行保持 NULL，视为"未分类"。
- **上传 API**：`POST /api/knowledgebase/upload` 增加可选 `service` / `environment` 表单参数；service ≤ 100 字符、environment ≤ 50 字符，Service 层在 S3/fileHash/Redis 调用之前先校验长度。
- **重复上传响应**：fileHash 命中时返回已有记录的 `service` / `environment` / 更新后的 `accessCount`，**不修改原记录标签**（`KnowledgeBasePersistenceService.handleDuplicateKnowledgeBase`）。
- **列表 API 筛选**：`GET /api/knowledgebase/list?service=&environment=&sortBy=&vectorStatus=`；新增 `GET /api/knowledgebase/services` 与 `GET /api/knowledgebase/environments` 作为下拉数据源。
- **行内编辑**：`PUT /api/knowledgebase/{id}/labels?service=&environment=` 更新已有 KB 的服务 / 环境。
- **管理页 UI**：`KnowledgeBaseManagePage.tsx` 提供搜索 / 分类 / service / environment 组合筛选（委托到已测试的 `utils/kbFilter.ts`），支持行内编辑标签。

### RAG 流式问答与来源快照

- **SSE 三事件契约**：`event: data`（文本 chunk） → 持久化 → `event: sources`（`List<SourceReference>` JSON） → `event: done`（`{status: COMPLETED | NO_RESULTS | MODEL_FAILED | CLIENT_DISCONNECTED}`）。`AtomicBoolean finalized` 协调三条终止路径。
- **来源引用 DTO**：`SourceReference(kbId, documentName, contentSnippet, score, service, environment)`——Phase 1 新增 `service` / `environment` 两个字段，是**提问当时**从 `KnowledgeBaseEntity` 快照获取，KB 后续改标签不影响已持久化来源。
- **批量查询保序**：`RagChatSessionService.buildSourceReferences()` 单次批量查 KB，保留来源顺序；KB 存在但标签为 NULL 时，来源标签字段保留 NULL。
- **持久化与回显**：`rag_chat_messages.sources_json` 存储完整来源列表；`getSessionDetail()` 直接返回历史消息（含 status 与 sources），前端刷新恢复不需要额外 API 调用。
- **来源面板 UI**：`KnowledgeBaseQueryPage.tsx` 使用 `toSourceTagView` 映射渲染 service（蓝色标签）+ environment（绿色标签）；历史 `sourcesJson` 缺字段时显示"无标签"，向后兼容。
- **消息状态视觉**：COMPLETED 蓝色 / NO_RESULTS 黄色 / MODEL_FAILED 琥珀 / CLIENT_DISCONNECTED 灰度。

### 多模型 Provider 与设置

- **多 Provider 管理**：内置 DashScope / LM Studio / Kimi / DeepSeek / GLM 等 OpenAI 兼容 Provider 配置，通过 `LlmProviderRegistry.getChatClientOrDefault(provider)` 获取 ChatClient。
- **默认模型切换**：设置页可切换默认 Chat 模型与默认 Embedding 模型，无需修改源码。
- **配置加密落盘**：运行时配置默认写入用户目录 `~/.interview-guide/`；Provider API Key 通过 `APP_AI_CONFIG_ENCRYPTION_KEY` 加密。

## 上游面试能力保留说明

**Phase 1 后端保留但不作为 DevSupport 用户交付面**：

| 模块 | 后端代码 | 前端 | 状态 |
|---|---|---|---|
| 简历分析 | `modules/resume/` | 已从导航隐藏 | 端点可用，不宣称已交付 |
| 文字面试 | `modules/interview/` | 已从导航隐藏 | 端点可用，不宣称已交付 |
| 语音面试 | `modules/voiceinterview/` | 已从导航隐藏 | 端点可用；`VoiceInterviewServiceTest` 等 46 个用例当前 `@Disabled`，代码/配置待修复 |
| 面试安排 | `modules/interviewschedule/` | 已从导航隐藏 | 端点可用，不宣称已交付 |
| 知识库题库与面试 | `KnowledgeBaseInterviewController` 等 | 已从导航隐藏 | 端点可用，不宣称已交付 |

Phase 1 前端导航（`Layout.tsx`）仅暴露：**知识库管理 / 问答助手 / 设置** 三个入口；访问面试相关旧路由会重定向到知识库首页。相关上游材料（原项目 OSS 图片链接、付费教程等）本轮未核验可访问性，不在本 README 引用。

> **注**：当前工作区有未提交的 Resume 导航改动（`Layout.tsx` 新增「简历管理」入口）。路线图阶段 1 将把主导航重建为 DevSupport 的四入口（文档中心 / 排查会话 / 案例库 / 评测结果），届时上述「三个入口」描述会随之更新。方向详见 [DEVSUPPORT_ROADMAP.md](./DEVSUPPORT_ROADMAP.md)。

## 项目结构

```
devsupport-platform/
├── app/                              # 后端应用
│   ├── src/main/java/interview/guide/
│   │   ├── App.java                  # 主启动类
│   │   ├── common/                   # 通用能力（限流、AI、异步模板、配置、异常、Result）
│   │   ├── infrastructure/           # 基础设施（文件、导出、Redis、MapStruct）
│   │   └── modules/                  # 业务模块
│   │       ├── knowledgebase/        # 知识库 + RAG（Phase 1 主战场）
│   │       ├── llmprovider/          # 多 Provider 与语音配置管理
│   │       ├── interview/            # 上游面试模块，Phase 1 保留但不暴露入口
│   │       ├── interviewschedule/    # 上游面试安排，保留
│   │       ├── resume/               # 上游简历，保留
│   │       └── voiceinterview/       # 上游语音面试，保留
│   └── src/main/resources/
│       ├── application.yml
│       ├── db/migration/             # Flyway 迁移（V1__init_schema.sql + 增量脚本）
│       ├── prompts/                  # StringTemplate `.st` Prompt
│       └── scripts/                  # Redis Lua 脚本
│
├── frontend/                         # React 前端
│   ├── src/api/                      # Axios 集中
│   ├── src/components/               # 公共组件
│   ├── src/pages/                    # 页面（Phase 1 主要：KnowledgeBase*）
│   ├── src/types/                    # 类型定义
│   └── src/utils/                    # 工具（kbFilter / sourceDisplay 等）
│
├── eval/datasets/devsupport-v0.1/    # P1-C 检索评测语料与冻结产物
├── docs/                             # 架构设计与改造记录
├── devsupport-phase1-design.md       # Phase 1 设计文档
├── PROJECT_PROGRESS.md               # 跨会话任务进度事实源
├── CHANGES.md                        # 每次改动记录
├── docker-compose.yml                # 完整部署（前端 + 后端 + PostgreSQL + Redis + MinIO）
├── docker-compose.dev.yml            # 本地开发依赖（PostgreSQL + Redis + RustFS）
├── docker-compose-eval.yml           # P1-C 评测容器（独立端口 5433）
├── .env.example                      # 环境变量示例
└── README.md
```

## 快速开始

环境要求：

| 依赖          | 版本 | 必需 | 说明                                     |
| ------------- | ---- | ---- | ---------------------------------------- |
| JDK           | 25   | 是   | 开发语言                                 |
| Node.js       | 18+  | 是   | 前端构建                                 |
| pnpm          | 10+  | 推荐 | 前端包管理器（`packageManager` 指定 10.26）|
| Docker        | -    | 推荐 | 一键启动依赖服务（PostgreSQL/Redis/RustFS）|

> 如果不用 Docker，需要自行安装 PostgreSQL 14+（含 pgvector 扩展）、Redis 6+ 和 S3 兼容存储。

### 1. 克隆项目

```bash
git clone https://github.com/HyCheng-reborn/devsupport-platform.git
cd devsupport-platform
```

> 上游只读参考仓库：`Snailclimb/interview-guide`（不向其推送）。DevSupport fork 的推送目标为 `HyCheng-reborn/devsupport-platform`。

### 2. 配置环境变量

推荐复制 `.env.example` 为 `.env`，后端 `bootRun` 会自动读取根目录 `.env`。最少需要填写 `AI_BAILIAN_API_KEY`，用于 DashScope 文本模型与 Embedding：

```bash
cp .env.example .env

# 编辑 .env
# AI_BAILIAN_API_KEY=your_dashscope_api_key
# APP_AI_CONFIG_ENCRYPTION_KEY=your_random_long_secret
# AI_MODEL=qwen3.5-flash   # 默认值
```

### 3. 启动依赖服务（可选）

```bash
docker compose -f docker-compose.dev.yml up -d
```

> **Phase 1 未核验**：`docker compose -f docker-compose.dev.yml up -d` 是否在你本机成功拉起 Postgres/Redis/RustFS，取决于本机 Docker 与端口占用；本轮补验**未启动任何容器**，未做真实依赖服务验证。

依赖容器起来后默认账号（与 `docker-compose.dev.yml` 一致）：

| 服务         | 地址             | 账号            | 密码            |
| ------------ | ---------------- | --------------- | --------------- |
| PostgreSQL   | `localhost:5432` | `postgres`      | 参见 `.env` `POSTGRES_PASSWORD` |
| Redis        | `localhost:6379` | -               | -               |
| RustFS 控制台 | `localhost:9001` | `rustfsadmin`   | `rustfsadmin`   |

> `docker-compose.yml` 与 `docker-compose.dev.yml` 的 `POSTGRES_PASSWORD` 已改为 `${POSTGRES_PASSWORD:?...}` **必填插值语法**（缺失或为空时 Compose 直接拒绝启动）；Spring 端 `application.yml` 使用 `${POSTGRES_PASSWORD}`（无默认值），DataSource 缺变量时 fail-closed。

### 4. 启动应用

**后端：**

```bash
./gradlew :app:bootRun
```

后端服务启动于 `http://localhost:8080`

**前端：**

```bash
cd frontend
corepack enable
pnpm install
pnpm dev
```

前端服务启动于 `http://localhost:5173`

## Docker 快速部署

本项目提供了完整的 Docker 支持（前端 Nginx + Spring Boot 后端 + PostgreSQL + Redis + MinIO）。编排文件：`docker-compose.yml`。

### 快速启动

```bash
cp .env.example .env
# 编辑 .env，必填：
#   AI_BAILIAN_API_KEY=your_key_here
#   APP_AI_CONFIG_ENCRYPTION_KEY=your_random_long_secret
#   POSTGRES_PASSWORD=<必填，Compose `:?` 语法>
docker-compose up -d --build
```

### 服务访问

| 服务             | 地址                                           | 默认账号     | 说明                   |
| ---------------- | ---------------------------------------------- | ------------ | ---------------------- |
| **前端应用**     | [http://localhost](http://localhost)           | -            | 用户访问入口           |
| **后端 API**     | [http://localhost:8080](http://localhost:8080) | -            | RESTful API            |
| **接口文档**     | [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html) | - | SpringDoc/Swagger UI |
| **MinIO 控制台** | [http://localhost:9001](http://localhost:9001) | `minioadmin` | 对象存储管理           |
| **MinIO API**    | `localhost:9000`                               | -            | S3 兼容接口            |
| **PostgreSQL**   | `localhost:5432`                               | `postgres`   | 数据库 + pgvector      |
| **Redis**        | `localhost:6379`                               | -            | 缓存与消息队列         |

### 常用运维命令

```bash
docker-compose ps              # 状态
docker-compose logs -f app     # 后端日志
docker-compose up -d --build   # 拉新代码重建
docker-compose down            # 停止（数据保留在 Docker 卷）
docker-compose down -v         # 停止并清除数据卷（慎用）
```

## 使用场景

| 用户角色        | 使用场景                                                       |
| --------------- | -------------------------------------------------------------- |
| **研发工程师**  | 上传服务设计文档 / 接口说明 / 复盘记录；按 service 标签检索   |
| **SRE / 运维**  | 按环境（生产 / 预发 / 通用）分类运维手册；提问定位故障        |
| **技术负责人**  | 治理团队知识库；服务/环境维度组织，来源面板显示提问当时标签快照 |

## 常见问题

### Q: 数据库表创建失败 / 数据丢失

本地开发首先检查 JPA `ddl-auto` 配置。

| 模式     | 行为                            | 适用场景      | 数据保留 |
| -------- | ------------------------------- | ------------- | -------- |
| update   | 表不存在自动创建，存在则增量更新 | 早期开发或临时实验，当前项目不推荐 | ✅ 保留 |
| create   | 无条件删除并重建所有表          | 仅首次建表时使用 | ❌ 删除 |
| **validate** | 只验证，不修改                  | **当前项目默认推荐，建表和变更交给 Flyway** | ✅ 保留 |
| none     | 什么都不做                      | 生产环境      | ✅ 保留 |

⚠️ **注意**：避免使用 `create` 模式，否则每次重启都会删除所有数据！

### Q: 知识库向量化失败

`vector_store` 表已由 Flyway 创建，Spring AI 不再自动建表。保持：

```yaml
spring:
  ai:
    vectorstore:
      pgvector:
        initialize-schema: false
```

避免应用启动时绕过 Flyway 修改数据库 schema。

### Q: 数据库迁移需要手动执行脚本吗？

不需要。Flyway 已接入，后端启动时自动执行 `app/src/main/resources/db/migration/` 下的迁移，记录到 `flyway_schema_history`。当前项目通过 `V1__init_schema.sql` 支持空库初始化，后续版本通过增量迁移演进；Hibernate `ddl-auto` 只做 `validate` 校验。测试环境使用 H2，默认关闭 Flyway。

### Q: 启动时报 `Connection to localhost:5432 refused`

通常不是 Flyway 脚本错误，而是宿主机访问不到 Postgres。确认依赖容器已启动并且端口已发布：

```bash
docker compose -f docker-compose.dev.yml up -d
docker ps --format '{{.Names}} {{.Ports}}'
```

`interview-postgres` 应显示 `0.0.0.0:5432->5432/tcp`；若只显示 `5432/tcp` 说明未发布端口，重建容器：

```bash
docker compose -f docker-compose.dev.yml up -d --force-recreate postgres redis
```

如果本机 `5432` / `6379` 被其他项目占用，修改 `.env` 中的 `POSTGRES_PORT` / `REDIS_PORT`，并同步 `docker-compose.dev.yml` 的端口映射。

### Q: Embedding / 问答调用失败

检查 `AI_BAILIAN_API_KEY` 是否配置正确（阿里云百炼控制台：<https://bailian.console.aliyun.com/>）。

### Q: 设置页新增 / 切换 Provider 后不生效？

运行时 Provider 配置默认写到 `~/.interview-guide/llm-providers.yml` 与 `llm-providers.env`。可以在设置页点击"测试连接"，或调用 `POST /api/llm-provider/reload` 重新加载。Docker 部署时如需持久化，为 `~/.interview-guide/` 挂载卷。

> **Phase 1 命名保留**：目录名 `~/.interview-guide/` 未随项目改名（详见「来源与保留说明」）。

### Q: 上传后来源面板为什么没显示服务 / 环境标签？

标签来自**提问当时**的 KB 快照：
- 如果 KB 的 `service` / `environment` 都是 NULL，来源面板显示"无标签"（属正常）；
- 如果是 Phase 1 之前上传的旧 KB，`service` / `environment` 列为 NULL 是设计决策（不设 DEFAULT），可在管理页行内编辑补上标签；
- 历史消息 `sourcesJson` 缺少 `service` / `environment` 字段时，前端会向后兼容显示"无标签"。

### Q: PDF 导出失败或中文显示异常？

项目内置中文字体（珠圆玉润仿宋），支持跨平台导出。检查 `app/src/main/resources/fonts/ZhuqueFangsong-Regular.ttf`、日志中的字体加载信息、iText 依赖是否正确。

### Q: Windows PowerShell 下后端日志中文乱码？

**原因**：后端与 Logback 按 **UTF-8** 输出；中文 Windows 控制台默认 **GBK**（代码页 936），字节解释错位显示乱码。

**已配置**：根目录 `gradle.properties`、`app/src/main/resources/logback-spring.xml`、`app/build.gradle` 中 `bootRun` 的 JVM 参数（`file.encoding` / `stdout.encoding` / `stderr.encoding`）。

**仍乱码时在 PowerShell 中执行**：

```powershell
chcp 65001 | Out-Null
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::InputEncoding  = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)
```

可写入 `$PROFILE` 让每次自动生效；若脚本无法执行，运行 `Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned`。

## 未验证事项（诚实清单）

以下**Phase 1 未真实环境执行**，本 README 不宣称已验证：

| 项 | 状态 |
|---|---|
| 真实 PostgreSQL + Flyway 迁移（`V20261001__add_service_environment.sql`）执行 | 未验证，本轮未启动容器 |
| 真实 Redis / Stream Consumer 端到端向量化 | 未验证 |
| 真实 S3 / RustFS 上传与桶创建 | 未验证 |
| 真实 DashScope LLM + Embedding API 调用 | 未验证，涉及付费 API |
| SSE 端到端 `data → sources → done` 浏览器 / curl 实测 | 未验证，仅 StepVerifier 单元测试覆盖 |
| Docker-based `RateLimitIntegrationTest`（4 用例）| 未运行，需启动 Docker Desktop |
| `VoiceInterviewServiceTest` 等 46 个 `@Disabled` 用例的目标行为 | 未验证（属代码 / 配置待修复） |
| 真实上传端到端后管理页筛选 / 来源面板标签实际显示 | 未验证（属集成环境） |
| `docker-compose.yml` 与 `docker-compose.dev.yml` 的 `:?` 必填语法在真实容器启动时的确切呈现 | 未验证（仅 `docker compose config` 静态渲染验证） |
| Spring 缺变量真实启动堆栈 | 未验证 |
| P1-C 真实 L1 评测（Gate 0a–0d） | 未执行 |

## 来源与保留说明

**Fork 关系**：本仓库 fork 自 [Snailclimb/interview-guide](https://github.com/Snailclimb/interview-guide)（上游 AI 面试项目）。DevSupport Phase 1 是**渐进改造**，只重定位用户可见层与新增 service/environment 标签，不改数据面 / 基础设施名称。

**Phase 1 明确保留的名称**（依据 `devsupport-phase1-design.md` §6 决策表）：

| 项目 | 当前值 | 决策 |
|---|---|---|
| Java 根包 | `interview.guide` | **保留**（数百文件，改名成本极高） |
| 数据库名 | `interview_guide` | **保留**（需 pg_dump/restore） |
| S3 bucket | `interview-guide` | **保留**（Compose 默认配置） |
| 容器名前缀 | `interview-*` | **保留**（改名后旧卷不兼容） |
| JPA 表名 | 面试相关 6 张 | **保留**（改名需 Flyway 迁移） |
| 本地配置目录 | `~/.interview-guide/` | **保留** |
| Gradle `rootProject.name` / `group` | `interview-guide` / `com.interview` | **保留** |
| 前端展示名 | DevSupport | **已改** |
| Prompt 语义 | 研发团队知识 & 故障排查助手 | **已改** |
| README / AGENTS.md | DevSupport | **本轮 Phase 1 内更新（批次 D）** |

**上游作者维护的付费教程**：本项目改造不依赖该教程，本 README 不再引用其外链；如需了解上游原始面试项目背景，请直接访问上游仓库。

## 项目路线图

详见 [DEVSUPPORT_ROADMAP.md](./DEVSUPPORT_ROADMAP.md) — 产品定位、开发阶段、验收标准与 Agent 执行边界。

## 贡献

欢迎提交 Issue 和 Pull Request。改动前后请阅读 `PROJECT_PROGRESS.md`（跨会话任务进度事实源）与 `CHANGES.md`（每次改动记录），保持状态同步。

## 许可证

AGPL-3.0 License（继承自上游；只要通过网络提供服务，就必须向用户公开修改后的源码）
