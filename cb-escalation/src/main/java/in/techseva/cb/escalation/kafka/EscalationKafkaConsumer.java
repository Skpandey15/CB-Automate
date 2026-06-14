package in.techseva.cb.escalation.kafka;

import in.techseva.cb.core.kafka.EscalationKafkaEvent;
import in.techseva.cb.core.kafka.KafkaTopics;
import in.techseva.cb.core.repository.VulnerabilityRepository;
import in.techseva.cb.escalation.service.EscalationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class EscalationKafkaConsumer {

    private static final Logger log = LoggerFactory.getLogger(EscalationKafkaConsumer.class);

    private final VulnerabilityRepository vulnerabilityRepo;
    private final EscalationService escalationService;

    public EscalationKafkaConsumer(VulnerabilityRepository vulnerabilityRepo,
                                    EscalationService escalationService) {
        this.vulnerabilityRepo = vulnerabilityRepo;
        this.escalationService = escalationService;
    }

    @KafkaListener(
        topics = KafkaTopics.ESCALATIONS_TRIGGERED,
        groupId = "${KAFKA_ESCALATION_GROUP_ID:cb-escalation-group}",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void onEscalation(ConsumerRecord<String, EscalationKafkaEvent> record, Acknowledgment ack) {
        EscalationKafkaEvent event = record.value();
        log.info("EscalationAgent: received escalation vulnId={} cwe={} retryCount={}",
                event.vulnerabilityId(), event.cweId(), event.retryCount());

        vulnerabilityRepo.findById(event.vulnerabilityId()).ifPresentOrElse(
            vuln -> escalationService.escalate(vuln, event),
            () -> log.warn("Vulnerability {} not found for escalation", event.vulnerabilityId())
        );

        ack.acknowledge();
    }
}
