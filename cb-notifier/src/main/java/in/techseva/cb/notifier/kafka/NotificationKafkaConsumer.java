package in.techseva.cb.notifier.kafka;

import in.techseva.cb.core.kafka.EscalationKafkaEvent;
import in.techseva.cb.core.kafka.KafkaTopics;
import in.techseva.cb.core.repository.VulnerabilityRepository;
import in.techseva.cb.notifier.service.JiraTicketService;
import in.techseva.cb.notifier.service.TeamsNotificationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class NotificationKafkaConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationKafkaConsumer.class);

    private final VulnerabilityRepository vulnerabilityRepo;
    private final JiraTicketService jiraService;
    private final TeamsNotificationService teamsService;

    public NotificationKafkaConsumer(VulnerabilityRepository vulnerabilityRepo,
                                      JiraTicketService jiraService,
                                      TeamsNotificationService teamsService) {
        this.vulnerabilityRepo = vulnerabilityRepo;
        this.jiraService = jiraService;
        this.teamsService = teamsService;
    }

    @KafkaListener(
        topics = KafkaTopics.ESCALATIONS_TRIGGERED,
        groupId = "${KAFKA_NOTIFIER_GROUP_ID:cb-notifier-group}",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void onEscalation(ConsumerRecord<String, EscalationKafkaEvent> record, Acknowledgment ack) {
        EscalationKafkaEvent event = record.value();
        log.info("Received escalation event: vulnId={} cwe={} retryCount={}",
                event.vulnerabilityId(), event.cweId(), event.retryCount());

        vulnerabilityRepo.findById(event.vulnerabilityId()).ifPresentOrElse(
            vuln -> {
                String reason = buildReason(event);
                String jiraKey = jiraService.createEscalationTicket(vuln, reason, event.retryCount());
                teamsService.sendEscalationAlert(vuln, reason, jiraKey, event.retryCount());
                log.info("Escalation processed: vuln={} jira={}", event.vulnerabilityId(), jiraKey);
            },
            () -> log.warn("Vulnerability {} not found for escalation event", event.vulnerabilityId())
        );

        ack.acknowledge();
    }

    private String buildReason(EscalationKafkaEvent event) {
        if (event.rejectionReasons() != null && !event.rejectionReasons().isEmpty()) {
            return "Rejected " + event.retryCount() + " times. Last reasons: " +
                    String.join("; ", event.rejectionReasons());
        }
        return "Max retries (" + event.retryCount() + ") exceeded";
    }
}
