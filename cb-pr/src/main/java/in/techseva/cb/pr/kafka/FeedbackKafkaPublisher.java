package in.techseva.cb.pr.kafka;

import in.techseva.cb.core.domain.Fix;
import in.techseva.cb.core.domain.Vulnerability;
import in.techseva.cb.core.kafka.KafkaTopics;
import in.techseva.cb.core.kafka.ReviewFeedbackKafkaEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class FeedbackKafkaPublisher {

    private static final Logger log = LoggerFactory.getLogger(FeedbackKafkaPublisher.class);

    private final KafkaTemplate<String, ReviewFeedbackKafkaEvent> kafkaTemplate;

    public FeedbackKafkaPublisher(KafkaTemplate<String, ReviewFeedbackKafkaEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishAccepted(Fix fix, Vulnerability vuln) {
        publish(fix, vuln, "ACCEPTED", "PR merged by human reviewer");
    }

    public void publishRejected(Fix fix, Vulnerability vuln, List<String> opaViolations) {
        String reason = opaViolations.isEmpty()
                ? "OPA governance rejected the patch"
                : "OPA violations: " + String.join("; ", opaViolations);
        publish(fix, vuln, "REJECTED", reason);
    }

    private void publish(Fix fix, Vulnerability vuln, String decision, String reason) {
        var event = new ReviewFeedbackKafkaEvent(
                fix.id(),
                vuln.id(),
                vuln.cweId(),
                decision,
                reason,
                fix.patchDiff(),
                fix.strategy(),
                Instant.now()
        );
        kafkaTemplate.send(KafkaTopics.REVIEW_FEEDBACK, fix.id(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish {} feedback for fix {}: {}", decision, fix.id(), ex.getMessage());
                    } else {
                        log.debug("Published {} feedback for fix={}", decision, fix.id());
                    }
                });
    }
}
