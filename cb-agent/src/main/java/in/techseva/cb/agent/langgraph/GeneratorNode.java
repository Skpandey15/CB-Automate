package in.techseva.cb.agent.langgraph;

import in.techseva.cb.agent.cache.RedisPromptCache;
import in.techseva.cb.agent.service.OllamaRoutingService;
import in.techseva.cb.core.domain.Vulnerability;
import in.techseva.cb.core.domain.VulnerabilityType;
import org.bsc.langgraph4j.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

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

            CRITICAL patchDiff FORMAT RULES — violations will cause the fix to be discarded:
            1. patchDiff MUST be a raw unified diff string value inside the JSON string field.
            2. DO NOT wrap patchDiff in markdown code fences (no ```java, no ```, no backticks of any kind).
            3. The diff MUST begin with "--- a/{filePath}" on the first line.
            4. The second line MUST be "+++ b/{filePath}" where filePath matches the vulnerability file exactly.
            5. Each changed block MUST have a "@@ -startLine,count +startLine,count @@" hunk header.
            6. Use ONLY line content from the ACTUAL FILE CONTENT section — do not invent lines.
            7. Context lines (unchanged) must start with a single space character.
            8. Removed lines must start with "-". Added lines must start with "+".

            EXAMPLE of correct patchDiff value (JSON string, no backticks):
            "--- a/auth-service/src/main/java/com/example/User.java\\n+++ b/auth-service/src/main/java/com/example/User.java\\n@@ -10,7 +10,7 @@\\n public class User {\\n-    private String pass = \\"secret\\"; // CB-FIX: CWE-798 — remove-hardcoded-credential\\n+    @Value(\\"${app.secret}\\")\\n+    private String pass;\\n }"
            """;

    private final OllamaRoutingService routingService;
    private final RedisPromptCache promptCache;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${github.owner:}")
    private String githubOwner;

    @Value("${github.repo:}")
    private String githubRepo;

    @Value("${github.token:}")
    private String githubToken;

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
        long inputTokens  = usage != null && usage.getPromptTokens()     != null ? usage.getPromptTokens()     : 0L;
        long outputTokens = usage != null && usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0L;

        promptCache.putPromptResponse(state.llmModel(), promptHash, fixJson);
        log.info("GeneratorNode: generated fix for vuln={} model={} tokens={}+{}",
                vuln.id(), state.llmModel(), inputTokens, outputTokens);

        return Map.of(
                AgentWorkflowState.GENERATED_FIX, fixJson,
                AgentWorkflowState.INPUT_TOKENS, inputTokens,
                AgentWorkflowState.OUTPUT_TOKENS, outputTokens
        );
    }

    private String fetchFileContent(String filePath) {
        if (githubOwner.isBlank() || githubRepo.isBlank() || filePath == null) return null;
        // Try direct path, then with springboot-microservices/ prefix
        String[] candidates = { filePath, "springboot-microservices/" + filePath };
        for (String candidate : candidates) {
            String url = "https://raw.githubusercontent.com/" + githubOwner + "/" + githubRepo + "/main/" + candidate;
            try {
                org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
                if (!githubToken.isBlank()) headers.set("Authorization", "token " + githubToken);
                var entity = new org.springframework.http.HttpEntity<>(headers);
                var resp = restTemplate.exchange(url, org.springframework.http.HttpMethod.GET, entity, String.class);
                if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                    log.debug("Fetched file content from {}", url);
                    return resp.getBody();
                }
            } catch (Exception e) {
                log.debug("Could not fetch {}: {}", url, e.getMessage());
            }
        }
        return null;
    }

    private String buildPrompt(Vulnerability vuln, List<String> contextDocs, int retryCount) {
        var sb = new StringBuilder();
        sb.append("## VULNERABILITY\n");
        sb.append("ID: ").append(vuln.id()).append("\n");
        sb.append("Type: ").append(vuln.vulnType() != null ? vuln.vulnType() : VulnerabilityType.CODE).append("\n");
        sb.append("CWE: ").append(vuln.cweId()).append("\n");
        sb.append("Severity: ").append(vuln.severity()).append("\n");
        sb.append("File: ").append(vuln.filePath()).append(":").append(vuln.lineNo()).append("\n");
        sb.append("Message: ").append(vuln.message()).append("\n");

        // Include actual file content so the model generates a correct diff
        if (!vuln.isDependencyVulnerability()) {
            String fileContent = fetchFileContent(vuln.filePath());
            if (fileContent != null) {
                sb.append("\n## ACTUAL FILE CONTENT (use ONLY these lines in your diff)\n```java\n");
                sb.append(fileContent);
                sb.append("\n```\n");
                sb.append("IMPORTANT: Your patchDiff MUST use exact line content from the file above.\n");
            }
        }

        if (vuln.isDependencyVulnerability()) {
            sb.append("\n## DEPENDENCY DETAILS\n");
            sb.append("Group:    ").append(vuln.dependencyGroup()).append("\n");
            sb.append("Artifact: ").append(vuln.dependencyArtifact()).append("\n");
            sb.append("Vulnerable version: ").append(vuln.vulnerableVersion()).append("\n");
            if (vuln.safeVersion() != null) {
                sb.append("Safe version: ").append(vuln.safeVersion()).append("\n");
            }
            if (vuln.cveId() != null) {
                sb.append("CVE: ").append(vuln.cveId()).append("\n");
            }
            sb.append("\nINSTRUCTION: This is a DEPENDENCY vulnerability. ");
            sb.append("Set patchDiff to null. ");
            sb.append("Set gradlePatch to: \"")
              .append(vuln.dependencyGroup()).append(":").append(vuln.dependencyArtifact())
              .append(":").append(vuln.vulnerableVersion()).append(" -> ").append(
                      vuln.safeVersion() != null ? vuln.safeVersion() : "<latest-safe-version>")
              .append("\"\n");
            sb.append("Set strategy to \"dependency-upgrade\".\n");
        }

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
