package in.techseva.cb.core.ontology;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.techseva.cb.core.domain.Fix;
import in.techseva.cb.core.domain.Vulnerability;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class OntologyMapper {

    private static final String CB_NS = "https://techseva.in/ontology/cb#";
    private static final String CB_PREFIX = "cb:";

    private final ObjectMapper objectMapper;

    public OntologyMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> vulnerabilityToJsonLd(Vulnerability v) {
        ObjectNode node = objectMapper.createObjectNode();
        ObjectNode context = objectMapper.createObjectNode();
        context.put("cb", CB_NS);
        context.put("cweId", CB_NS + "cweId");
        context.put("severity", CB_NS + "severity");
        context.put("ruleKey", CB_NS + "ruleKey");
        context.put("lineNo", CB_NS + "lineNo");
        context.put("effort", CB_NS + "effort");
        context.put("status", CB_NS + "status");
        context.put("message", CB_NS + "message");
        context.put("owaspCategory", CB_NS + "owaspCategory");
        context.put("projectKey", CB_NS + "projectKey");
        context.put("sonarIssueKey", CB_NS + "sonarIssueKey");
        context.put("filePath", CB_NS + "filePath");
        node.set("@context", context);
        node.put("@type", CB_PREFIX + "Vulnerability");
        node.put("@id", CB_NS + "vulnerability/" + v.id());
        node.put("cweId", v.cweId());
        node.put("severity", v.severity() != null ? v.severity().name() : null);
        node.put("ruleKey", v.ruleKey());
        node.put("lineNo", v.lineNo());
        node.put("effort", v.effort());
        node.put("status", v.status() != null ? v.status().name() : null);
        node.put("message", v.message());
        node.put("owaspCategory", v.owaspCategory());
        node.put("projectKey", v.projectKey());
        node.put("sonarIssueKey", v.sonarIssueKey());
        node.put("filePath", v.filePath());
        if (v.detectedAt() != null) node.put("detectedAt", v.detectedAt().toString());
        return objectMapper.convertValue(node, Map.class);
    }

    public Map<String, Object> fixToJsonLd(Fix f) {
        ObjectNode node = objectMapper.createObjectNode();
        ObjectNode context = objectMapper.createObjectNode();
        context.put("cb", CB_NS);
        node.set("@context", context);
        node.put("@type", CB_PREFIX + "Fix");
        node.put("@id", CB_NS + "fix/" + f.id());
        node.put("patchDiff", f.patchDiff());
        node.put("gradlePatch", f.gradlePatch());
        node.put("strategy", f.strategy());
        node.put("confidence", f.confidence());
        node.put("llmModel", f.llmModel());
        node.put("explanation", f.explanation());
        node.put("status", f.status() != null ? f.status().name() : null);
        node.put("buildValidated", f.buildValidated());
        if (f.prUrl() != null) node.put("prUrl", f.prUrl());
        if (f.prNumber() != null) node.put("prNumber", f.prNumber());
        return objectMapper.convertValue(node, Map.class);
    }

    public String vulnerabilityToJsonLdString(Vulnerability v) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(vulnerabilityToJsonLd(v));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize vulnerability to JSON-LD", e);
        }
    }

    public String fixToJsonLdString(Fix f) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(fixToJsonLd(f));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize fix to JSON-LD", e);
        }
    }

    public Fix parseFixFromJsonLd(String jsonLd) {
        try {
            return objectMapper.readValue(jsonLd, Fix.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Fix from JSON-LD: " + e.getMessage(), e);
        }
    }
}
