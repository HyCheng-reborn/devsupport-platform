package interview.guide.modules.knowledgebase;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.result.Result;
import interview.guide.modules.knowledgebase.model.MessageStatus;
import interview.guide.modules.knowledgebase.model.RagChatDTO.CreateSessionRequest;
import interview.guide.modules.knowledgebase.model.RagChatDTO.SendMessageRequest;
import interview.guide.modules.knowledgebase.model.RagChatDTO.SessionDTO;
import interview.guide.modules.knowledgebase.model.RagChatDTO.SessionDetailDTO;
import interview.guide.modules.knowledgebase.model.RagChatDTO.SessionListItemDTO;
import interview.guide.modules.knowledgebase.model.RagChatDTO.UpdateKnowledgeBasesRequest;
import interview.guide.modules.knowledgebase.model.RagChatDTO.UpdateTitleRequest;
import interview.guide.modules.knowledgebase.model.RetrievalResult;
import interview.guide.modules.knowledgebase.model.SourceReference;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryService;
import interview.guide.modules.knowledgebase.service.RagChatSessionService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RAG 聊天控制器
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "RAG 问答", description = "基于知识库的智能问答会话")
public class RagChatController {

    private final RagChatSessionService sessionService;
    private final KnowledgeBaseQueryService queryService;
    private final ObjectMapper objectMapper;

    /**
     * 创建新会话
     */
    @PostMapping("/api/rag-chat/sessions")
    public Result<SessionDTO> createSession(@Valid @RequestBody CreateSessionRequest request) {
        return Result.success(sessionService.createSession(request));
    }

    /**
     * 获取会话列表
     */
    @GetMapping("/api/rag-chat/sessions")
    public Result<List<SessionListItemDTO>> listSessions() {
        return Result.success(sessionService.listSessions());
    }

    /**
     * 获取会话详情（包含消息历史）
     * GET /api/rag-chat/sessions/{sessionId}
     */
    @GetMapping("/api/rag-chat/sessions/{sessionId}")
    public Result<SessionDetailDTO> getSessionDetail(@PathVariable Long sessionId) {
        return Result.success(sessionService.getSessionDetail(sessionId));
    }

    /**
     * 更新会话标题
     */
    @PutMapping("/api/rag-chat/sessions/{sessionId}/title")
    public Result<Void> updateSessionTitle(
            @PathVariable Long sessionId,
            @Valid @RequestBody UpdateTitleRequest request) {
        sessionService.updateSessionTitle(sessionId, request.title());
        return Result.success(null);
    }

    /**
     * 切换会话置顶状态
     * PUT /api/rag-chat/sessions/{sessionId}/pin
     */
    @PutMapping("/api/rag-chat/sessions/{sessionId}/pin")
    public Result<Void> togglePin(@PathVariable Long sessionId) {
        sessionService.togglePin(sessionId);
        return Result.success(null);
    }

    /**
     * 更新会话知识库
     */
    @PutMapping("/api/rag-chat/sessions/{sessionId}/knowledge-bases")
    public Result<Void> updateSessionKnowledgeBases(
            @PathVariable Long sessionId,
            @Valid @RequestBody UpdateKnowledgeBasesRequest request) {
        sessionService.updateSessionKnowledgeBases(sessionId, request.knowledgeBaseIds());
        return Result.success(null);
    }

    /**
     * 删除会话
     * DELETE /api/rag-chat/sessions/{sessionId}
     */
    @DeleteMapping("/api/rag-chat/sessions/{sessionId}")
    public Result<Void> deleteSession(@PathVariable Long sessionId) {
        sessionService.deleteSession(sessionId);
        return Result.success(null);
    }

    /**
     * 发送消息（流式SSE）
     * 流式响应设计：
     * 1. 先同步保存用户消息和创建 AI 消息占位
     * 2. 流式发出 data chunk；内容流正常完成后，依据「实际检索 + 实际输出」确定最终状态，
     *    先落库回答内容与来源，落库成功后才发出 sources 事件与 done 事件（done 携带最终状态）
     * 3. 落库失败、模型错误或客户端取消均不会向客户端宣告成功；单次护栏避免重复写入
     */
    @PostMapping(value = "/api/rag-chat/sessions/{sessionId}/messages/stream",
                 produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> sendMessageStream(
            @PathVariable Long sessionId,
            @Valid @RequestBody SendMessageRequest request) {

        log.info("收到 RAG 聊天流式请求: sessionId={}, question={}, 线程: {} (虚拟线程: {})",
            sessionId, request.question(), Thread.currentThread(), Thread.currentThread().isVirtual());

        // 1. 准备消息（保存用户消息，创建 AI 消息占位）
        Long messageId = sessionService.prepareStreamMessage(sessionId, request.question());

        // 2. 获取检索结果（包含流式响应和来源文档）
        RetrievalResult result = sessionService.getStreamAnswer(sessionId, request.question());

        // 3. 构建来源列表（来源组装与数据库查询责任归入会话 Service）
        List<SourceReference> sources = sessionService.buildSourceReferences(result.sourceDocuments());
        String sourcesJson = null;
        try {
            sourcesJson = objectMapper.writeValueAsString(sources);
        } catch (Exception e) {
            log.warn("序列化来源信息失败: {}", e.getMessage());
            sourcesJson = "[]";
        }

        // 4. 判断是否有检索到文档（供异常/取消路径决定来源是否保留）
        final boolean hasDocuments = !result.sourceDocuments().isEmpty();

        // 5. 事件顺序保证：data... -> (落库最终状态与来源) -> sources -> done。
        //    done 仅在回答内容与最终状态、来源成功落库后发出；持久化失败时不向客户端宣告成功。
        //    用单次护栏协调「正常完成 / 模型错误 / 客户端取消」三条终止路径，避免重复写入与互相覆盖。
        StringBuilder fullContent = new StringBuilder();
        final String finalSourcesJson = sourcesJson;
        final AtomicBoolean finalized = new AtomicBoolean(false);

        return result.contentStream()
            .doOnNext(fullContent::append)
            .map(chunk -> ServerSentEvent.<String>builder()
                .event("data")
                .data(chunk.replace("\n", "\\n").replace("\r", "\\r"))
                .build())
            .concatWith(Flux.defer(() -> {
                // 依据实际检索与实际输出确定最终状态，而非请求时的检索条数
                MessageStatus finalStatus = queryService.resolveFinalStatus(
                    fullContent.toString(), result.sourceDocuments());
                // 无依据的拒答不保存来源，避免呈现为“有依据”的回答
                String persistedSourcesJson = finalStatus == MessageStatus.NO_RESULTS
                    ? "[]" : finalSourcesJson;

                if (!finalized.compareAndSet(false, true)) {
                    return Flux.empty();
                }
                try {
                    sessionService.completeStreamMessage(
                        messageId, fullContent.toString(), finalStatus, persistedSourcesJson);
                } catch (Exception e) {
                    log.error("RAG 流式回答持久化失败，不向客户端宣告成功: sessionId={}, messageId={}",
                        sessionId, messageId, e);
                    return Flux.error(new BusinessException(
                        ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED, "回答保存失败：" + e.getMessage()));
                }
                // 持久化成功后才宣告结束，done 携带最终状态供前端使用
                return Flux.just(
                    ServerSentEvent.<String>builder()
                        .event("sources")
                        .data(persistedSourcesJson)
                        .build(),
                    ServerSentEvent.<String>builder()
                        .event("done")
                        .data("{\"status\":\"" + finalStatus.name() + "\"}")
                        .build());
            }))
            .doOnError(e -> {
                // 内容流本身出错（模型失败）；持久化失败已在上面拦截，护栏确保不重复写入
                if (finalized.compareAndSet(false, true)) {
                    persistQuietly(messageId, fullContent.toString(), MessageStatus.MODEL_FAILED,
                        hasDocuments ? finalSourcesJson : "[]", sessionId);
                }
                log.error("RAG 聊天流式错误: sessionId={}", sessionId, e);
            })
            .doOnCancel(() -> {
                if (finalized.compareAndSet(false, true)) {
                    persistQuietly(messageId, fullContent.toString(), MessageStatus.CLIENT_DISCONNECTED,
                        hasDocuments ? finalSourcesJson : "[]", sessionId);
                    log.info("RAG 聊天流式取消: sessionId={}, messageId={}", sessionId, messageId);
                }
            });
    }

    /**
     * 终止态（模型失败 / 客户端断开）持久化，异常只记录不外抛，避免在 Reactor 终止回调里抛出。
     */
    private void persistQuietly(Long messageId, String content, MessageStatus status,
                                String sourcesJson, Long sessionId) {
        try {
            sessionService.completeStreamMessage(messageId, content, status, sourcesJson);
        } catch (Exception ex) {
            log.error("RAG 流式终止态持久化失败: sessionId={}, messageId={}, status={}",
                sessionId, messageId, status, ex);
        }
    }
}
