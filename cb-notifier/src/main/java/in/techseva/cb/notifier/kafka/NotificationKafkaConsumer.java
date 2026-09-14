package in.techseva.cb.notifier.kafka;

import in.techseva.cb.core.events.EscalationEvent;
import in.techseva.cb.core.kafka.EscalationKafkaEvent;
import in.techseva.cb.core.kafka.KafkaTopics;
import in.techseva.cb.core.repository.VulnerabilityRepository;
import in.techseva.cb.notifier.service.NotifierService;
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
    private final NotifierService notifierService;

    public NotificationKafkaConsumer(VulnerabilityRepository vulnerabilityRepo,
                                      NotifierService notifierService) {
        this.vulnerabilityRepo = vulnerabilityRepo;
        this.notifierService = notifierService;
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
                notifierService.onEscalation(new EscalationEvent(this, vuln, null, reason));
                log.info("Escalation notification dispatched: vuln={}", event.vulnerabilityId());
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
