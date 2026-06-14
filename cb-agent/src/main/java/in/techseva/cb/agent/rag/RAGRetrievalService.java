package in.techseva.cb.agent.rag;

import in.techseva.cb.core.domain.Vulnerability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Public facade over QdrantRAGService. Kept for API compatibility; agentic callers
 * use QdrantRAGService directly via FixAgentTools.
 */
@Service
public class RAGRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RAGRetrievalService.class);

    private final QdrantRAGService qdrantRAGService;

    public RAGRetrievalService(QdrantRAGService qdrantRAGService) {
        this.qdrantRAGService = qdrantRAGService;
    }

    @Cacheable(value = "ragResults", key = "#vuln.sonarIssueKey()")
    public List<QdrantRAGService.FixSummary> retrieveSimilarFixes(Vulnerability vuln) {
        String message = String.format("severity:%s rule:%s message:%s",
            vuln.severity(), vuln.ruleKey(), vuln.message());
        return qdrantRAGService.retrieveSimilarFixes(vuln.cweId(), message);
    }
}
