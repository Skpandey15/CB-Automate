package in.techseva.cb.agent.memory;

import in.techseva.cb.core.domain.Fix;
import in.techseva.cb.core.domain.FixStatus;
import in.techseva.cb.core.domain.Vulnerability;
import in.techseva.cb.core.repository.FixRepository;
import in.techseva.cb.core.repository.VulnerabilityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class FixMemoryService {

    private static final Logger log = LoggerFactory.getLogger(FixMemoryService.class);

    private final VectorStore vectorStore;
    private final FixRepository fixRepo;
    private final VulnerabilityRepository vulnRepo;

    // Session-scoped dedup: avoids re-embedding on every poll cycle
    private final Set<String> indexedThisSession = ConcurrentHashMap.newKeySet();

    public FixMemoryService(VectorStore vectorStore,
                            FixRepository fixRepo,
                            VulnerabilityRepository vulnRepo) {
        this.vectorStore = vectorStore;
        this.fixRepo = fixRepo;
        this.vulnRepo = vulnRepo;
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 90_000)
    public void indexValidatedFixes() {
        List<Fix> candidates = fixRepo.findByStatus(FixStatus.BUILD_VALIDATED)
            .stream()
            .filter(f -> f.id() != null && !indexedThisSession.contains(f.id()))
            .toList();

        if (candidates.isEmpty()) {
            return;
        }

        List<Document> docs = candidates.stream()
            .map(fix -> {
                Vulnerability vuln = vulnRepo.findById(fix.vulnerabilityId()).orElse(null);
                return toDocument(fix, vuln);
            })
            .toList();

        try {
            vectorStore.add(docs);
            docs.forEach(d -> indexedThisSession.add(d.getId()));
            log.info("Indexed {} build-validated fixes into Qdrant memory store", docs.size());
        } catch (Exception e) {
            log.warn("Failed to index fixes into Qdrant: {}", e.getMessage());
        }
    }

    public void indexFix(Fix fix, Vulnerability vuln) {
        try {
            vectorStore.add(List.of(toDocument(fix, vuln)));
            if (fix.id() != null) {
                indexedThisSession.add(fix.id());
            }
            log.debug("Indexed fix id={} into Qdrant", fix.id());
        } catch (Exception e) {
            log.warn("On-demand Qdrant index failed for fix={}: {}", fix.id(), e.getMessage());
        }
    }

    private Document toDocument(Fix fix, Vulnerability vuln) {
        String cweId = vuln != null ? vuln.cweId() : "unknown";
        String severity = vuln != null && vuln.severity() != null ? vuln.severity().toString() : "UNKNOWN";
        String message = vuln != null ? vuln.message() : "";

        String embedText = String.format(
            "CWE:%s severity:%s strategy:%s message:%s explanation:%s",
            cweId, severity, fix.strategy(), message, fix.explanation()
        );

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("fixId", fix.id());
        metadata.put("vulnerabilityId", fix.vulnerabilityId());
        metadata.put("cweId", cweId);
        metadata.put("strategy", fix.strategy() != null ? fix.strategy() : "");
        metadata.put("confidence", fix.confidence());
        metadata.put("patchDiff", fix.patchDiff() != null ? fix.patchDiff() : "");
        metadata.put("explanation", fix.explanation() != null ? fix.explanation() : "");
        metadata.put("llmModel", fix.llmModel() != null ? fix.llmModel() : "");

        return new Document(fix.id(), embedText, metadata);
    }
}
