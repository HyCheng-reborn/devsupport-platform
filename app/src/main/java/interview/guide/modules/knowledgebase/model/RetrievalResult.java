package interview.guide.modules.knowledgebase.model;

import org.springframework.ai.document.Document;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * RAG 问答结果契约：将流式内容流与检索来源文档绑定传递。
 */
public record RetrievalResult(
    Flux<String> contentStream,
    List<Document> sourceDocuments
) {
    public RetrievalResult {
        if (sourceDocuments == null) {
            sourceDocuments = List.of();
        }
    }
}
