package in.techseva.cb.agent.langgraph;

import org.bsc.langgraph4j.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Fourth node: validates the generated fix JSON structure and basic sanity checks.
 * Full OPA governance happens later in cb-pr. This node handles structural validation.
 */
@Component
public class ValidatorNode implements NodeAction<AgentWorkflowState> {

    private static final Logger log = LoggerFactory.getLogger(ValidatorNode.class);
    private static final double MIN_CONFIDENCE = 0.3;

    @Override
    public Map<String, Object> apply(AgentWorkflowState state) throws Exception {
        String fix = state.generatedFix().orElse("");

        if (fix.isBlank()) {
            log.warn("ValidatorNode: empty fix generated");
            return Map.of(AgentWorkflowState.VALIDATION_OK, false,
                          AgentWorkflowState.ERROR, "Empty fix generated");
        }

        // Strip markdown code fences if present
        String json = fix.strip();
        if (json.startsWith("```")) {
            int start = json.indexOf('\n') + 1;
            int end = json.lastIndexOf("```");
            json = end > start ? json.substring(start, end).strip() : json;
        }

        // Must be valid JSON with required fields
        if (!json.startsWith("{") || !json.contains("patchDiff") || !json.contains("confidence")) {
            log.warn("ValidatorNode: fix missing required JSON-LD fields");
            return Map.of(AgentWorkflowState.VALIDATION_OK, false,
                          AgentWorkflowState.ERROR, "Fix JSON missing required fields: patchDiff, confidence");
        }

        // Extract confidence value
        double confidence = extractConfidence(json);
        if (confidence < MIN_CONFIDENCE) {
            log.warn("ValidatorNode: confidence {} below threshold {}", confidence, MIN_CONFIDENCE);
            return Map.of(AgentWorkflowState.VALIDATION_OK, false,
                          AgentWorkflowState.CONFIDENCE, confidence,
                          AgentWorkflowState.ERROR, "Confidence " + confidence + " below minimum " + MIN_CONFIDENCE);
        }

        // Reject fixes that attempt system-level exploits
        if (json.contains("System.exit") || json.contains("Runtime.getRuntime().exec")) {
            log.error("ValidatorNode: SECURITY VIOLATION — fix contains dangerous call");
            return Map.of(AgentWorkflowState.VALIDATION_OK, false,
                          AgentWorkflowState.ERROR, "Fix contains dangerous system calls");
        }

        log.info("ValidatorNode: fix VALID confidence={}", confidence);
        return Map.of(
                AgentWorkflowState.VALIDATION_OK, true,
                AgentWorkflowState.CONFIDENCE, confidence,
                AgentWorkflowState.GENERATED_FIX, json
        );
    }

    private double extractConfidence(String json) {
        try {
            int idx = json.indexOf("\"confidence\"");
            if (idx < 0) return 0.0;
            int colon = json.indexOf(':', idx);
            int comma = json.indexOf(',', colon);
            int brace = json.indexOf('}', colon);
            int end = comma > 0 && comma < brace ? comma : brace;
            return Double.parseDouble(json.substring(colon + 1, end).strip());
        } catch (Exception e) {
            return 0.0;
        }
    }
}
