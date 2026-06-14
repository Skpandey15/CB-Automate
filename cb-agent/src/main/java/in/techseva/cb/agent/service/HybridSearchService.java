package in.techseva.cb.agent.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import in.techseva.cb.agent.rag.QdrantRAGService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Hybrid search combining Qdrant semantic (vector) search with Elasticsearch BM25 lexical search.
 * Results are merged via Reciprocal Rank Fusion (RRF).
 */
@Service
public class HybridSearchService {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);
    private static final String ES_INDEX = "cb_fixes";
    private static final int TOP_K = 5;
    private static final double RRF_K = 60.0;

    private final QdrantRAGService qdrantRag;
    private final ElasticsearchClient esClient;

    public HybridSearchService(QdrantRAGService qdrantRag, ElasticsearchClient esClient) {
        this.qdrantRag = qdrantRag;
        this.esClient = esClient;
    }

    public List<Document> search(String cweId, String query) {
        List<ScoredDoc> qdrantResults = qdrantSearch(cweId, query);
        List<ScoredDoc> esResults = bm25Search(cweId, query);
        return rrfMerge(qdrantResults, esResults, TOP_K);
    }

    private List<ScoredDoc> qdrantSearch(String cweId, String query) {
        try {
            List<QdrantRAGService.FixSummary> summaries = qdrantRag.retrieveSimilarFixes(cweId, query);
            var result = new ArrayList<ScoredDoc>(summaries.size());
            for (int i = 0; i < summaries.size(); i++) {
                QdrantRAGService.FixSummary s = summaries.get(i);
                Document doc = new Document(s.explanation() + "\n" + s.patchDiff(),
                        Map.of("cweId", s.cweId(), "strategy", s.strategy()));
                result.add(new ScoredDoc(doc, i + 1));
            }
            return result;
        } catch (Exception e) {
            log.warn("Qdrant search failed for cwe={}: {}", cweId, e.getMessage());
            return List.of();
        }
    }

    private List<ScoredDoc> bm25Search(String cweId, String query) {
        try {
            SearchResponse<Map> response = esClient.search(s -> s
                    .index(ES_INDEX)
                    .query(q -> q
                        .bool(b -> b
                            .must(m -> m.match(mm -> mm.field("cweId").query(cweId)))
                            .should(sh -> sh.match(mm -> mm.field("explanation").query(query)))
                            .should(sh -> sh.match(mm -> mm.field("strategy").query(query)))
                        )
                    )
                    .size(TOP_K),
                Map.class
            );
            List<ScoredDoc> result = new ArrayList<>();
            List<Hit<Map>> hits = response.hits().hits();
            for (int i = 0; i < hits.size(); i++) {
                Hit<Map> hit = hits.get(i);
                Map<String, Object> src = hit.source() != null ? hit.source() : Map.of();
                Document doc = new Document(
                    String.valueOf(src.getOrDefault("explanation", "")),
                    src
                );
                result.add(new ScoredDoc(doc, i + 1));
            }
            return result;
        } catch (Exception e) {
            log.warn("Elasticsearch BM25 search failed for cwe={}: {}", cweId, e.getMessage());
            return List.of();
        }
    }

    private List<Document> rrfMerge(List<ScoredDoc> qdrant, List<ScoredDoc> es, int topN) {
        Map<String, Double> scores = new java.util.HashMap<>();
        Map<String, Document> docs = new java.util.HashMap<>();

        for (ScoredDoc sd : qdrant) {
            String key = docKey(sd.doc);
            scores.merge(key, 1.0 / (RRF_K + sd.rank), Double::sum);
            docs.put(key, sd.doc);
        }
        for (ScoredDoc sd : es) {
            String key = docKey(sd.doc);
            scores.merge(key, 1.0 / (RRF_K + sd.rank), Double::sum);
            docs.put(key, sd.doc);
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
                .limit(topN)
                .map(e -> docs.get(e.getKey()))
                .toList();
    }

    private String docKey(Document doc) {
        return doc.getId() != null ? doc.getId() : doc.getText().substring(0, Math.min(64, doc.getText().length()));
    }

    private record ScoredDoc(Document doc, int rank) {}
}
