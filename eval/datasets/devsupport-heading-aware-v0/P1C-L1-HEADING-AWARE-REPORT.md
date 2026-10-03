# P1-C L1 — heading-aware 候选数据集真实向量对照报告

> 单次真实向量对照评测的**冻结证据总结**。本报告与配套机器工件全部派生自唯一成功来源，
> 不重新计算向量、不修改原始指标、不含任何凭据。

## 1. 范围与来源

- **来源报告（唯一真值）**：`build/eval-runs/heading-aware-success-26e7f099/p1c-l1-report.CANDIDATE-USABLE.json`
  - `evalRunId`：`26e7f099-22d2-42cd-a4e3-8627f4894d99`
  - `executionMode`：真实 Embedding 向量检索（无 LLM）
- **数据集**：`eval/datasets/devsupport-heading-aware-v0/`（version `heading-aware-v0`）
- **对照对象**：冻结 baseline 数据集 `eval/datasets/devsupport-v0.1`（28 chunks），其汇总数字记录于 `CHANGES.md` / `PROJECT_PROGRESS.md`
- **Embedding 配置**：`dashscope` `text-embedding-v3`，1024 维，COSINE 距离，HNSW 索引，`topK=10`，`reportingKValues=[1,3,5,10]`
- **运行边界**：独立评测库（`interview-eval-postgres` / `interview_guide_eval` / `eval_runner`，marker `p1c-eval-isolated` 已核验）；预算硬上限 50；只调用 Embedding，不调用 LLM；未重跑 baseline。

## 2. 公平性说明

候选与 baseline 使用**相同的生产解析与文本清洗链路**后再切分，唯一变量为切分策略：

- `ig-readme-root`：先经 `DocumentParseService.parseContent`(Tika) + `TextCleaningService.cleanText` 得到与 baseline **逐字节一致**的清洗输入（`cleanedSha256 = f47b6a97…5aa6d1`），再按 heading-aware 规则重切为 33 个 chunk（`configId=heading-aware-v1`）。
- 其余文档（16 个 chunk）：与 baseline **逐字节相同、稳定 ID 相同**地继承，未重切。
- 稳定 ID 规范：`{docId}__{configId}__{seq:04d}__{sha256(text):12}`。

## 3. 冻结 baseline（L1，20 题数据集，同一 Embedding 配置）

| K | Hit@K | MRR@K | APC@K | FullCoverage@K |
|---|-------|-------|-------|----------------|
| 5 | 0.9375 | 0.7781 | 0.8813 | 0.8125 (13/16) |
| 10 | 1.0000 | 0.7885 | 1.0000 | 1.0000 (16/16) |

- chunks：28；入库批次：3；预算 attempts：23。

## 4. heading-aware candidate（本轮实测）

宏平均分母 = 16 道可答题；micro = 该 K 覆盖要点数 / 38。

| K | Hit@K | MRR@K | APC@K | FullCoverage@K | coveredPoints / 38 (micro) |
|---|-------|-------|-------|----------------|----------------------------|
| 1 | 0.8125 | 0.8125 | 0.7281 | 0.5625 (9/16) | 28/38 = 0.7368 |
| 3 | 0.9375 | 0.8542 | 0.9125 | 0.8750 (14/16) | 35/38 = 0.9211 |
| 5 | 0.9375 | 0.8542 | 0.9125 | 0.8750 (14/16) | 35/38 = 0.9211 |
| 10 | 1.0000 | 0.8631 | 1.0000 | 1.0000 (16/16) | 38/38 = 1.0000 |

- chunks：49；入库批次：5；预算 attempts：25（`WITHIN_LIMIT`，0 失败）；`roundAvailability = USABLE`；`cleanupStatus = CLEANED`（写入 49 → 清理后 0）；`ingestionVerification = PASS`。

## 5. candidate − baseline 差值（仅汇总层可比）

| 指标 | baseline | candidate | Δ |
|------|----------|-----------|---|
| Hit@5 | 0.9375 | 0.9375 | 0 |
| MRR@5 | 0.7781 | 0.8542 | **+0.0761** |
| APC@5 | 0.8813 | 0.9125 | **+0.0312** |
| FullCoverage@5 | 0.8125 (13/16) | 0.8750 (14/16) | **+0.0625（净 +1 题）** |
| Hit@10 | 1.0 | 1.0 | 0 |
| MRR@10 | 0.7885 | 0.8631 | **+0.0746** |
| APC@10 | 1.0 | 1.0 | 0 |
| FullCoverage@10 | 1.0 | 1.0 | 0 |

## 6. 逐题概览（候选，见 `p1c-l1-per-query.csv` / `p1c-l1-per-query.json`）

- **FullCoverage@5 未达标的两题**：`q11`、`q14`。
  - `q11`：Hit@5 = 0（首个相关 chunk 到 rank>5 才出现，@10 命中并全覆盖）。
  - `q14`：Hit@5 = 1（有支撑进入 Top-5），但 5 个要点中 @5 仅覆盖 3 个，@10 才全覆盖。
- FullCoverage@1：9/16；FullCoverage@10：16/16。
- 4 道 NO_ANSWER（`q17`–`q20`）仅保留检索诊断，不参与宏平均，逐题表指标列留空。

## 7. 结论口径（严格限定）

- 可以表述为：**在本轮冻结的 20 题 L1 数据集、同一 Embedding 配置的一次真实对照中，heading-aware 候选的汇总指标优于冻结 baseline。**
- **不**据此断言生产检索普遍提升，**不**宣称统计显著（单轮、样本 16 题）。
- baseline 的**逐题 JSON 已不可得**（其 build 报告被后续运行覆盖，仅汇总数字被文档固化），因此**不能声称已完成逐题配对比较**；上文差值仅为汇总层对比。
- 成本代价明确：chunks 28 → 49、入库批次 3 → 5、预算 attempts 23 → 25（均 ≤ 硬上限 50）。

## 8. Q14 与理论上限（更正记录）

- Q14 的全部支撑 chunk（含位于**未重切**文档中的支撑）仍在候选 49-chunk 集合内，闭环 0 缺失。
- 未重切 ≠ 不可覆盖：重切 README 会改变候选池与排名竞争，未重切文档中的支撑 chunk 仍可能进入 Top-K。
- 因此 Q14 的 **FC@5 理论上限更正为 1.0**，不得写成 15/16 封顶；本轮实测 Q14 在 @5 未全覆盖、在 @10 全覆盖。

## 9. 配套工件与哈希（候选目录，随仓库跟踪）

- `p1c-l1-report.json`：成功报告的**脱敏冻结副本**（仅含数据库名/用户名/模型名/哈希/evalRunId，无凭据）。
- `p1c-l1-per-query.json`：权威机器可读逐题表（20 题，含 `metricsByK`@1/3/5/10 与 Top-10 `rank/evalChunkId/score`），直接派生自成功 JSON。
- `p1c-l1-per-query.csv`：逐题指标宽表（NO_ANSWER 指标列留空）。
- 候选输入四工件 normalized-LF SHA-256（冻结清单 `p1c-frozen-artifacts.json`，逐项匹配）：
  - `chunks.jsonl = 80deabfc8b419fcd…`
  - `candidate-gold.json = cbaadbe1d18320be…`
  - `corpus-manifest.json = a7c1a0b1137d7c96…`
  - `chunk-manifest.json = 59afe46b750fb5bd…`

## 10. 复现与验证命令（本轮不重跑付费评测）

```powershell
# 离线：候选数据集预检（49 chunks / 16-4-38 / 金标闭环 / 冻结哈希）
.\gradlew.bat :app:test --no-daemon --tests "interview.guide.eval.P1cOfflineUnitTest" --rerun-tasks

# 真实对照（需 .env.eval 凭据与独立评测库，付费 Embedding；本仓库本轮未执行）
.\gradlew.bat :app:evalP1cReal "-Peval.p1c.realApi=true" `
  "-Peval.datasetDir=eval/datasets/devsupport-heading-aware-v0" `
  "-Peval.expected.chunkCount=49" "-Peval.expected.answerPoints=38" `
  "-Peval.expected.queryCount=20" "-Peval.expected.answerableCount=16" `
  "-Peval.expected.noAnswerCount=4"
```
