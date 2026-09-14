package in.techseva.cb.escalation.service;

import in.techseva.cb.core.domain.Severity;
import in.techseva.cb.core.domain.Vulnerability;
import in.techseva.cb.core.domain.VulnerabilityStatus;
import in.techseva.cb.core.domain.VulnerabilityType;
import in.techseva.cb.core.kafka.EscalationKafkaEvent;
import in.techseva.cb.core.service.AuditService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * JIRA_ENABLED and TEAMS_ENABLED both default to false (README's
 * Configuration Reference) — this is the most common real deployment
 * state, and also lets these tests exercise the full escalate() path
 * (including RCA report generation) with no HTTP calls at all.
 */
@ExtendWith(MockitoExtension.class)
class EscalationServiceTest {

    @Mock AuditService auditService;

    private EscalationService serviceWithIntegrationsDisabled() {
        return new EscalationService("", "", "", "CB", false, "", false, auditService);
    }

    private Vulnerability vuln() {
        return new Vulnerability("vuln1", "SONAR-001", "CWE-89", Severity.CRITICAL,
                "java:S2078", 42, "30min", VulnerabilityStatus.FAILED,
                "msg", "A03:2021", "my-proj", "my-proj:src/Foo.java", "src/Foo.java",
                null, 3, Instant.now(), Instant.now(), null, null,
                VulnerabilityType.CODE, null, null, null, null, null);
    }

    private EscalationKafkaEvent event() {
        return new EscalationKafkaEvent("vuln1", "SONAR-001", "CWE-89", "CRITICAL",
                "src/Foo.java", 3, List.of("confidence too low", "build failed"), "fix1", Instant.now());
    }

    @Test
    void escalate_bothIntegrationsDisabled_stillGeneratesRcaAndAudits() {
        serviceWithIntegrationsDisabled().escalate(vuln(), event());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detailsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(
                org.mockito.ArgumentMatchers.eq("vuln1"),
                org.mockito.ArgumentMatchers.eq("Vulnerability"),
                org.mockito.ArgumentMatchers.eq("ESCALATED_BY_AGENT"),
                org.mockito.ArgumentMatchers.eq("escalation-agent"),
                detailsCaptor.capture());

        Map<String, Object> details = detailsCaptor.getValue();
        assertThat(details.get("jiraKey")).isEqualTo("N/A");
        assertThat(details.get("retryCount")).isEqualTo(3);
        assertThat((int) details.get("rcaLength")).isGreaterThan(0);
    }
}
