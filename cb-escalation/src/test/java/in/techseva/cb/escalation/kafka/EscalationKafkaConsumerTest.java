package in.techseva.cb.escalation.kafka;

import in.techseva.cb.core.domain.Severity;
import in.techseva.cb.core.domain.Vulnerability;
import in.techseva.cb.core.domain.VulnerabilityStatus;
import in.techseva.cb.core.domain.VulnerabilityType;
import in.techseva.cb.core.kafka.EscalationKafkaEvent;
import in.techseva.cb.core.repository.VulnerabilityRepository;
import in.techseva.cb.escalation.service.EscalationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EscalationKafkaConsumerTest {

    @Mock VulnerabilityRepository vulnerabilityRepo;
    @Mock EscalationService escalationService;
    @Mock Acknowledgment ack;

    private Vulnerability vuln() {
        return new Vulnerability("vuln1", "SONAR-001", "CWE-89", Severity.CRITICAL,
                "java:S2078", 42, "30min", VulnerabilityStatus.FAILED,
                "msg", "A03:2021", "my-proj", "my-proj:src/Foo.java", "src/Foo.java",
                null, 3, Instant.now(), Instant.now(), null, null,
                VulnerabilityType.CODE, null, null, null, null, null);
    }

    private EscalationKafkaEvent event() {
        return new EscalationKafkaEvent("vuln1", "SONAR-001", "CWE-89", "CRITICAL",
                "src/Foo.java", 3, List.of("low confidence"), "fix1", Instant.now());
    }

    @Test
    void onEscalation_vulnerabilityFound_delegatesToEscalationServiceAndAcks() {
        Vulnerability vuln = vuln();
        EscalationKafkaEvent event = event();
        when(vulnerabilityRepo.findById("vuln1")).thenReturn(Optional.of(vuln));
        var record = new ConsumerRecord<>("escalations.triggered", 0, 0L, "vuln1", event);

        new EscalationKafkaConsumer(vulnerabilityRepo, escalationService).onEscalation(record, ack);

        verify(escalationService).escalate(vuln, event);
        verify(ack).acknowledge();
    }

    @Test
    void onEscalation_vulnerabilityNotFound_skipsEscalationButStillAcks() {
        EscalationKafkaEvent event = event();
        when(vulnerabilityRepo.findById("vuln1")).thenReturn(Optional.empty());
        var record = new ConsumerRecord<>("escalations.triggered", 0, 0L, "vuln1", event);

        new EscalationKafkaConsumer(vulnerabilityRepo, escalationService).onEscalation(record, ack);

        verify(escalationService, never()).escalate(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(ack).acknowledge();
    }
}
