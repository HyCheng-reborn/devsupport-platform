# 改动报告（增量记录）

> 本文件是「每次完成改动后」的简洁增量报告，按时间倒序追加。
> 与根目录 `PROJECT_PROGRESS.md`（跨对话任务进度事实源）互补：本文件只记「改了什么、为什么、验证结果与未验证项」，不复述完整方案。
> 说明：`/docs` 目录被 `.gitignore` 忽略，故本报告放在仓库根目录以确保被跟踪。
> 推送目标：远程 `devsupport`（`HyCheng-reborn/devsupport-platform`）；本地目录 `interview-guide`。

---

## 2026-10-01 — RagChatController 来源组装责任下沉到 Service（Controller 不再持有 Repository）

**基线**：`bab3a62`（本任务前 HEAD）
**范围**：只把 `buildSourceReferences` 的数据库查询与来源组装从 `KnowledgeBaseQueryService` 移入已持有 `KnowledgeBaseRepository` 的 `RagChatSessionService`，让 `RagChatController` 不再直接注入 Repository。未改数据库 schema、未改其他端点、未改来源字段/排序/SSE 序列化/状态持久化契约、未调外部 API / 数据库。

### 改了什么
- `RagChatSessionService`：新增 `buildSourceReferences(List<Document> docs)`，复用自身 `knowledgeBaseRepository` 字段批量查 `originalFilename` 组装 `SourceReference`；来源顺序、字段含义、截断与「未知文档」回退与原实现一致。
- `KnowledgeBaseQueryService`：删除 `buildSourceReferences(docs, kbRepo)` 及随之不再使用的 `KnowledgeBaseEntity`/`SourceReference`/`KnowledgeBaseRepository` import。
- `RagChatController`：移除 `KnowledgeBaseRepository` 字段与 import，改调 `sessionService.buildSourceReferences(result.sourceDocuments())`；`resolveFinalStatus` 仍走 `queryService`，SSE 事件顺序与持久化不变。
- 测试：`RagChatControllerTest` 构造函数去掉 repository 参数、来源打桩改为 `sessionService.buildSourceReferences(anyList())`；`KnowledgeBaseQueryServiceTest` 删除已迁移的来源用例与无用 import；来源提取用例迁到 `RagChatSessionServiceTest`（新增 4 条，含未知 kb 回退）。

### 为什么
- 原设计中 `buildSourceReferences` 放在 `KnowledgeBaseQueryService` 却需要 `KnowledgeBaseRepository`，导致 Controller 仅为把 Repository 传进去而直接持有 Repository，违反 Controller 不碰数据访问的分层职责。`RagChatSessionService` 本就持有该 Repository 且已是 Controller 协作者，是来源组装的自然归属。

### 验证
- `./gradlew :app:compileJava :app:compileTestJava --no-daemon` → exit 0
- `./gradlew :app:test --tests '...RagChatControllerTest' --tests '...RagChatSessionServiceTest' --tests '...KnowledgeBaseQueryServiceTest' --tests '...SourceReferenceTest' --no-daemon --rerun` → exit 0（四套件 failures=0 errors=0，含迁移后的来源用例）

### 尚未验证（真实环境）
- 未连真实 LLM/数据库跑端到端 SSE；来源组装仅由单元测试与 Controller 打桩覆盖，真实 `findAllById` 命中/缺行路径未做集成验证。

---

## 2026-10-01 — KnowledgeBaseController 流式错误兜底语义定点恢复

**基线**：`376ef5a`（本任务前 HEAD）
**范围**：只恢复 `queryKnowledgeBaseStream()`（`/api/knowledgebase/query/stream`，纯文本 `Flux<String>`）的流式错误兜底；不改共享 Service、不改 RagChatController 的 SSE 协议、不调外部 API/数据库。

### 改了什么
- `KnowledgeBaseController.queryKnowledgeBaseStream`：新增 (A) 建立 RetrievalResult 时同步抛错的 try/catch 兜底，(B) Flux 订阅后异步出错的 `.onErrorResume` 兜底；用 `AtomicBoolean emitted` 区分“未输出即失败”（整串【错误】+真实原因）与“已输出部分后中断”（追加固定【错误】标记）；新增常量 `STREAM_ERROR_PREFIX`/`STREAM_UNAVAILABLE_FALLBACK` 与 `resolveErrorReason`。
- 新增 `KnowledgeBaseControllerStreamTest`（7 条）。

### 为什么（回归）
- Phase 1（`da1c744`）为支持 RagChatController 把共享 `answerQuestionStream` 的两处降级改为 `Flux.error(e)`；而本端点直接返回 `.contentStream()` 且无错误处理，导致失败裸传播、前端把被中断的回答当正常完成（伪装 + 丢失【错误】兜底）。端点级修复不影响依赖 `Flux.error` 的 RagChatController。

### 验证
- `./gradlew :app:compileJava :app:compileTestJava --no-daemon` → exit 0
- `./gradlew :app:test --tests '...KnowledgeBaseControllerStreamTest' --tests '...RagChatControllerTest' --tests '...KnowledgeBaseQueryServiceTest' --no-daemon --rerun` → exit 0（新类 7、RagChatControllerTest / QueryServiceTest 无回归）

### 尚未验证（真实环境）
- 未连真实后端跑端到端 SSE；(A) 同步抛错分支在当前服务形态下几乎不触发（Service 内部已 catch 为 `Flux.error`），主要真实路径为 (B) 中 `emitted=false` 分支。

---

## 2026-10-01 — RAG 流式回答前端状态处理定点修复

**基线**：`b85890f`（本任务前 HEAD）
**范围**：只修前端流式回答的最终状态与来源展示，消费上一任务后端已带出的 `done.status` 契约；不改后端、不调外部 API / 数据库、不动无关页面。

### 改了什么
- `frontend/src/api/ragStreamStatus.ts`（新增纯逻辑模块）：`parseDoneStatus`（解析 done data，缺失/非法/未知→`undefined`）、`resolveFinalStatus`（无确认回退 `MODEL_FAILED`，绝不当 COMPLETED）、`sourcesDisplayMode`（COMPLETED→grounded / NO_RESULTS→none / MODEL_FAILED·CLIENT_DISCONNECTED→degraded / 生成中→pending）、`selectSourcesForStatus`（NO_RESULTS 一律清空来源）。
- `frontend/src/api/stream.ts`：`onDone` 改为 `(status?: string) => void`，`done` 分支把 data 透传。
- `frontend/src/api/ragChat.ts`：`MessageStatus` 收敛到纯模块再导出；`sendMessageStream` 用 `finalized` 单次护栏协调 onDone（解析服务端状态）/ onComplete（流结束却无 done→`MODEL_FAILED`）/ onError；`onComplete` 回传最终状态。
- `frontend/src/pages/KnowledgeBaseQueryPage.tsx`：完成回调按服务端状态写定、用 `selectSourcesForStatus` 定来源；渲染用 `sourcesDisplayMode` 门控（仅 grounded 作“引用来源”，degraded 降为“参考文档·本条未成功生成仅供参考”，none/pending 不渲染）；非 COMPLETED 显状态徽标。刷新恢复沿用 `m.status` + `m.sourcesJson` 经同一渲染管线。
- `frontend/src/api/ragStreamStatus.test.ts`（新增 5 条）、`package.json` 新增 `test:rag-stream`、`.github/workflows/ci.yml` frontend 任务加入该脚本。

### 为什么（根因）
- 原 `stream.ts` 的 done 分支丢弃 data；页面在 onDone 里无条件写 `COMPLETED`，不看服务端确认状态。
- “无 done 确认”与 onError 未收敛为失败态；有文档却拒答（NO_RESULTS）时仍带来源呈现为有依据的回答。

### 验证
- `node --test src/api/ragStreamStatus.test.ts` → 5 pass / 0 fail
- `pnpm run build`（`tsc && vite build`）→ exit 0（仅既有 CSS `:where()` 告警与 chunk 体积告警，与改动无关）

### 尚未验证（真实环境）
- 未连真实后端跑过一次端到端 SSE；done/error/断流实际时序与无 done 回退路径仅由单元级纯函数 + 代码走查保证。
- 刷新恢复依赖后端确已持久化 `status` / `sources_json`，本轮未对真实 DB 做端到端核对。

---

## 2026-10-01 — RAG 流式回答服务端最终状态与事件顺序定点修复

**提交**：`88b4017`（已推送 `devsupport/master`，基线 `67baebd`）
**范围**：只修 Phase 1 流式回答（`RagChatController.sendMessageStream`）的服务端最终状态与事件顺序；未做无关重构、未改生产数据、未调付费 API / Docker / 真实 L1。

### 改了什么
- `KnowledgeBaseQueryService.java`：新增 `resolveFinalStatus(实际输出, 检索文档)` —— 空检索 / 输出为空 / 命中 `isNoResultLike` 拒答 → `NO_RESULTS`；有文档且实质性回答 → `COMPLETED`。判定收敛到 Service 层。
- `RagChatController.java`：链重构为 `data… → Flux.defer{ 判最终状态 → 先落库 → 成功才发 sources + done }`；落库失败返回 `Flux.error(BusinessException)` 且**不发 done**；`AtomicBoolean` 单次护栏协调 成功 / `MODEL_FAILED` / `CLIENT_DISCONNECTED` 三条终止路径；`NO_RESULTS` 一律以 `[]` 存来源；`done` 事件 data 补 `{"status":"..."}`。
- `RagChatControllerTest.java`（新增 5 条）、`KnowledgeBaseQueryServiceTest.java`（新增 `resolveFinalStatus` 4 条）。

### 为什么（根因）
- 原实现 `done` 先于持久化发出，落库失败仍向客户端宣告成功。
- 原 `status` 在请求时按检索条数预定，不看模型实际输出 —— 检索命中却输出拒答文本时会被误存为有依据的 COMPLETED。
- 原错误 / 取消 / 落库异常三条终止回调无护栏，可能重复写入并互相覆盖。

### 验证
- `./gradlew :app:compileJava :app:compileTestJava --no-daemon` → exit 0
- `./gradlew :app:test --tests 'interview.guide.modules.knowledgebase.RagChatControllerTest' --tests 'interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryServiceTest' --no-daemon --rerun` → exit 0（RagChatControllerTest 5、resolveFinalStatus 4，全绿）
- 环境：PowerShell，`$env:GRADLE_USER_HOME="C:\temp\gradle-tmp"`

### 尚未验证（真实环境）
- 未连真实 LLM / 数据库跑过一次端到端 SSE 顺序与落库；`@Transactional` 在真实 DB 的回滚表现未实测。
- 真实模型多样拒答措辞能否全部落入 `isNoResultLike`，未经样本验证。
- 前端据 `done.status` 区分展示属下一项任务，本任务仅保证后端已带出该字段。

---
