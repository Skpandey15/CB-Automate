package in.techseva.cb.patcher.kafka;

import in.techseva.cb.core.domain.Fix;
import in.techseva.cb.core.domain.Vulnerability;
import in.techseva.cb.core.kafka.FixKafkaEvent;
import in.techseva.cb.core.kafka.KafkaTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class ValidatedFixKafkaPublisher {

    private static final Logger log = LoggerFactory.getLogger(ValidatedFixKafkaPublisher.class);

    private final KafkaTemplate<String, FixKafkaEvent> kafkaTemplate;

    public ValidatedFixKafkaPublisher(KafkaTemplate<String, FixKafkaEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishValidated(Fix fix, Vulnerability vuln) {
        var event = new FixKafkaEvent(
                fix.id(),
                vuln.id(),
                vuln.cweId(),
                fix.strategy(),
                fix.confidence(),
                fix.llmModel(),
                fix.patchDiff(),
                fix.explanation(),
                "VALIDATED",
                Instant.now()
        );
        kafkaTemplate.send(KafkaTopics.FIXES_VALIDATED, fix.id(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish validated fix {} to Kafka: {}", fix.id(), ex.getMessage());
                    } else {
                        log.debug("Published validated fix {} to {}", fix.id(), KafkaTopics.FIXES_VALIDATED);
                    }
                });
    }
}
