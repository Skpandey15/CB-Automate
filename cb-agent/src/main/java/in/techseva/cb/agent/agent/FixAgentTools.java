package in.techseva.cb.agent.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.techseva.cb.agent.rag.QdrantRAGService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
public class FixAgentTools {

    private static final Logger log = LoggerFactory.getLogger(FixAgentTools.class);

    private final QdrantRAGService qdrantRAGService;
    private final ObjectMapper objectMapper;

    public FixAgentTools(QdrantRAGService qdrantRAGService, ObjectMapper objectMapper) {
        this.qdrantRAGService = qdrantRAGService;
        this.objectMapper = objectMapper;
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
            case "CWE-89" -> "SQL Injection: Use PreparedStatement or JPA named parameters. Never concatenate user input into SQL strings.";
            case "CWE-79" -> "XSS: Escape output with HtmlUtils.htmlEscape(). Set Content-Security-Policy header. Avoid innerHTML.";
            case "CWE-78" -> "Command Injection: Use ProcessBuilder with argument list. Never pass user input to Runtime.exec(String).";
            case "CWE-22" -> "Path Traversal: Canonicalize paths via Paths.get().normalize(), verify they start within allowed base directory.";
            case "CWE-798" -> "Hardcoded Credentials: Move to Vault or environment variables. Use @Value with vault: prefix.";
            case "CWE-327" -> "Broken Crypto: Replace MD5/SHA1 with SHA-256 or higher. Use BCryptPasswordEncoder for passwords.";
            case "CWE-918" -> "SSRF: Validate URLs against an allowlist before making HTTP calls. Reject private IP ranges.";
            case "CWE-330" -> "Insufficient Randomness: Replace java.util.Random with java.security.SecureRandom for security-sensitive operations.";
            case "CWE-1078" -> "Weak Password Policy: Enforce minimum length >= 12, require mixed case, digits, and special chars. Use BCrypt.";
            default -> String.format("No specific guideline for %s. Apply OWASP Top 10 best practices: input validation, output encoding, and least privilege.", cweId);
        };
    }
}
