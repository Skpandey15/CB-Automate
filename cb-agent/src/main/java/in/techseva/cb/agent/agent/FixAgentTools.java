package in.techseva.cb.agent.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.techseva.cb.agent.rag.QdrantRAGService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Component
public class FixAgentTools {

    private static final Logger log = LoggerFactory.getLogger(FixAgentTools.class);

    private final QdrantRAGService qdrantRAGService;
    private final ObjectMapper objectMapper;
    private final RestClient mavenCentralClient;

    @Autowired
    public FixAgentTools(QdrantRAGService qdrantRAGService, ObjectMapper objectMapper,
                          RestClient.Builder restClientBuilder) {
        this.qdrantRAGService = qdrantRAGService;
        this.objectMapper = objectMapper;
        this.mavenCentralClient = restClientBuilder
                .baseUrl("https://search.maven.org/solrsearch/select")
                .build();
    }

    /** Test-only seam: inject a pre-built RestClient (e.g. mocked transport) directly. */
    FixAgentTools(QdrantRAGService qdrantRAGService, ObjectMapper objectMapper, RestClient mavenCentralClient) {
        this.qdrantRAGService = qdrantRAGService;
        this.objectMapper = objectMapper;
        this.mavenCentralClient = mavenCentralClient;
    }

    @Tool(description = "Retrieve the top validated security fixes from the Compliance Buddy knowledge base that are similar to the given vulnerability. You MUST call this tool first before generating any fix. Returns a JSON array of fix summaries with patchDiff, strategy, and explanation.")
    public String retrieveSimilarFixes(
            @ToolParam(description = "CWE identifier such as CWE-89 or CWE-79") String cweId,
            @ToolParam(description = "Short description of the vulnerability including severity and message") String vulnerabilityMessage
    ) {
        log.info("Agent calling retrieveSimilarFixes: cweId={}", cweId);
        try {
            List<QdrantRAGService.FixSummary> fixes =
                qdrantRAGService.retrieveSimilarFixes(cweId, vulnerabilityMessage);
            if (fixes.isEmpty()) {
                return "[]";
            }
            return objectMapper.writeValueAsString(fixes);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize fix summaries: {}", e.getMessage());
            return "[]";
        } catch (Exception e) {
            log.warn("retrieveSimilarFixes tool error: {}", e.getMessage());
            return "[]";
        }
    }

    @Tool(description = "Get all CWE remediation guidelines and OWASP mapping for the given CWE ID. Useful when no similar fixes are found and you need to know the standard remediation approach.")
    public String getRemediationGuideline(
            @ToolParam(description = "CWE identifier such as CWE-89") String cweId
    ) {
        return switch (cweId.toUpperCase()) {
            case "CWE-89"   -> "SQL Injection: Use PreparedStatement or JPA named parameters. Never concatenate user input into SQL strings.";
            case "CWE-79"   -> "XSS: Escape output with HtmlUtils.htmlEscape(). Set Content-Security-Policy header. Avoid innerHTML.";
            case "CWE-78"   -> "Command Injection: Use ProcessBuilder with argument list. Never pass user input to Runtime.exec(String).";
            case "CWE-22"   -> "Path Traversal: Canonicalize paths via Paths.get().normalize(), verify they start within allowed base directory.";
            case "CWE-798"  -> "Hardcoded Credentials: Move to Vault or environment variables. Use @Value with vault: prefix.";
            case "CWE-327"  -> "Broken Crypto: Replace MD5/SHA1 with SHA-256 or higher. Use BCryptPasswordEncoder for passwords.";
            case "CWE-918"  -> "SSRF: Validate URLs against an allowlist before making HTTP calls. Reject private IP ranges.";
            case "CWE-330"  -> "Insufficient Randomness: Replace java.util.Random with java.security.SecureRandom for security-sensitive operations.";
            case "CWE-1078" -> "Weak Password Policy: Enforce minimum length >= 12, require mixed case, digits, and special chars. Use BCrypt.";
            case "CWE-1104" -> "Vulnerable Dependency: Upgrade the dependency to the latest stable version that does not contain the CVE. " +
                               "Update the version string in build.gradle (or gradle.properties). Run './gradlew build' to verify.";
            default -> String.format("No specific guideline for %s. Apply OWASP Top 10 best practices: input validation, output encoding, and least privilege.", cweId);
        };
    }

    @Tool(description = "Look up the latest stable version of a Maven/Gradle dependency from Maven Central. " +
                        "Use this for DEPENDENCY type vulnerabilities to find the safe version to upgrade to. " +
                        "Returns a JSON object with 'latestVersion' and 'coordinate'.")
    public String getSafeVersion(
            @ToolParam(description = "Maven group ID, e.g. org.springframework") String groupId,
            @ToolParam(description = "Maven artifact ID, e.g. spring-webmvc") String artifactId
    ) {
        log.info("Agent calling getSafeVersion: {}:{}", groupId, artifactId);
        try {
            String query = "g:" + groupId + "+AND+a:" + artifactId;
            @SuppressWarnings("unchecked")
            Map<String, Object> response = mavenCentralClient.get()
                    .uri("?q={q}&core=gav&rows=10&wt=json", query)
                    .retrieve()
                    .body(Map.class);

            if (response == null) return "{\"error\": \"No response from Maven Central\"}";

            @SuppressWarnings("unchecked")
            Map<String, Object> resp = (Map<String, Object>) response.get("response");
            if (resp == null) return "{\"error\": \"Unexpected Maven Central response format\"}";

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> docs = (List<Map<String, Object>>) resp.get("docs");
            if (docs == null || docs.isEmpty()) {
                return "{\"error\": \"Artifact " + groupId + ":" + artifactId + " not found on Maven Central\"}";
            }

            String latest = docs.stream()
                    .map(d -> (String) d.get("v"))
                    .filter(v -> v != null && isStable(v))
                    .findFirst()
                    .orElse((String) docs.get(0).get("v"));

            return objectMapper.writeValueAsString(Map.of(
                    "groupId", groupId,
                    "artifactId", artifactId,
                    "latestStableVersion", latest != null ? latest : "unknown",
                    "coordinate", groupId + ":" + artifactId + ":" + (latest != null ? latest : "?")
            ));
        } catch (Exception e) {
            log.warn("getSafeVersion failed for {}:{}: {}", groupId, artifactId, e.getMessage());
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    private boolean isStable(String version) {
        String lower = version.toLowerCase();
        return !lower.contains("alpha") && !lower.contains("beta")
                && !lower.contains("rc") && !lower.contains("snapshot")
                && !lower.contains("milestone") && !lower.contains(".m");
    }
}
