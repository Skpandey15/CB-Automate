package in.techseva.cb.scanner.mapper;

import in.techseva.cb.core.domain.Severity;
import in.techseva.cb.core.domain.Vulnerability;
import in.techseva.cb.core.domain.VulnerabilityStatus;
import in.techseva.cb.scanner.client.SonarQubeClient.SonarIssue;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

@Component
public class SonarIssueMapper {

    private static final Map<String, String> RULE_TO_CWE = new java.util.HashMap<>(Map.of(
            "java:S2078", "CWE-89",
            "java:S2076", "CWE-78",
            "java:S2083", "CWE-22",
            "java:S2068", "CWE-798",
            "java:S4790", "CWE-327",
            "java:S5144", "CWE-918",
            "java:S2245", "CWE-330",
            "java:S5131", "CWE-79"
    ));

    private static final Map<String, String> RULE_TO_OWASP = new java.util.HashMap<>(Map.of(
            "java:S2078", "A03:2021",
            "java:S2076", "A03:2021",
            "java:S2083", "A01:2021",
            "java:S2068", "A07:2021",
            "java:S4790", "A02:2021",
            "java:S5144", "A10:2021",
            "java:S2245", "A02:2021",
            "java:S5131", "A03:2021"
    ));

    static {
        // Code quality rules
        RULE_TO_CWE.put("java:S1192", "CWE-1078");
        RULE_TO_CWE.put("java:S6213", "CWE-1041");
        RULE_TO_CWE.put("java:S1481", "CWE-563");
        RULE_TO_CWE.put("java:S1172", "CWE-561");
        RULE_TO_CWE.put("java:S1068", "CWE-561");
        RULE_TO_CWE.put("java:S125",  "CWE-1071");
        RULE_TO_OWASP.put("java:S1192", "A04:2021");
        RULE_TO_OWASP.put("java:S6213", "A04:2021");
    }

    public Vulnerability toVulnerability(SonarIssue issue, String projectKey) {
        String filePath = extractFilePath(issue.component(), projectKey);
        return new Vulnerability(
                null,
                issue.key(),
                RULE_TO_CWE.getOrDefault(issue.rule(), "CWE-UNKNOWN"),
                parseSeverity(issue.severity()),
                issue.rule(),
                issue.line() != null ? issue.line() : 0,
                issue.effort(),
                VulnerabilityStatus.DETECTED,
                issue.message(),
                RULE_TO_OWASP.getOrDefault(issue.rule(), "UNKNOWN"),
                projectKey,
                issue.component(),
                filePath,
                null,
                0,
                Instant.now(),
                Instant.now(),
                null,
                null
        );
    }

    private Severity parseSeverity(String raw) {
        if (raw == null) return Severity.MINOR;
        return switch (raw.toUpperCase()) {
            case "BLOCKER" -> Severity.BLOCKER;
            case "CRITICAL" -> Severity.CRITICAL;
            case "MAJOR" -> Severity.MAJOR;
            case "MINOR" -> Severity.MINOR;
            default -> Severity.INFO;
        };
    }

    private String extractFilePath(String component, String projectKey) {
        if (component == null) return "";
        // component format: projectKey:src/main/java/...
        int colonIdx = component.indexOf(':');
        return colonIdx >= 0 ? component.substring(colonIdx + 1) : component;
    }
}
