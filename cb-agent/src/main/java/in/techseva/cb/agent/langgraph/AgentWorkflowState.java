package in.techseva.cb.agent.langgraph;

import in.techseva.cb.core.domain.Vulnerability;
import org.bsc.langgraph4j.state.AgentState;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable-by-convention state bag for the LangGraph4j multi-agent workflow.
 * Each node returns a partial Map that is merged into this state.
 */
public class AgentWorkflowState extends AgentState {

    public static final String VULNERABILITY = "vulnerability";
    public static final String CONTEXT_DOCS  = "contextDocs";
    public static final String GENERATED_FIX = "generatedFix";
    public static final String LLM_MODEL     = "llmModel";
    public static final String CONFIDENCE    = "confidence";
    public static final String VALIDATION_OK = "validationOk";
    public static final String RETRY_COUNT   = "retryCount";
    public static final String ERROR         = "error";
    public static final String INPUT_TOKENS  = "inputTokens";
    public static final String OUTPUT_TOKENS = "outputTokens";

    public AgentWorkflowState(Map<String, Object> initData) {
        super(initData);
    }

    public Optional<Vulnerability> vulnerability() {
        return value(VULNERABILITY, Vulnerability.class);
    }

    @SuppressWarnings("unchecked")
    public List<String> contextDocs() {
        return (List<String>) data().getOrDefault(CONTEXT_DOCS, List.of());
    }

    public Optional<String> generatedFix() {
        return value(GENERATED_FIX, String.class);
    }

    public String llmModel() {
        return (String) data().getOrDefault(LLM_MODEL, "gpt-4o");
    }

    public double confidence() {
        Object v = data().get(CONFIDENCE);
        return v instanceof Number n ? n.doubleValue() : 0.0;
    }

    public boolean validationOk() {
        Object v = data().get(VALIDATION_OK);
        return v instanceof Boolean b && b;
    }

    public int retryCount() {
        Object v = data().get(RETRY_COUNT);
        return v instanceof Integer i ? i : 0;
    }

    public Optional<String> error() {
        return value(ERROR, String.class);
    }

    public long inputTokens() {
        Object v = data().get(INPUT_TOKENS);
        return v instanceof Number n ? n.longValue() : 0L;
    }

    public long outputTokens() {
        Object v = data().get(OUTPUT_TOKENS);
        return v instanceof Number n ? n.longValue() : 0L;
    }
}
