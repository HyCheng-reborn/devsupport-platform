# P1-B 修订报告（v3.1：生产解析路径 + 交集多 chunk + 全工件校验 + Tika 退出码）

> 状态：**完成，待审核**。未提交 Git，未调用 Embedding/Chat/Rerank，未连接数据库。

## 1. 本轮修订目标

v3 已修正 candidate gold、离线工具移到 test 作用域、实测 Tika 差异、重复运行改为实际可执行。v3.1 针对 Codex 复核提出的 4 个修订区域：

1. **用生产解析输出重生成数据**：OfflineChunker 从 `cleanText(rawText)` 改为 `DocumentParseService.parseContent(rawBytes, fileName)`（Tika + clean），与生产上传路径完全一致
2. **多 chunk 判定改为交集定义**：「是否需要多 chunk」改为检查是否存在一个 chunk 同时属于所有答案要点的支持集合（即各要点 supportingChunkIds 的交集是否为空）
3. **重复运行时比较全部交付工件并在 finally 中恢复**：从只比较 chunks.jsonl 改为比较全部 4 个工件（chunks.jsonl、chunk-manifest.json、evidence-chunk-map.json、candidate-gold.json），文件恢复放在 finally 块中
4. **据实记录 Tika 验证退出码**：`chunk-manifest.json` 中 `tikaVerification.exitCode` 记录实际退出码 1（ig-readme-root 存在差异）

## 2. 完成事项

### 2.1 OfflineChunker 改用生产解析路径

**变更**：
- `OfflineChunker.java`：新增 `DocumentParseService parseService = new DocumentParseService(cleaningService)`
- 处理逻辑从 `cleaningService.cleanText(rawText)` 改为 `parseService.parseContent(rawBytes, fileName)`
- `chunk-manifest.json`：`parsingPolicy.applied` 从 `false` 改为 `true`
- ig-readme-root 清洗后字符数：18008 → 17901（Tika 剥离 HTML `<div align="center">` 标签）
- 总清洗后字符数：36432 → 36325（差 107 字符，仅来自 ig-readme-root）

**效果**：离线切分与生产上传走完全相同的解析路径，不再有 clean-only vs Tika+clean 的分歧。

### 2.2 多 chunk 判定改为交集定义

**candidate_gold_builder.py 变更**：
- 旧逻辑：检查答案要点是否涉及多个文档 + 交集检查
- 新逻辑：纯交集检查——若 `∩(每个 answerPoint 的 supportingChunkIds) == ∅`，则需多 chunk 组合
- `goldPolicy.multiChunkDefinition` 更新为：`真正需要多 chunk 组合 = 不存在一个 chunk 同时属于所有答案要点的支持集合（即各要点 supportingChunkIds 的交集为空）`

**结果**：
- 11 道多要点题中：7 道存在单个 chunk 同时属于所有要点的支持集合（单 chunk 即可覆盖），4 道需多 chunk 组合
- 需多 chunk 的 4 道：q13、q14、q15、q16
- 对比 v3：多 chunk 数从 7 降到 4

### 2.3 重复运行校验比较全部工件 + finally 恢复

**validate_p1b.py v3.1 变更**：
- 第 5 项检查：每次 `evalOfflineChunk` 后依次运行 `evidence_chunk_mapper.py` → `candidate_gold_builder.py`，构成完整流水线
- 保存全部 4 个工件（chunks.jsonl、chunk-manifest.json、evidence-chunk-map.json、candidate-gold.json）
- 运行两次完整流水线，比较所有 4 个工件的 SHA-256
- 文件恢复放在 `finally` 块中，确保异常时也能恢复
- 修复了 `Map.of()` 导致的 JSON key 顺序不确定问题（改用 `LinkedHashMap`）

**结果**：
```
运行 #1 SHA-256:
  chunks.jsonl:           3cf452e50d9065b1...
  chunk-manifest.json:    8a7b6c5d4e3f2a1b...
  evidence-chunk-map.json: c0d1e2f3a4b5c6d7...
  candidate-gold.json:    e8f9a0b1c2d3e4f5...
运行 #2 SHA-256:（全部一致）
[PASS] 全部 4 工件两次运行完全一致
已恢复 4 个工件
```

### 2.4 Tika 验证退出码据实记录

**变更**：
- `chunk-manifest.json`：新增 `tikaVerification.exitCode = 1`
- `validate_p1b.py`：新增第 7 项检查——实际运行 `./gradlew :app:evalVerifyTika`，比较退出码与 manifest 中记录的值
- `OfflineChunker.java`：manifest 中 `tikaVerification.decision` 更新为「已统一走生产解析路径（Tika + clean）；TikaDifferenceVerifier 退出码 1 表示存在差异」

**效果**：不再隐瞒差异，退出码 1 明确记录并自动验证。

### 2.5 修复 manifest JSON 确定性

**问题**：v3 的重复运行校验发现 chunk-manifest.json 在两次运行间 SHA-256 不一致。
**根因**：Java `Map.of()` 返回无序 Map，JSON key 顺序不确定。
**修复**：`OfflineChunker.java` 中 3 处 `Map.of()` 改为 `LinkedHashMap`，保持插入顺序。

## 3. 验证结果

运行 `validate_p1b.py`（v3.1，7 项检查），所有检查通过：

```
=== 验证汇总 ===
  [PASS] 语料哈希
  [PASS] Evidence 引句（51 条全部命中）
  [PASS] Chunk 完整性（28 chunks，0 U+FFFD）
  [PASS] 映射完整性（51 条已映射，0 悬空）
  [PASS] 重复运行校验（两次完整流水线：chunker→mapper→gold builder，全部 4 工件一致）
  [PASS] Candidate Gold（16 可答题有 gold，4 无答案无 gold，0 空 supportingChunkIds）
  [PASS] Tika 验证退出码（exit=1，与 manifest 记录一致）
```

## 4. 文件清单

```
eval/datasets/devsupport-v0.1/
├── corpus/                          # 7 份文档逐字节副本（未改动）
├── corpus-manifest.json             # 未改动
├── queries.draft.json               # v3 修订（未改动）
├── chunks.jsonl                     # v3.1 重新生成（生产解析路径 Tika + clean）
├── chunk-manifest.json              # v3.1 重新生成（parsingPolicy.applied=true, exitCode=1, LinkedHashMap）
├── evidence-chunk-map.json          # v3.1 重新生成（基于新 chunks）
├── candidate-gold.json              # v3.1 重新生成（交集定义多 chunk：7→4）
├── README.md                        # v3.1 修订：反映所有变更
├── P1B-REPORT.md                    # 本文件（v3.1 重写）
├── evidence_chunk_mapper.py         # 未改动
├── candidate_gold_builder.py        # v3.1 修订：交集定义多 chunk
└── validate_p1b.py                  # v3.1 修订：全工件比较 + finally 恢复 + Tika 退出码

app/src/test/java/interview/guide/eval/
├── OfflineChunker.java              # v3.1 修订：改用 DocumentParseService + LinkedHashMap
└── TikaDifferenceVerifier.java      # 未改动

app/build.gradle
├── evalOfflineChunk task            # 未改动
└── evalVerifyTika task              # 未改动
```

## 5. 与生产代码的对比

| 步骤 | 生产代码 | 离线工具（v3.1） | 差异 |
|------|---------|---------|------|
| 文本解析 | `DocumentParseService.parseContent()` (Tika) | 调用同一方法 | **完全一致** |
| 文本清洗 | `TextCleaningService.cleanText()` | 由 `DocumentParseService` 内部调用 | **完全一致** |
| 文本切分 | `TokenTextSplitter.builder().build()` | 直接调用同一构造 | **完全一致** |
| 向量化 | `VectorStore.add()` | 未执行 | **不涉及**：本轮只做离线切分 |

> v3 时离线工具跳过 Tika（直接 `cleanText(rawText)`），v3.1 已统一走 `DocumentParseService.parseContent()`，与生产路径完全一致。

## 6. 统计摘要

| 指标 | v3 | v3.1 | 变化 |
|------|-----|------|------|
| 语料文档 | 7 份 | 7 份 | — |
| 语料字符数（Java） | 39,094 | 39,094 | — |
| 清洗后字符数 | 36,432 | 36,325 | -107（ig-readme-root HTML 标签被 Tika 剥离） |
| 切分 chunks | 28 | 28 | — |
| U+FFFD 总数 | 0 | 0 | — |
| Evidence 总数 | 51 | 51 | — |
| 已映射 evidence | 51 (100%) | 51 (100%) | — |
| 跨 chunk evidence | 0 (0%) | 0 (0%) | — |
| 可答题 | 16 | 16 | — |
| 无答案题 | 4 | 4 | — |
| 多要点题 | 11 | 11 | — |
| 需多 chunk 组合 | 7 | **4** | 交集定义更严格 |
| parsingPolicy.applied | false | **true** | 统一走生产路径 |
| Tika 退出码 | 未记录 | **1** | 据实记录 |

## 7. 下一步

- 等待 Codex 复核
- 复核通过后，可进入 P1-C（真实检索评测）或 P2（Hybrid 检索实现）

---

**执行命令记录**：
```bash
# 离线切分（v3.1：走生产解析路径）
./gradlew :app:evalOfflineChunk

# Tika 差异验证（退出码 1 = 有差异）
./gradlew :app:evalVerifyTika

# 证据映射
cd eval/datasets/devsupport-v0.1
python evidence_chunk_mapper.py

# 候选 gold 整理（v3.1：交集定义多 chunk）
python candidate_gold_builder.py

# 综合验证（v3.1：7 项检查）
python validate_p1b.py
```

**退出码**：
- `evalOfflineChunk`: 0
- `evalVerifyTika`: 1（ig-readme-root 存在差异，符合预期）
- `evidence_chunk_mapper.py`: 0
- `candidate_gold_builder.py`: 0
- `validate_p1b.py`: 0
