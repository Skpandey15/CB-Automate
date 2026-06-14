package in.techseva.cb.agent.prompt;

import in.techseva.cb.core.domain.Fix;
import in.techseva.cb.core.domain.ReviewFeedback;
import in.techseva.cb.core.domain.Vulnerability;
import in.techseva.cb.core.ontology.OntologyMapper;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PromptBuilder {

    private static final String SYSTEM_PROMPT = """
            You are an expert Java 17 / Spring Boot 4 security engineer specialising in OWASP Top 10 remediation.

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
            1. Respond ONLY with a JSON-LD object of type cb:Fix.
            2. Required fields: @context, @type, patchDiff (unified diff), gradlePatch (or null if not needed),
               strategy (short label), confidence (0.0-1.0), llmModel, explanation (≤200 words).
            3. patchDiff must be a valid unified diff (--- a/path, +++ b/path, @@ headers).
            4. On every patched line add a trailing comment: // CB-FIX: <cweId> — <strategy>
            5. Do NOT include prose outside the JSON-LD block.
            6. If you cannot generate a safe fix, set confidence to 0.0 and explain why.

            JSON-LD CONTEXT: https://techseva.in/ontology/cb#
            """;

    private final OntologyMapper ontologyMapper;

    public PromptBuilder(OntologyMapper ontologyMapper) {
        this.ontologyMapper = ontologyMapper;
    }

    public String buildSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    public String buildUserPrompt(Vulnerability vuln, List<Fix> ragFixes) {
        StringBuilder sb = new StringBuilder();
        sb.append("## VULNERABILITY TO REMEDIATE\n\n");
        sb.append(ontologyMapper.vulnerabilityToJsonLdString(vuln));
        sb.append("\n\n");

        if (!ragFixes.isEmpty()) {
            sb.append("## SIMILAR VALIDATED FIXES (RAG top-").append(ragFixes.size()).append(")\n\n");
            sb.append("Adapt the fix style from these validated examples to the current file/line context:\n\n");
            sb.append("[\n");
            for (int i = 0; i < ragFixes.size(); i++) {
                sb.append(ontologyMapper.fixToJsonLdString(ragFixes.get(i)));
                if (i < ragFixes.size() - 1) sb.append(",\n");
            }
            sb.append("\n]\n\n");
        } else {
            sb.append("## NO PRIOR FIXES\n\nNo similar validated fixes found. Generate a fresh fix based on CWE guidelines.\n\n");
        }

        sb.append("## INSTRUCTION\n\n");
        sb.append("Generate a JSON-LD cb:Fix object that remediates the vulnerability above. ");
        sb.append("Follow CWE remediation guidelines in the system prompt. ");
        sb.append("Respond ONLY with the JSON-LD object — no preamble, no explanation outside the JSON.");
        return sb.toString();
    }

    public String buildUserPrompt(Vulnerability vuln, List<Fix> ragFixes,
                                   List<ReviewFeedback> reviewFeedbacks) {
        String base = buildUserPrompt(vuln, ragFixes);
        if (reviewFeedbacks == null || reviewFeedbacks.isEmpty()) return base;

        StringBuilder sb = new StringBuilder(base);
        sb.append("\n\n## ⚠️ PREVIOUS FIX REJECTED — REVIEWER FEEDBACK\n\n");
        sb.append("Your previous fix attempt was reviewed and rejected. ");
        sb.append("You MUST address ALL of the following concerns in the new fix:\n\n");
        for (int i = 0; i < reviewFeedbacks.size(); i++) {
            sb.append(i + 1).append(". ").append(reviewFeedbacks.get(i).rejectionReason()).append("\n");
        }
        sb.append("\nGenerate an improved fix that explicitly resolves every concern listed above.");
        return sb.toString();
    }
}
