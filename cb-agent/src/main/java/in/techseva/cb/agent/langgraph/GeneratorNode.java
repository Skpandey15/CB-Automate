package in.techseva.cb.agent.langgraph;

import in.techseva.cb.agent.cache.RedisPromptCache;
import in.techseva.cb.agent.service.OllamaRoutingService;
import in.techseva.cb.core.domain.Vulnerability;
import org.bsc.langgraph4j.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Third node: generates the fix using the routed LLM with cached prompt lookup.
 */
@Component
public class GeneratorNode implements NodeAction<AgentWorkflowState> {

    private static final Logger log = LoggerFactory.getLogger(GeneratorNode.class);

    private static final String SYSTEM_PROMPT = """
            You are an expert Java 17 / Spring Boot security engineer specialising in OWASP Top 10 remediation.
            Use the provided similar fix examples as guidance. Respond ONLY with a JSON-LD cb:Fix object.

            Required fields: @context, @type, patchDiff (unified diff), gradlePatch (null if not needed),
            strategy (short label), confidence (0.0-1.0), llmModel, explanation (≤200 words).
            On every patched line add trailing comment: // CB-FIX: <cweId> — <strategy>
            JSON-LD CONTEXT: https://techseva.in/ontology/cb#
            """;

    private final OllamaRoutingService routingService;
    private final RedisPromptCache promptCache;

    public GeneratorNode(OllamaRoutingService routingService, RedisPromptCache promptCache) {
        this.routingService = routingService;
        this.promptCache = promptCache;
    }

    @Override
    public Map<String, Object> apply(AgentWorkflowState state) throws Exception {
        Vulnerability vuln = state.vulnerability()
                .orElseThrow(() -> new IllegalStateException("No vulnerability in state"));

        String userPrompt = buildPrompt(vuln, state.contextDocs(), state.retryCount());
        String promptHash = RedisPromptCache.hashPrompt(state.llmModel() + ":" + userPrompt);

        Optional<String> cached = promptCache.getPromptResponse(state.llmModel(), promptHash);
        if (cached.isPresent()) {
            log.info("GeneratorNode: cache HIT for vuln={} model={}", vuln.id(), state.llmModel());
            return Map.of(
                    AgentWorkflowState.GENERATED_FIX, cached.get(),
                    AgentWorkflowState.INPUT_TOKENS, 0L,
                    AgentWorkflowState.OUTPUT_TOKENS, 0L
            );
        }

        ChatClient client = routingService.routeForVulnerability(vuln);
        ChatResponse response = client.prompt()
                .system(SYSTEM_PROMPT)
                .user(userPrompt)
                .call()
                .chatResponse();

        String fixJson = response.getResult().getOutput().getText();
        Usage usage = response.getMetadata().getUsage();
        long inputTokens = usage != null ? usage.getPromptTokens() : 0L;
        long outputTokens = usage != null ? usage.getGenerationTokens() : 0L;

        promptCache.putPromptResponse(state.llmModel(), promptHash, fixJson);
        log.info("GeneratorNode: generated fix for vuln={} model={} tokens={}+{}",
                vuln.id(), state.llmModel(), inputTokens, outputTokens);

        return Map.of(
                AgentWorkflowState.GENERATED_FIX, fixJson,
                AgentWorkflowState.INPUT_TOKENS, inputTokens,
                AgentWorkflowState.OUTPUT_TOKENS, outputTokens
        );
    }

    private String buildPrompt(Vulnerability vuln, List<String> contextDocs, int retryCount) {
        var sb = new StringBuilder();
        sb.append("## VULNERABILITY\n");
        sb.append("ID: ").append(vuln.id()).append("\n");
        sb.append("CWE: ").append(vuln.cweId()).append("\n");
        sb.append("Severity: ").append(vuln.severity()).append("\n");
        sb.append("File: ").append(vuln.filePath()).append(":").append(vuln.lineNo()).append("\n");
        sb.append("Message: ").append(vuln.message()).append("\n");

        if (!contextDocs.isEmpty()) {
            sb.append("\n## SIMILAR ACCEPTED FIXES (use as examples)\n");
            for (int i = 0; i < contextDocs.size(); i++) {
                sb.append("\nExample ").append(i + 1).append(":\n").append(contextDocs.get(i)).append("\n");
            }
        }

        if (retryCount > 0) {
            sb.append("\n⚠️ RETRY ATTEMPT ").append(retryCount)
              .append(": Previous fix was rejected. Generate an improved fix.\n");
        }

        sb.append("\nGenerate the cb:Fix JSON-LD object now:");
        return sb.toString();
    }
}
