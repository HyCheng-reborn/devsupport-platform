package interview.guide.modules.knowledgebase;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseListItemDTO;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseStatsDTO;
import interview.guide.modules.knowledgebase.model.QueryRequest;
import interview.guide.modules.knowledgebase.model.QueryResponse;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseDeleteService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseListService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseUploadService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 知识库控制器
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "知识库管理", description = "知识库上传、下载、查询、分类与向量化")
public class KnowledgeBaseController {

    /** 流式端点错误兜底：尚未产出内容时，整串以该前缀 + 真实原因发出。 */
    private static final String STREAM_ERROR_PREFIX = "【错误】知识库查询失败：";
    /** 流式端点错误兜底：已输出部分内容后中断时，追加的固定错误标记。 */
    private static final String STREAM_UNAVAILABLE_FALLBACK =
        "\n\n【错误】知识库查询失败：AI服务暂时不可用，请稍后重试。";

    private final KnowledgeBaseUploadService uploadService;
    private final KnowledgeBaseQueryService queryService;
    private final KnowledgeBaseListService listService;
    private final KnowledgeBaseDeleteService deleteService;

    /**
     * 获取所有知识库列表
     */
    @GetMapping("/api/knowledgebase/list")
    public Result<List<KnowledgeBaseListItemDTO>> getAllKnowledgeBases(
            @RequestParam(value = "sortBy", required = false) String sortBy,
            @RequestParam(value = "vectorStatus", required = false) String vectorStatus,
            @RequestParam(value = "service", required = false) String service,
            @RequestParam(value = "environment", required = false) String environment) {
        
        VectorStatus status = null;
        if (vectorStatus != null && !vectorStatus.isBlank()) {
            try {
                status = VectorStatus.valueOf(vectorStatus.toUpperCase());
            } catch (IllegalArgumentException e) {
                return Result.error("无效的向量化状态: " + vectorStatus);
            }
        }
        
        return Result.success(listService.listKnowledgeBases(status, sortBy, service, environment));
    }

    /**
     * 获取知识库详情
     */
    @GetMapping("/api/knowledgebase/{id}")
    public Result<KnowledgeBaseListItemDTO> getKnowledgeBase(@PathVariable Long id) {
        return listService.getKnowledgeBase(id)
                .map(Result::success)
                .orElse(Result.error("知识库不存在"));
    }

    /**
     * 删除知识库
     */
    @DeleteMapping("/api/knowledgebase/{id}")
    public Result<Void> deleteKnowledgeBase(@PathVariable Long id) {
        deleteService.deleteKnowledgeBase(id);
        return Result.success(null);
    }

    /**
     * 基于知识库回答问题（支持多知识库）
     */
    @PostMapping("/api/knowledgebase/query")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 10)
    public Result<QueryResponse> queryKnowledgeBase(@Valid @RequestBody QueryRequest request) {
        return Result.success(queryService.queryKnowledgeBase(request));
    }

    /**
     * 基于知识库回答问题（流式SSE，支持多知识库）
     *
     * <p>该端点返回纯文本 {@code Flux<String>}，没有 RagChat 的 sources/done 事件协议，
     * 因此在此恢复 Phase 1 之前原有的流式错误兜底语义：出错时显式发出【错误】文本，
     * 而不是让错误裸传播导致前端把“被中断的回答”误读成正常完成的回答。
     * 共享的 {@code answerQuestionStream} 仍返回 {@code Flux.error}，不改变 RagChatController 的 SSE 协议。
     * 明确区分两类错误：
     * <ul>
     *   <li>建立 RetrievalResult 时的同步抛错（含尚未产出任何内容即失败）：整条流以带真实原因的【错误】文本兜底。</li>
     *   <li>Flux 订阅后的异步出错（已输出部分内容后中断）：追加显式的【错误】标记，避免残缺回答被伪装成正常回答。</li>
     * </ul>
     */
    @PostMapping(value = "/api/knowledgebase/query/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 5)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 5)
    public Flux<String> queryKnowledgeBaseStream(@Valid @RequestBody QueryRequest request) {
        log.debug("收到知识库流式查询请求: kbIds={}, question={}, 线程: {} (虚拟线程: {})",
            request.knowledgeBaseIds(), request.question(), Thread.currentThread(), Thread.currentThread().isVirtual());

        // 仅返回内容流，来源信息在此端点忽略。
        Flux<String> contentStream;
        try {
            // (A) 建立 RetrievalResult 阶段的同步抛错
            contentStream = queryService
                .answerQuestionStream(request.knowledgeBaseIds(), request.question())
                .contentStream();
        } catch (Exception e) {
            log.error("知识库流式查询建立阶段同步失败: kbIds={}, error={}",
                request.knowledgeBaseIds(), e.getMessage(), e);
            return Flux.just(STREAM_ERROR_PREFIX + resolveErrorReason(e));
        }

        final AtomicBoolean emitted = new AtomicBoolean(false);
        return contentStream
            .doOnNext(chunk -> emitted.set(true))
            // (B) Flux 订阅后的异步出错
            .onErrorResume(e -> {
                log.error("知识库流式查询输出阶段失败: kbIds={}, emitted={}, error={}",
                    request.knowledgeBaseIds(), emitted.get(), e.getMessage(), e);
                // 尚未产出任何内容（含 RetrievalResult 里预置的 Flux.error）：以带真实原因的整串错误文本兜底
                // 已输出部分内容后中断：追加固定错误标记，避免残缺回答被当作正常完成的回答
                return Flux.just(emitted.get()
                    ? STREAM_UNAVAILABLE_FALLBACK
                    : STREAM_ERROR_PREFIX + resolveErrorReason(e));
            });
    }

    private static String resolveErrorReason(Throwable e) {
        String msg = e.getMessage();
        return (msg == null || msg.isBlank()) ? "AI服务暂时不可用，请稍后重试。" : msg;
    }

    // ========== 分类管理 API ==========

    /**
     * 获取所有分类
     */
    @GetMapping("/api/knowledgebase/categories")
    public Result<List<String>> getAllCategories() {
        return Result.success(listService.getAllCategories());
    }

    /**
     * 获取所有服务标签
     */
    @GetMapping("/api/knowledgebase/services")
    public Result<List<String>> getAllServices() {
        return Result.success(listService.getAllServices());
    }

    /**
     * 获取所有环境标签
     */
    @GetMapping("/api/knowledgebase/environments")
    public Result<List<String>> getAllEnvironments() {
        return Result.success(listService.getAllEnvironments());
    }

    /**
     * 根据分类获取知识库列表
     */
    @GetMapping("/api/knowledgebase/category/{category}")
    public Result<List<KnowledgeBaseListItemDTO>> getByCategory(@PathVariable String category) {
        return Result.success(listService.listByCategory(category));
    }

    /**
     * 获取未分类的知识库
     */
    @GetMapping("/api/knowledgebase/uncategorized")
    public Result<List<KnowledgeBaseListItemDTO>> getUncategorized() {
        return Result.success(listService.listByCategory(null));
    }

    /**
     * 更新知识库分类
     */
    @PutMapping("/api/knowledgebase/{id}/category")
    public Result<Void> updateCategory(@PathVariable Long id, @RequestBody Map<String, String> body) {
        listService.updateCategory(id, body.get("category"));
        return Result.success(null);
    }

    /**
     * 更新知识库服务/环境标签
     */
    @PutMapping("/api/knowledgebase/{id}/labels")
    public Result<Void> updateLabels(@PathVariable Long id,
                                     @RequestParam(value = "service", required = false) String service,
                                     @RequestParam(value = "environment", required = false) String environment) {
        listService.updateLabels(id, service, environment);
        return Result.success(null);
    }

    // ========== 上传下载 API ==========

    /**
     * 上传知识库文件
     */
    @PostMapping(value = "/api/knowledgebase/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 3)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 3)
    public Result<Map<String, Object>> uploadKnowledgeBase(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "service", required = false) String service,
            @RequestParam(value = "environment", required = false) String environment) {
        return Result.success(uploadService.uploadKnowledgeBase(file, name, category, service, environment));
    }

    /**
     * 下载知识库文件
     */
    @GetMapping("/api/knowledgebase/{id}/download")
    public ResponseEntity<byte[]> downloadKnowledgeBase(@PathVariable Long id) {
        var entity = listService.getEntityForDownload(id);
        byte[] fileContent = listService.downloadFile(id);

        String filename = entity.getOriginalFilename();
        String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8)
                .replaceAll("\\+", "%20");

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + encodedFilename + "\"; filename*=UTF-8''" + encodedFilename)
                .header(HttpHeaders.CONTENT_TYPE,
                        entity.getContentType() != null ? entity.getContentType()
                                : MediaType.APPLICATION_OCTET_STREAM_VALUE)
                .body(fileContent);
    }

    // ========== 搜索 API ==========

    /**
     * 搜索知识库
     */
    @GetMapping("/api/knowledgebase/search")
    public Result<List<KnowledgeBaseListItemDTO>> search(@RequestParam("keyword") String keyword) {
        return Result.success(listService.search(keyword));
    }

    // ========== 统计 API ==========

    /**
     * 获取知识库统计信息
     */
    @GetMapping("/api/knowledgebase/stats")
    public Result<KnowledgeBaseStatsDTO> getStatistics() {
        return Result.success(listService.getStatistics());
    }

    // ========== 向量化管理 API ==========

    /**
     * 重新向量化知识库（手动重试）
     * 用于向量化失败后的重试
     */
    @PostMapping("/api/knowledgebase/{id}/revectorize")
    @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 2)
    @RateLimit(dimension = RateLimit.Dimension.IP, count = 2)
    public Result<Void> revectorize(@PathVariable Long id) {
        uploadService.revectorize(id);
        return Result.success(null);
    }

}
