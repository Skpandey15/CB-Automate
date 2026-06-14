package in.techseva.cb.agent.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
public class QdrantRAGService {

    private static final Logger log = LoggerFactory.getLogger(QdrantRAGService.class);
    private static final int TOP_K = 5;
    private static final double MIN_SCORE = 0.70;

    private final VectorStore vectorStore;

    public QdrantRAGService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public List<FixSummary> retrieveSimilarFixes(String cweId, String message) {
        String queryText = String.format("CWE:%s %s", cweId, message);
        try {
            List<Document> docs = vectorStore.similaritySearch(
                SearchRequest.builder()
                    .query(queryText)
                    .topK(TOP_K)
                    .similarityThreshold(MIN_SCORE)
                    .build()
            );
            List<FixSummary> summaries = docs.stream()
                .map(QdrantRAGService::docToSummary)
                .toList();
            log.info("Qdrant RAG returned {} matches for CWE={}", summaries.size(), cweId);
            return summaries;
        } catch (Exception e) {
            log.warn("Qdrant similarity search failed for CWE={}: {}", cweId, e.getMessage());
            return Collections.emptyList();
        }
    }

    private static FixSummary docToSummary(Document doc) {
        Map<String, Object> meta = doc.getMetadata();
        return new FixSummary(
            (String) meta.getOrDefault("cweId", "unknown"),
            (String) meta.getOrDefault("strategy", ""),
            (String) meta.getOrDefault("explanation", ""),
            (String) meta.getOrDefault("patchDiff", "")
        );
    }

    public record FixSummary(String cweId, String strategy, String explanation, String patchDiff) {}
}
