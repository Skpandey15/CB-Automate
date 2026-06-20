package in.techseva.cb.agent.agent;

import in.techseva.cb.core.domain.ReviewFeedback;
import in.techseva.cb.core.domain.Vulnerability;
import in.techseva.cb.core.domain.VulnerabilityType;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    private static final String SYSTEM_PROMPT = """
            You are an expert Java 17 / Spring Boot 4 security engineer specialising in OWASP Top 10 remediation.

            MANDATORY WORKFLOW:
            1. FIRST call the retrieveSimilarFixes tool with the CWE ID and vulnerability description.
               This retrieves validated examples from the knowledge base to guide your fix style.
            2. If no examples are found, call getRemediationGuideline for the CWE to confirm the correct approach.
            3. For DEPENDENCY vulnerabilities: call getSafeVersion(groupId, artifactId) to confirm the safe version.
               The safeVersion field may already be provided in the vulnerability — verify it with getSafeVersion.
            4. THEN generate the JSON-LD cb:Fix object.

            DEPENDENCY VULNERABILITY RULES (vulnType=DEPENDENCY):
            - patchDiff must be null or empty — there is no source code to change.
            - gradlePatch MUST be populated in format: "group:artifact:vulnerableVersion -> safeVersion"
              Example: "org.springframework:spring-webmvc:5.3.27 -> 5.3.39"
            - Multiple lines allowed if several files declare the same dependency.
            - strategy should be "dependency-upgrade".
            - confidence: 0.95 if safeVersion is confirmed by getSafeVersion, else 0.7.

            CWE REMEDIATION GUIDELINES:
            - CWE-89 (SQL Injection): Use PreparedStatement or JPA named params. Never concatenate user input into SQL.
            - CWE-79 (XSS): Escape output with HtmlUtils.htmlEscape(). Use Content-Security-Policy header.
            - CWE-78 (Command Injection): Use ProcessBuilder with arg lists. Never pass user input to Runtime.exec(String).
            - CWE-22 (Path Traversal): Canonicalize paths, verify they start with allowed base dir.
            - CWE-798 (Hardcoded Credentials): Move to Vault / environment variables. Use @Value with Vault secrets.
            - CWE-327 (Broken Crypto): Replace MD5/SHA1 with SHA-256+. Use BCryptPasswordEncoder for passwords.
            - CWE-918 (SSRF): Whitelist allowed URLs. Use URI allow-list validation before HTTP calls.
            - CWE-330 (Insufficient Randomness): Replace java.util.Random with SecureRandom.

            OUTPUT RULES (STRICT):
            1. Respond ONLY with a JSON-LD object of type cb:Fix. No prose before or after.
            2. Required fields: @context, @type, patchDiff (unified diff), gradlePatch (or null if not needed),
               strategy (short label), confidence (0.0-1.0), llmModel, explanation (≤200 words).
            3. patchDiff must be a valid unified diff (--- a/path, +++ b/path, @@ headers).
            4. On every patched line add a trailing comment: // CB-FIX: <cweId> — <strategy>
            5. If you cannot generate a safe fix, set confidence to 0.0 and explain why.

            JSON-LD CONTEXT: https://techseva.in/ontology/cb#
            """;

    private final ChatClient primaryChatClient;
    private final ChatClient fallbackChatClient;
    private final FixAgentTools fixAgentTools;

    public AgentOrchestrator(@Qualifier("primaryChatClient") ChatClient primaryChatClient,
                             @Qualifier("fallbackChatClient") ChatClient fallbackChatClient,
                             FixAgentTools fixAgentTools) {
        this.primaryChatClient = primaryChatClient;
        this.fallbackChatClient = fallbackChatClient;
        this.fixAgentTools = fixAgentTools;
    }

    @CircuitBreaker(name = "llm-agent", fallbackMethod = "generateFixFallback")
    @Retry(name = "llm-agent")
    public String generateFix(Vulnerability vuln, List<ReviewFeedback> feedbacks) {
        log.info("Agentic fix generation for vuln={} cwe={}", vuln.id(), vuln.cweId());
        return primaryChatClient.prompt()
            .system(SYSTEM_PROMPT)
            .user(buildUserPrompt(vuln, feedbacks))
            .tools(fixAgentTools)
            .call()
            .content();
    }

    String generateFixFallback(Vulnerability vuln, List<ReviewFeedback> feedbacks, Throwable t) {
        log.warn("Primary agent circuit open, falling back to direct LLM: {}", t.getMessage());
        return fallbackChatClient.prompt()
            .system(SYSTEM_PROMPT)
            .user(buildUserPrompt(vuln, feedbacks))
            .call()
            .content();
    }

    private String buildUserPrompt(Vulnerability vuln, List<ReviewFeedback> feedbacks) {
        StringBuilder sb = new StringBuilder();
        sb.append("## VULNERABILITY TO REMEDIATE\n\n");
        sb.append("ID: ").append(vuln.id()).append("\n");
        sb.append("Type: ").append(vuln.vulnType() != null ? vuln.vulnType() : VulnerabilityType.CODE).append("\n");
        sb.append("CWE: ").append(vuln.cweId()).append("\n");
        sb.append("Severity: ").append(vuln.severity()).append("\n");
        sb.append("Rule: ").append(vuln.ruleKey()).append("\n");
        sb.append("File: ").append(vuln.filePath()).append(":").append(vuln.lineNo()).append("\n");
        sb.append("Message: ").append(vuln.message()).append("\n");
        if (vuln.component() != null) sb.append("Component: ").append(vuln.component()).append("\n");

        if (vuln.isDependencyVulnerability()) {
            sb.append("\n## DEPENDENCY DETAILS\n\n");
            sb.append("Group:    ").append(vuln.dependencyGroup()).append("\n");
            sb.append("Artifact: ").append(vuln.dependencyArtifact()).append("\n");
            sb.append("Vulnerable version: ").append(vuln.vulnerableVersion()).append("\n");
            if (vuln.safeVersion() != null) {
                sb.append("Safe version (pre-computed): ").append(vuln.safeVersion()).append("\n");
            }
            if (vuln.cveId() != null) {
                sb.append("CVE: ").append(vuln.cveId()).append("\n");
            }
            sb.append("Declared in: ").append(vuln.filePath()).append("\n");

            sb.append("\n## AGENT INSTRUCTIONS\n\n");
            sb.append("Step 1: Call retrieveSimilarFixes(\"").append(vuln.cweId()).append("\", \"");
            sb.append("dependency upgrade ").append(vuln.dependencyGroup()).append(":").append(vuln.dependencyArtifact()).append("\")\n");
            sb.append("Step 2: Call getSafeVersion(\"").append(vuln.dependencyGroup()).append("\", \"")
              .append(vuln.dependencyArtifact()).append("\") to confirm the safe upgrade target\n");
            sb.append("Step 3: Generate a JSON-LD cb:Fix with gradlePatch in format:\n");
            sb.append("        \"").append(vuln.dependencyGroup()).append(":").append(vuln.dependencyArtifact())
              .append(":").append(vuln.vulnerableVersion()).append(" -> <safeVersion>\"\n");
            sb.append("        Leave patchDiff null — no source code changes are needed.\n");
        } else {
            sb.append("\n## AGENT INSTRUCTIONS\n\n");
            sb.append("Step 1: Call retrieveSimilarFixes(\"").append(vuln.cweId()).append("\", \"");
            sb.append("severity:").append(vuln.severity()).append(" ").append(vuln.message()).append("\")\n");
            sb.append("Step 2: If needed, call getRemediationGuideline(\"").append(vuln.cweId()).append("\")\n");
            sb.append("Step 3: Generate a JSON-LD cb:Fix object remediating the vulnerability\n");
        }

        if (feedbacks != null && !feedbacks.isEmpty()) {
            sb.append("\n## ⚠️ PREVIOUS FIX REJECTED — REVIEWER FEEDBACK\n\n");
            sb.append("Your previous fix attempt was reviewed and rejected. ");
            sb.append("You MUST address ALL of the following concerns:\n\n");
            for (int i = 0; i < feedbacks.size(); i++) {
                sb.append(i + 1).append(". ").append(feedbacks.get(i).rejectionReason()).append("\n");
            }
            sb.append("\nGenerate an improved fix that explicitly resolves every concern above.\n");
        }

        return sb.toString();
    }
}
