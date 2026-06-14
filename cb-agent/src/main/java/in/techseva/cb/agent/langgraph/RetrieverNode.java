package in.techseva.cb.agent.langgraph;

import in.techseva.cb.agent.service.HybridSearchService;
import in.techseva.cb.core.domain.Vulnerability;
import org.bsc.langgraph4j.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Second node: retrieves similar fixes via hybrid search (Qdrant + Elasticsearch BM25).
 */
@Component
public class RetrieverNode implements NodeAction<AgentWorkflowState> {

    private static final Logger log = LoggerFactory.getLogger(RetrieverNode.class);

    private final HybridSearchService hybridSearch;

    public RetrieverNode(HybridSearchService hybridSearch) {
        this.hybridSearch = hybridSearch;
    }

    @Override
    public Map<String, Object> apply(AgentWorkflowState state) throws Exception {
        Vulnerability vuln = state.vulnerability()
                .orElseThrow(() -> new IllegalStateException("No vulnerability in state"));

        String query = vuln.severity() + " " + vuln.message();
        List<Document> docs = hybridSearch.search(vuln.cweId(), query);
        List<String> snippets = docs.stream()
                .map(Document::getText)
                .map(t -> t.substring(0, Math.min(500, t.length())))
                .toList();

        log.info("RetrieverNode: found {} context docs for cwe={}", snippets.size(), vuln.cweId());
        return Map.of(AgentWorkflowState.CONTEXT_DOCS, snippets);
    }
}
