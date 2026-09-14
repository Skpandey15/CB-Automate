package in.techseva.cb.notifier.service;

import in.techseva.cb.core.domain.Fix;
import in.techseva.cb.core.domain.FixStatus;
import in.techseva.cb.core.domain.Severity;
import in.techseva.cb.core.domain.Vulnerability;
import in.techseva.cb.core.domain.VulnerabilityStatus;
import in.techseva.cb.core.domain.VulnerabilityType;
import in.techseva.cb.core.events.EscalationEvent;
import in.techseva.cb.core.events.PRRaisedEvent;
import in.techseva.cb.core.service.AuditService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class NotifierServiceTest {

    @Mock EmailNotificationService emailService;
    @Mock DatadogEventService datadogService;
    @Mock AuditService auditService;

    private NotifierService service() {
        return new NotifierService(emailService, datadogService, auditService);
    }

    private Vulnerability vuln() {
        return new Vulnerability("vuln1", "SONAR-001", "CWE-89", Severity.CRITICAL,
                "java:S2078", 42, "30min", VulnerabilityStatus.PR_RAISED,
                "msg", "A03:2021", "my-proj", "my-proj:src/Foo.java", "src/Foo.java",
                null, 0, Instant.now(), Instant.now(), null, null,
                VulnerabilityType.CODE, null, null, null, null, null);
    }

    private Fix fix() {
        return new Fix("fix1", "vuln1", "--- a\n+++ b\n", null, "parameterize",
                0.9, "gpt-4o", "explanation", "https://github.com/x/y/pull/1", 1, "cb/fix-1", null,
                FixStatus.PR_OPEN, true, "build ok", 100, Instant.now());
    }

    @Test
    void onPRRaised_notifiesEmailAndDatadogThenAudits() {
        Vulnerability vuln = vuln();
        Fix fix = fix();
        var event = new PRRaisedEvent(this, vuln, fix);

        service().onPRRaised(event);

        verify(emailService).sendPrRaisedEmail(vuln, fix);
        verify(datadogService).postPrRaisedEvent(vuln, fix);
        verify(auditService).log(org.mockito.ArgumentMatchers.eq("fix1"), org.mockito.ArgumentMatchers.eq("Fix"),
                org.mockito.ArgumentMatchers.eq("NOTIFICATION_SENT"), org.mockito.ArgumentMatchers.eq("notifier"), any());
    }

    @Test
    void onPRRaised_emailThrows_doesNotPropagateAndSkipsDownstreamCalls() {
        var event = new PRRaisedEvent(this, vuln(), fix());
        doThrow(new RuntimeException("smtp down")).when(emailService).sendPrRaisedEmail(any(), any());

        service().onPRRaised(event); // must not throw

        verifyNoInteractions(datadogService);
        verify(auditService, org.mockito.Mockito.never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void onEscalation_notifiesEmailAndDatadogThenAudits() {
        Vulnerability vuln = vuln();
        Fix fix = fix();
        var event = new EscalationEvent(this, vuln, fix, "build failed 3x");

        service().onEscalation(event);

        verify(emailService).sendEscalationEmail(vuln, fix, "build failed 3x");
        verify(datadogService).postEscalationEvent(vuln, "build failed 3x");
        verify(auditService).log(org.mockito.ArgumentMatchers.eq("vuln1"), org.mockito.ArgumentMatchers.eq("Vulnerability"),
                org.mockito.ArgumentMatchers.eq("ESCALATION_NOTIFIED"), org.mockito.ArgumentMatchers.eq("notifier"), any());
    }

    @Test
    void onEscalation_datadogThrows_doesNotPropagate() {
        var event = new EscalationEvent(this, vuln(), fix(), "build failed 3x");
        doThrow(new RuntimeException("datadog down")).when(datadogService).postEscalationEvent(any(), any());

        service().onEscalation(event); // must not throw

        verify(auditService, org.mockito.Mockito.never()).log(any(), any(), any(), any(), any());
    }
}
