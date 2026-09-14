package in.techseva.cb.notifier.kafka;

import in.techseva.cb.core.domain.Severity;
import in.techseva.cb.core.domain.Vulnerability;
import in.techseva.cb.core.domain.VulnerabilityStatus;
import in.techseva.cb.core.domain.VulnerabilityType;
import in.techseva.cb.core.kafka.EscalationKafkaEvent;
import in.techseva.cb.core.repository.VulnerabilityRepository;
import in.techseva.cb.notifier.service.JiraTicketService;
import in.techseva.cb.notifier.service.TeamsNotificationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationKafkaConsumerTest {

    @Mock VulnerabilityRepository vulnerabilityRepo;
    @Mock JiraTicketService jiraService;
    @Mock TeamsNotificationService teamsService;
    @Mock Acknowledgment ack;

    private NotificationKafkaConsumer consumer() {
        return new NotificationKafkaConsumer(vulnerabilityRepo, jiraService, teamsService);
    }

    private Vulnerability vuln() {
        return new Vulnerability("vuln1", "SONAR-001", "CWE-89", Severity.CRITICAL,
                "java:S2078", 42, "30min", VulnerabilityStatus.FAILED,
                "msg", "A03:2021", "my-proj", "my-proj:src/Foo.java", "src/Foo.java",
                null, 3, Instant.now(), Instant.now(), null, null,
                VulnerabilityType.CODE, null, null, null, null, null);
    }

    @Test
    void onEscalation_withRejectionReasons_buildsReasonFromThem() {
        Vulnerability vuln = vuln();
        var event = new EscalationKafkaEvent("vuln1", "SONAR-001", "CWE-89", "CRITICAL",
                "src/Foo.java", 3, List.of("low confidence", "build failed"), "fix1", Instant.now());
        when(vulnerabilityRepo.findById("vuln1")).thenReturn(Optional.of(vuln));
        when(jiraService.createEscalationTicket(eq(vuln), any(), eq(3))).thenReturn("CB-42");
        var record = new ConsumerRecord<>("escalations.triggered", 0, 0L, "vuln1", event);

        consumer().onEscalation(record, ack);

        var reasonCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jiraService).createEscalationTicket(eq(vuln), reasonCaptor.capture(), eq(3));
        assertThat(reasonCaptor.getValue()).contains("low confidence").contains("build failed");
        verify(teamsService).sendEscalationAlert(vuln, reasonCaptor.getValue(), "CB-42", 3);
        verify(ack).acknowledge();
    }

    @Test
    void onEscalation_noRejectionReasons_buildsMaxRetriesReason() {
        Vulnerability vuln = vuln();
        var event = new EscalationKafkaEvent("vuln1", "SONAR-001", "CWE-89", "CRITICAL",
                "src/Foo.java", 3, List.of(), "fix1", Instant.now());
        when(vulnerabilityRepo.findById("vuln1")).thenReturn(Optional.of(vuln));
        var record = new ConsumerRecord<>("escalations.triggered", 0, 0L, "vuln1", event);

        consumer().onEscalation(record, ack);

        var reasonCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jiraService).createEscalationTicket(eq(vuln), reasonCaptor.capture(), anyInt());
        assertThat(reasonCaptor.getValue()).contains("Max retries").contains("3");
    }

    @Test
    void onEscalation_vulnerabilityNotFound_skipsNotificationsButStillAcks() {
        var event = new EscalationKafkaEvent("vuln1", "SONAR-001", "CWE-89", "CRITICAL",
                "src/Foo.java", 3, List.of(), "fix1", Instant.now());
        when(vulnerabilityRepo.findById("vuln1")).thenReturn(Optional.empty());
        var record = new ConsumerRecord<>("escalations.triggered", 0, 0L, "vuln1", event);

        consumer().onEscalation(record, ack);

        verifyNoInteractions(jiraService, teamsService);
        verify(ack).acknowledge();
    }
}
