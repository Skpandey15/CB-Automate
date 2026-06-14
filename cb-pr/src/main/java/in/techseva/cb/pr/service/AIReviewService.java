package in.techseva.cb.pr.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.techseva.cb.core.domain.Fix;
import in.techseva.cb.core.domain.ReviewSession;
import in.techseva.cb.core.domain.Vulnerability;
import in.techseva.cb.core.repository.ReviewSessionRepository;
import in.techseva.cb.pr.client.GitHubEnterpriseClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Posts a CB AI self-review on each raised PR.
 * GPT-4o analyses the applied patch and posts a GitHub comment with ACCEPT/REJECT checkboxes.
 */
@Service
public class AIReviewService {

    private static final Logger log = LoggerFactory.getLogger(AIReviewService.class);

    private static final String REVIEW_SYSTEM = """
            You are a senior Java security engineer conducting a code review.
            You are reviewing an automatically-generated security fix patch.
            Your goal: identify remaining concerns, missed cases, or side effects.

            Respond ONLY with a JSON array of findings. Each finding has:
              { "title": "short label (≤8 words)", "severity": "HIGH|MEDIUM|LOW", "description": "≤100 words" }

            If the fix is correct and complete, respond with an empty array: []
            Do NOT add prose outside the JSON array.
            """;

    private final ChatClient chatClient;
    private final GitHubEnterpriseClient githubClient;
    private final ReviewSessionRepository reviewSessionRepo;
    private final ObjectMapper objectMapper;
    private final String repoOwner;
    private final String repoName;

    public AIReviewService(
            @Qualifier("prReviewChatClient") ChatClient chatClient,
            GitHubEnterpriseClient githubClient,
            ReviewSessionRepository reviewSessionRepo,
            ObjectMapper objectMapper,
            @Value("${github.owner}") String repoOwner,
            @Value("${github.repo}") String repoName) {
        this.chatClient = chatClient;
        this.githubClient = githubClient;
        this.reviewSessionRepo = reviewSessionRepo;
        this.objectMapper = objectMapper;
        this.repoOwner = repoOwner;
        this.repoName = repoName;
    }

    public void reviewAndPost(Vulnerability vuln, Fix fix, int prNumber, String prUrl) {
        try {
            String diff = githubClient.getPRDiff(repoOwner, repoName, prNumber);
            List<Map<String, String>> findings = analyzeWithAI(vuln, fix, diff);

            String commentBody = buildReviewComment(vuln, prNumber, findings);
            Long commentId = githubClient.createIssueComment(repoOwner, repoName, prNumber, commentBody);

            String sessionStatus = findings.isEmpty() ? "NO_FINDINGS" : "PENDING";
            ReviewSession session = new ReviewSession(
                    null,
                    vuln.id(),
                    prNumber,
                    prUrl,
                    commentId,
                    sessionStatus,
                    findings.size(),
                    Instant.now()
            );
            reviewSessionRepo.save(session);
            log.info("CB review posted on PR #{}: {} finding(s), status={}", prNumber, findings.size(), sessionStatus);
        } catch (Exception e) {
            log.error("CB AI review failed for PR #{}: {}", prNumber, e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> analyzeWithAI(Vulnerability vuln, Fix fix, String diff) {
        String userPrompt = """
                ## VULNERABILITY BEING FIXED
                CWE: %s
                File: %s (line %d)
                Severity: %s
                Description: %s

                ## APPLIED FIX (unified diff)
                ```
                %s
                ```

                Review this fix. Respond ONLY with a JSON array of findings (or [] if clean).
                """.formatted(
                vuln.cweId(),
                vuln.filePath(),
                vuln.lineNo(),
                vuln.severity(),
                vuln.message(),
                diff != null && diff.length() > 8000 ? diff.substring(0, 8000) + "\n... (truncated)" : diff);

        String raw = chatClient.prompt()
                .system(REVIEW_SYSTEM)
                .user(userPrompt)
                .call()
                .content();

        return parseFindings(raw);
    }

    private List<Map<String, String>> parseFindings(String raw) {
        try {
            String json = raw.strip();
            if (json.startsWith("```")) {
                int start = json.indexOf('\n') + 1;
                int end = json.lastIndexOf("```");
                if (end > start) json = json.substring(start, end).strip();
            }
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Could not parse AI review findings: {}. Raw: {}", e.getMessage(),
                    raw.length() > 200 ? raw.substring(0, 200) : raw);
            return List.of();
        }
    }

    private String buildReviewComment(Vulnerability vuln, int prNumber,
                                       List<Map<String, String>> findings) {
        String fileName = vuln.filePath() != null
                ? vuln.filePath().substring(Math.max(0, vuln.filePath().lastIndexOf('/') + 1))
                : "unknown";

        StringBuilder sb = new StringBuilder();
        sb.append("## 🤖 Compliance Buddy AI Review\n\n");

        if (findings.isEmpty()) {
            sb.append("✅ **No concerns found.** The fix for **").append(vuln.cweId())
              .append("** in `").append(fileName).append("` looks correct and complete.\n\n");
            sb.append("Human reviewers have been requested to approve and merge this PR.\n");
        } else {
            sb.append("CB has reviewed the automated fix for **").append(vuln.cweId())
              .append("** in `").append(fileName).append("` and found **")
              .append(findings.size()).append(" concern(s)**:\n\n---\n\n");

            for (int i = 0; i < findings.size(); i++) {
                Map<String, String> f = findings.get(i);
                sb.append("### Finding ").append(i + 1).append(": ")
                  .append(f.getOrDefault("title", "Concern")).append("\n");
                sb.append("**Severity:** ").append(f.getOrDefault("severity", "MEDIUM")).append("  \n");
                sb.append("**Issue:** ").append(f.getOrDefault("description", "")).append("\n\n");
            }

            sb.append("---\n\n## 📋 Review Decision\n\n");
            sb.append("Please select ONE of the following:\n\n");
            sb.append("- [ ] ✅ **ACCEPT** — Findings noted, the fix is acceptable as-is\n");
            sb.append("- [ ] ❌ **REJECT** — Close this PR and re-generate the fix\n\n");
            sb.append("> **If rejecting:** also post a comment starting with `REJECT:` explaining ");
            sb.append("what the new fix should do differently.\n");
            sb.append("> Example: `REJECT: The fix misses the same pattern on line 87`\n\n");
        }

        sb.append("---\n*🤖 Generated by Compliance Buddy · PR #").append(prNumber).append("*");
        return sb.toString();
    }
}
