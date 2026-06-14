package in.techseva.cb.notifier.service;

import in.techseva.cb.core.events.EscalationEvent;
import in.techseva.cb.core.events.PRRaisedEvent;
import in.techseva.cb.core.service.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class NotifierService {

    private static final Logger log = LoggerFactory.getLogger(NotifierService.class);

    private final EmailNotificationService emailService;
    private final DatadogEventService datadogService;
    private final AuditService auditService;

    public NotifierService(EmailNotificationService emailService,
                           DatadogEventService datadogService,
                           AuditService auditService) {
        this.emailService = emailService;
        this.datadogService = datadogService;
        this.auditService = auditService;
    }

    @Async("cbNotifierExecutor")
    @EventListener
    public void onPRRaised(PRRaisedEvent event) {
        log.info("Notifier processing PRRaisedEvent for vuln={}", event.getVulnerability().id());
        try {
            emailService.sendPrRaisedEmail(event.getVulnerability(), event.getFix());
            datadogService.postPrRaisedEvent(event.getVulnerability(), event.getFix());
            auditService.log(event.getFix().id(), "Fix", "NOTIFICATION_SENT", "notifier",
                    Map.of("channel", "email+datadog", "type", "pr-raised"));
        } catch (Exception e) {
            log.error("Notification failed for PRRaisedEvent: {}", e.getMessage(), e);
        }
    }

    @Async("cbNotifierExecutor")
    @EventListener
    public void onEscalation(EscalationEvent event) {
        log.info("Notifier processing EscalationEvent for vuln={}", event.getVulnerability().id());
        try {
            emailService.sendEscalationEmail(event.getVulnerability(), event.getFix(), event.getReason());
            datadogService.postEscalationEvent(event.getVulnerability(), event.getReason());
            auditService.log(event.getVulnerability().id(), "Vulnerability", "ESCALATION_NOTIFIED",
                    "notifier", Map.of("reason", event.getReason()));
        } catch (Exception e) {
            log.error("Escalation notification failed: {}", e.getMessage(), e);
        }
    }
}
