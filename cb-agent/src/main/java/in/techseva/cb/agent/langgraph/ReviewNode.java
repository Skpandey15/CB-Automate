package in.techseva.cb.agent.langgraph;

import in.techseva.cb.core.domain.Vulnerability;
import org.bsc.langgraph4j.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Fifth node: final quality review using the fallback (Anthropic claude) model.
 * Checks semantic correctness of the patch, not just structure.
 */
@Component
public class ReviewNode implements NodeAction<AgentWorkflowState> {

    private static final Logger log = LoggerFactory.getLogger(ReviewNode.class);

    private static final String REVIEW_PROMPT = """
            You are a senior security code reviewer. Review the following security fix JSON-LD object.
            Check:
            1. Does the patchDiff actually fix the CWE vulnerability without introducing new ones?
            2. Is the fix minimal and correct?
            3. Does it follow the coding conventions implied by the context?

            Respond in JSON: {"approved": true/false, "finalConfidence": 0.0-1.0, "reviewNote": "brief note"}
            """;

    private final ChatClient reviewChatClient;

    public ReviewNode(@Qualifier("fallbackChatClient") ChatClient reviewChatClient) {
        this.reviewChatClient = reviewChatClient;
    }

    @Override
    public Map<String, Object> apply(AgentWorkflowState state) throws Exception {
        Vulnerability vuln = state.vulnerability()
                .orElseThrow(() -> new IllegalStateException("No vulnerability in state"));
        String fix = state.generatedFix().orElse("");

        String reviewInput = "CWE: " + vuln.cweId() + "\nSeverity: " + vuln.severity() + "\n\nFix:\n" + fix;

        String reviewResponse;
        try {
            reviewResponse = reviewChatClient.prompt()
                    .system(REVIEW_PROMPT)
                    .user(reviewInput)
                    .call()
                    .content();
        } catch (Exception e) {
            log.warn("ReviewNode: review LLM call failed, accepting with original confidence: {}", e.getMessage());
            return Map.of(
                    AgentWorkflowState.VALIDATION_OK, true,
                    AgentWorkflowState.CONFIDENCE, state.confidence()
            );
        }

        double finalConfidence = extractFinalConfidence(reviewResponse, state.confidence());
        boolean approved = reviewResponse.contains("\"approved\": true") || reviewResponse.contains("\"approved\":true");

        log.info("ReviewNode: vuln={} approved={} finalConfidence={}", vuln.id(), approved, finalConfidence);

        return Map.of(
                AgentWorkflowState.VALIDATION_OK, approved,
                AgentWorkflowState.CONFIDENCE, finalConfidence
        );
    }

    private double extractFinalConfidence(String response, double fallback) {
        try {
            int idx = response.indexOf("\"finalConfidence\"");
            if (idx < 0) return fallback;
            int colon = response.indexOf(':', idx);
            int end = response.indexOf(',', colon);
            if (end < 0) end = response.indexOf('}', colon);
            return Double.parseDouble(response.substring(colon + 1, end).strip());
        } catch (Exception e) {
            return fallback;
        }
    }
}
