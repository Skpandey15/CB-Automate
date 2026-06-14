package in.techseva.cb.patcher.kafka;

import in.techseva.cb.core.domain.Fix;
import in.techseva.cb.core.domain.Vulnerability;
import in.techseva.cb.core.events.FixGeneratedEvent;
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
public class FixKafkaConsumer {

    private static final Logger log = LoggerFactory.getLogger(FixKafkaConsumer.class);

    private final FixRepository fixRepo;
    private final VulnerabilityRepository vulnerabilityRepo;
    private final ApplicationEventPublisher eventPublisher;

    public FixKafkaConsumer(FixRepository fixRepo,
                             VulnerabilityRepository vulnerabilityRepo,
                             ApplicationEventPublisher eventPublisher) {
        this.fixRepo = fixRepo;
        this.vulnerabilityRepo = vulnerabilityRepo;
        this.eventPublisher = eventPublisher;
    }

    @KafkaListener(
        topics = KafkaTopics.FIXES_GENERATED,
        groupId = "${KAFKA_PATCHER_GROUP_ID:cb-patcher-group}",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void onFixGenerated(ConsumerRecord<String, FixKafkaEvent> record, Acknowledgment ack) {
        FixKafkaEvent event = record.value();
        log.info("Received fix from Kafka: fixId={} vulnId={} model={} confidence={}",
                event.fixId(), event.vulnerabilityId(), event.llmModel(), event.confidence());

        fixRepo.findById(event.fixId()).ifPresentOrElse(
            fix -> vulnerabilityRepo.findById(event.vulnerabilityId()).ifPresentOrElse(
                vuln -> {
                    eventPublisher.publishEvent(new FixGeneratedEvent(this, vuln, fix));
                    log.debug("Dispatched FixGeneratedEvent for fix={}", fix.id());
                },
                () -> log.warn("Vulnerability {} not found for fix {}", event.vulnerabilityId(), event.fixId())
            ),
            () -> log.warn("Fix {} not found in MongoDB — Kafka event may have arrived before save", event.fixId())
        );

        ack.acknowledge();
    }
}
