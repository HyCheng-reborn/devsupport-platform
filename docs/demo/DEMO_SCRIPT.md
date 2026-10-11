# DevSupport 5–8 分钟零付费演示脚本

## 前置条件

- Docker 运行 `docker compose -f docker-compose.dev.yml up -d`
- 后端 Demo 模式：`./gradlew :app:bootRun --args='--spring.profiles.active=demo'`
- 前端：`cd frontend && pnpm run dev`
- 访问 http://localhost:5173

## 演示流程

### 1. 文档上传与异步索引（30s）
- 导航到「文档中心」
- 上传 `docs/demo/01-db-connection-pool-exhaustion.md`
- 展示：上传成功、状态变为"已完成"
- **技术点**：Apache Tika 解析 → DemoEmbeddingModel（SHA-256 确定性 1024 维向量，非语义） → pgvector 存储
- **证据边界**：向量为内容 hash，相同内容始终相同向量，不支持语义相似度检索

### 2. 创建排查会话（20s）
- 导航到「排查会话」
- 创建新会话，关联上传的知识库
- 可选：选择 service/environment 上下文
- **技术点**：KB-only 隔离，会话绑定知识库范围

### 3. SSE 排查对话（60s）
- 输入："系统启动端口是什么？"
- 展示：流式回答、来源引用面板
- **技术点**：SSE 流式响应（data → sources → done）、RAG 检索 + DemoChatModel 模板回答
- **证据边界**：回答标注 "[Demo 模板响应 — 非真实 AI 生成]"，来源为 KB 原文片段

### 4. 工具结果面板（60s）
- 输入："检查系统依赖健康状态"
- 展示：工具结果面板（Demo 模拟标识）、组件状态
- **技术点**：Spring AI Function Calling、check_dependency_health 工具、ToolContext 请求隔离
- **证据边界**：面板显示 "Demo 模拟数据，非真实探测"，demo=true

### 5. 案例管理（40s）
- 导航到「案例库」
- 展示：案例列表、从会话生成案例功能
- **技术点**：案例草稿/审核/发布流程

### 6. 回归报告（30s）
- 导航到「评测结果」
- 展示：数据集列表、回归评测结果
- **技术点**：确定性回归评测、KB-only 隔离

### 7. 会话历史回读（30s）
- 回到「排查会话」，重新打开会话
- 展示：历史消息完整、工具结果面板仍可见
- **技术点**：消息持久化（tool_calls_json）、会话回读

## 不展示的内容
- Voice 面试模块（已隐藏入口）
- 真实 LLM/Embedding 调用（需付费 API Key）
- 语义检索质量（Demo 使用非语义向量）

## 截图限制说明

浏览器验证时视口处于隐藏状态，无法截取完整页面截图。上述演示流程已在真实环境中通过浏览器走查验证，所有关键步骤（文档上传、SSE 对话、工具结果、案例库、评测、历史回读、设置页面）均确认功能正常。
