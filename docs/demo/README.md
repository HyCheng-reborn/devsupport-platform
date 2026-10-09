# DevSupport 演示资料

本目录包含 DevSupport 平台的演示样例资料，用于展示完整的"上传文档 → 提问 → 获得带来源回答 → 记录案例"排查流程。

## 演示目标

展示 DevSupport 平台如何帮助开发者高效排查技术故障：
1. 上传故障排查文档到知识库
2. 基于文档内容进行 RAG 问答
3. 获得带来源引用的结构化回答
4. 将高质量回答保存为案例，沉淀排查经验

## 前置条件

- Docker 服务已启动（PostgreSQL + Redis）
- 环境变量已配置（`.env` 文件）
- Node.js 18+ / pnpm 已安装

## 演示步骤

### 1. 启动后端（Demo Profile）

```bash
./gradlew :app:bootRun --args='--spring.profiles.active=demo'
```

Demo profile 下 RAG 向量化使用内存模型，零付费即可运行。

### 2. 启动前端

```bash
cd frontend
pnpm run dev
```

访问 http://localhost:5173 打开前端界面。

### 3. 上传样例文档

进入"知识库"页面，将本目录下的故障场景文档上传到知识库：

- `01-db-connection-pool-exhaustion.md` — 数据库连接池耗尽
- `02-redis-oom.md` — Redis 内存溢出
- `03-s3-upload-timeout.md` — S3 上传超时
- `04-ssl-certificate-expired.md` — SSL 证书过期
- `05-jvm-off-heap-oom-kill.md` — JVM 堆外内存泄漏 OOM Kill

等待文档向量化完成（状态变为"已完成"）。

### 4. 创建排查会话并提问

进入"智能问答"页面，选择刚上传的知识库文档，创建新会话。使用下方演示提问模板进行提问。

### 5. 查看带来源的回答

观察回答内容中的来源引用，点击可跳转到原文对应段落，验证 RAG 检索准确性。

### 6. 保存为案例

将满意的回答保存为案例，填写案例标题、标签和适用场景，沉淀到案例库。

### 7. 查看案例库和评测

进入"案例库"页面，查看已保存的案例。进入"评测"页面，可查看 RAG 检索质量评测结果。

## 故障场景清单

| 编号 | 场景 | 文件 | 核心知识点 |
|------|------|------|------------|
| 01 | 数据库连接池耗尽 | [01-db-connection-pool-exhaustion.md](./01-db-connection-pool-exhaustion.md) | HikariCP 指标、慢查询定位、EXPLAIN 分析 |
| 02 | Redis 内存溢出 | [02-redis-oom.md](./02-redis-oom.md) | maxmemory-policy、bigkeys 分析、TTL 管理 |
| 03 | S3 上传超时 | [03-s3-upload-timeout.md](./03-s3-upload-timeout.md) | SDK 超时配置、重试策略、客户端限流 |
| 04 | SSL 证书过期 | [04-ssl-certificate-expired.md](./04-ssl-certificate-expired.md) | certbot 续期、systemd timer、证书监控 |
| 05 | JVM 堆外内存泄漏 | [05-jvm-off-heap-oom-kill.md](./05-jvm-off-heap-oom-kill.md) | DirectByteBuffer、NativeMemoryTracking、Netty ByteBuf 释放 |

## 演示提问模板

以下提问模板用于引导演示过程中的问答交互，每个场景提供 2-3 个由浅入深的问题：

### 场景 01：数据库连接池耗尽

- **Q1**：应用日志出现 `HikariPool - Connection is not available` 是什么原因？怎么排查？
- **Q2**：如何通过 EXPLAIN 分析慢查询的执行计划？需要关注哪些指标？
- **Q3**：HikariCP 连接池应该怎么配置才合理？有哪些关键的监控指标？

### 场景 02：Redis 内存溢出

- **Q1**：Redis 返回 OOM 错误是什么原因？如何确认内存使用情况？
- **Q2**：`redis-cli --bigkeys` 怎么用？如何分析 key 的内存分布？
- **Q3**：`maxmemory-policy` 有哪些策略？生产环境应该选哪种？

### 场景 03：S3 上传超时

- **Q1**：文件上传报 `SocketTimeoutException` 可能有哪些原因？
- **Q2**：AWS S3 SDK 的超时和重试应该怎么配置？
- **Q3**：大文件上传有什么最佳实践？分片上传怎么实现？

### 场景 04：SSL 证书过期

- **Q1**：如何快速检查一个域名的 SSL 证书是否过期？
- **Q2**：Let's Encrypt 证书自动续期失败常见原因有哪些？
- **Q3**：怎么配置证书到期监控告警？

### 场景 05：JVM 堆外内存泄漏

- **Q1**：Java 进程被 OOM Kill 但堆内存没满，是什么原因？
- **Q2**：如何排查堆外内存泄漏？`NativeMemoryTracking` 怎么用？
- **Q3**：Netty 的 ByteBuf 怎么正确释放？有哪些常见的内存泄漏场景？

## 注意事项

- 所有文档内容均为脱敏样例，不包含真实公司信息或凭据
- 演示环境使用 demo profile，LLM 调用走内存模型，不产生费用
- 文档中的服务名、版本号、配置参数均为虚构，仅用于演示排查思路
