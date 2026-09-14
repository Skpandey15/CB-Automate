package in.techseva.cb.notifier.kafka;

import in.techseva.cb.core.domain.Severity;
import in.techseva.cb.core.domain.Vulnerability;
import in.techseva.cb.core.domain.VulnerabilityStatus;
import in.techseva.cb.core.domain.VulnerabilityType;
import in.techseva.cb.core.events.EscalationEvent;
import in.techseva.cb.core.kafka.EscalationKafkaEvent;
import in.techseva.cb.core.repository.VulnerabilityRepository;
import in.techseva.cb.notifier.service.NotifierService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationKafkaConsumerTest {

    @Mock VulnerabilityRepository vulnerabilityRepo;
    @Mock NotifierService notifierService;
    @Mock Acknowledgment ack;

    private NotificationKafkaConsumer consumer() {
        return new NotificationKafkaConsumer(vulnerabilityRepo, notifierService);
    }

    private Vulnerability vuln() {
        return new Vulnerability("vuln1", "SONAR-001", "CWE-89", Severity.CRITICAL,
                "java:S2078", 42, "30min", VulnerabilityStatus.FAILED,
                "msg", "A03:2021", "my-proj", "my-proj:src/Foo.java", "src/Foo.java",
                null, 3, Instant.now(), Instant.now(), null, null,
                VulnerabilityType.CODE, null, null, null, null, null);
    }

    @Test
    void onEscalation_withRejectionReasons_notifiesWithReasonBuiltFromThem() {
        Vulnerability vuln = vuln();
        var event = new EscalationKafkaEvent("vuln1", "SONAR-001", "CWE-89", "CRITICAL",
                "src/Foo.java", 3, List.of("low confidence", "build failed"), "fix1", Instant.now());
        when(vulnerabilityRepo.findById("vuln1")).thenReturn(Optional.of(vuln));
        var record = new ConsumerRecord<>("escalations.triggered", 0, 0L, "vuln1", event);

        consumer().onEscalation(record, ack);

        ArgumentCaptor<EscalationEvent> captor = ArgumentCaptor.forClass(EscalationEvent.class);
        verify(notifierService).onEscalation(captor.capture());
        EscalationEvent notified = captor.getValue();
        assertThat(notified.getVulnerability()).isEqualTo(vuln);
        assertThat(notified.getReason()).contains("low confidence").contains("build failed");
        verify(ack).acknowledge();
    }

    @Test
    void onEscalation_noRejectionReasons_notifiesWithMaxRetriesReason() {
        Vulnerability vuln = vuln();
        var event = new EscalationKafkaEvent("vuln1", "SONAR-001", "CWE-89", "CRITICAL",
                "src/Foo.java", 3, List.of(), "fix1", Instant.now());
        when(vulnerabilityRepo.findById("vuln1")).thenReturn(Optional.of(vuln));
        var record = new ConsumerRecord<>("escalations.triggered", 0, 0L, "vuln1", event);

        consumer().onEscalation(record, ack);

        ArgumentCaptor<EscalationEvent> captor = ArgumentCaptor.forClass(EscalationEvent.class);
        verify(notifierService).onEscalation(captor.capture());
        assertThat(captor.getValue().getReason()).contains("Max retries").contains("3");
    }

    @Test
    void onEscalation_vulnerabilityNotFound_skipsNotificationButStillAcks() {
        var event = new EscalationKafkaEvent("vuln1", "SONAR-001", "CWE-89", "CRITICAL",
                "src/Foo.java", 3, List.of(), "fix1", Instant.now());
        when(vulnerabilityRepo.findById("vuln1")).thenReturn(Optional.empty());
        var record = new ConsumerRecord<>("escalations.triggered", 0, 0L, "vuln1", event);

        consumer().onEscalation(record, ack);

        verifyNoInteractions(notifierService);
        verify(ack).acknowledge();
    }
}
