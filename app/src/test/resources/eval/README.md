# P1-A 离线检索评测核心（test 作用域）

本目录下的代码与夹具**仅用于验证检索指标计算逻辑**，全部数据为**虚构夹具**，
**不是真实 RAG 检索质量基线**，不连接数据库 / Redis / S3 / 网络 / 模型。

## 组成

- `interview.guide.eval.RetrievalMetrics`：指标计算核心（Hit@K / Recall@K / MRR@K）。
- `EvalQuery` / `Answerability` / `RetrievalHit` / `QueryJudgement`：输入数据结构。
- `QueryEvaluation` / `EvaluationReport`：输出结构（报告带 `executionMode=fixture`、`scope=metric_validation`、免责声明）。
- `EvaluationReportJson`：复用项目现有 Jackson 3（`tools.jackson.databind`）做夹具读取与报告 JSON 导出，未引入新依赖。
- `eval/example-query-set.json`：固定虚构夹具。

## 指标口径

- **Hit@K**：前 K 条是否至少命中一条相关片段（1.0 / 0.0）。
- **Recall@K**：前 K 条命中的**不同**相关片段数 / 全部标注相关片段数。
- **MRR@K**：前 K 条中第一条相关片段排名的倒数；前 K 条无命中为 0。
- **宏平均**：仅对 `ANSWERABLE` 且带非空 gold 的查询求平均；无可答题时宏平均为 `null`（不除零、不回填满分）。
- 命中判定**只按 `chunkId`**；来源知识库 `kbId` 相同不算片段命中。`score` 可空，排名指标不依赖 score。
- `NO_ANSWER` / `UNANNOTATED`（gold 为空）被排除，分别计数（`noAnswerQueries` / `unannotatedQueries` / `excludedQueries`）。
- 无效输入（K≤0、queryId 重复、结果含重复 chunkId、标注与 gold 不一致、gold 重复）一律 `IllegalArgumentException` 拒绝。
- 检索结果为空或少于 K 条属正常输入，按实际条数计算。
- **夹具 `k` 读取契约**：`EvaluationReportJson.readFixture` 先解析 JSON 树校验 `k` 的节点类型与范围，再映射夹具。`k` 必须是 JSON 整数且 `1 <= k <= Integer.MAX_VALUE`；小数（含 `1.9`、`1.0`）、字符串、`null`、缺失、布尔、超出 int 范围及非正值一律在读取阶段 `IllegalArgumentException` 拒绝，不做截断 / 四舍五入 / 字符串转整数 / 默认值补齐，读取失败时不产出任何夹具或报告。

## 运行

这些是普通离线单元测试，随 `:app:test` 执行，无需任何开关或付费调用：

```
./gradlew :app:test --tests "interview.guide.eval.*"
```

---

## 后续真实评测设计记录（仅记录，本轮不实现）

1. **分层报告，分开呈现，不得混称：**
   - **L1 向量检索组件基线**：直接调用 `KnowledgeBaseVectorService.similaritySearch(...)`，只覆盖单次向量检索（含其 fallback）。**这不是完整原系统基线**。
   - **L2 原系统查询策略基线**：包含历史加载、查询改写、动态 topK/阈值、改写→原问题顺序回退。接入方式应走**真实生产代码路径**（黑盒回放 `POST /api/rag-chat/sessions/{id}/messages/stream`，或对 `KnowledgeBaseVectorService` 注入可观测装饰器采集轨迹），**不得复制生产私有逻辑后冒充真实调用链**。
2. **付费开关必须严格解析 `true`：** 仅当显式值为 `true` 时启用真实评测；缺失、空串、`false`、其它任意值一律**禁用**（严格布尔解析，不宽松 truthy）。
3. **Gradle 项目属性需显式传给测试 JVM：** 通过 `systemProperty`/`-D` 把开关传入 forked 测试进程；`@Tag` 本身不阻止普通 `test` 执行，必须在 Gradle 侧对常规 `test` 任务 `excludeTags`，并用独立任务（`onlyIf` 绑定开关属性、不挂 `check`）承载真实评测。需以"无 API Key 环境下跑普通 `:app:test` 必不触发付费调用"作为回归护栏。
4. **真实语料准备与只读评测分开执行：** 语料写入（夹具准备，含写操作）与检索评测（只读）分属不同阶段/任务，使用**独立评测数据范围**（专用 kbId 段或独立 schema），评测后清理，避免污染业务数据。
5. **冻结信息**须包含：HEAD + 相关未提交 diff 摘要/哈希、语料内容与 chunk 清单及其哈希（非仅 kbId）、运行期**实际生效**的 Embedding 配置（非仅 YAML 默认值）、查询集/检索参数/索引配置。现有向量数据的写入来源若无法确认，标注"未知"，不声称已绑定。
6. **回归判定：** 确定性层（本目录的 fixture 单测）要求逐字段完全相等；真实 API/向量检索层改用指标 + 排名变化 + 容差（Recall@k/MRR/nDCG 阈值、Top-k 命中集合 Jaccard、score 差 ≤ ε），不要求 score 序列完全相等。
