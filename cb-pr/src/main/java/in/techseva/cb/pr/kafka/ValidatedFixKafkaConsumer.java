package in.techseva.cb.pr.kafka;

import in.techseva.cb.core.events.FixValidatedEvent;
import in.techseva.cb.core.kafka.FixKafkaEvent;
import in.techseva.cb.core.kafka.KafkaTopics;
import in.techseva.cb.core.repository.FixRepository;
import in.techseva.cb.core.repository.VulnerabilityRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class ValidatedFixKafkaConsumer {

    private static final Logger log = LoggerFactory.getLogger(ValidatedFixKafkaConsumer.class);

    private final FixRepository fixRepo;
    private final VulnerabilityRepository vulnerabilityRepo;
    private final ApplicationEventPublisher eventPublisher;

    public ValidatedFixKafkaConsumer(FixRepository fixRepo,
                                      VulnerabilityRepository vulnerabilityRepo,
                                      ApplicationEventPublisher eventPublisher) {
        this.fixRepo = fixRepo;
        this.vulnerabilityRepo = vulnerabilityRepo;
        this.eventPublisher = eventPublisher;
    }

    @KafkaListener(
        topics = KafkaTopics.FIXES_VALIDATED,
        groupId = "${KAFKA_PR_GROUP_ID:cb-pr-group}",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void onFixValidated(ConsumerRecord<String, FixKafkaEvent> record, Acknowledgment ack) {
        FixKafkaEvent event = record.value();
        log.info("Received validated fix from Kafka: fixId={} vulnId={}", event.fixId(), event.vulnerabilityId());

        fixRepo.findById(event.fixId()).ifPresentOrElse(
            fix -> vulnerabilityRepo.findById(event.vulnerabilityId()).ifPresentOrElse(
                vuln -> {
                    eventPublisher.publishEvent(new FixValidatedEvent(this, vuln, fix));
                    log.debug("Dispatched FixValidatedEvent for fix={}", fix.id());
                },
                () -> log.warn("Vulnerability {} not found for fix {}", event.vulnerabilityId(), event.fixId())
            ),
            () -> log.warn("Fix {} not found in MongoDB", event.fixId())
        );

        ack.acknowledge();
    }
}
