package in.techseva.cb.agent.kafka;

import in.techseva.cb.core.domain.Fix;
import in.techseva.cb.core.kafka.FixKafkaEvent;
import in.techseva.cb.core.kafka.KafkaTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class FixKafkaPublisher {

    private static final Logger log = LoggerFactory.getLogger(FixKafkaPublisher.class);

    private final KafkaTemplate<String, FixKafkaEvent> kafkaTemplate;

    public FixKafkaPublisher(KafkaTemplate<String, FixKafkaEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(Fix fix, String vulnerabilityId, String cweId) {
        var event = new FixKafkaEvent(
                fix.id(),
                vulnerabilityId,
                cweId != null ? cweId : "UNKNOWN",
                fix.strategy(),
                fix.confidence(),
                fix.llmModel(),
                fix.patchDiff(),
                fix.explanation(),
                fix.status() != null ? fix.status().name() : "PENDING",
                Instant.now()
        );
        kafkaTemplate.send(KafkaTopics.FIXES_GENERATED, fix.id(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish fix {} to Kafka: {}", fix.id(), ex.getMessage());
                    } else {
                        log.debug("Published fix {} to {} partition {} offset {}",
                                fix.id(), KafkaTopics.FIXES_GENERATED,
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
