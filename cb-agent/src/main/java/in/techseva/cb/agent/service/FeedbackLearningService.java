package in.techseva.cb.agent.service;

import in.techseva.cb.core.kafka.KafkaTopics;
import in.techseva.cb.core.kafka.ReviewFeedbackKafkaEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Listens to review.feedback Kafka topic and indexes ACCEPTED fixes into Qdrant
 * for future RAG retrieval, improving fix quality over time.
 */
@Service
public class FeedbackLearningService {

    private static final Logger log = LoggerFactory.getLogger(FeedbackLearningService.class);

    private final VectorStore vectorStore;

    public FeedbackLearningService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @KafkaListener(
        topics = KafkaTopics.REVIEW_FEEDBACK,
        groupId = "${KAFKA_FEEDBACK_GROUP_ID:cb-feedback-learning-group}",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void onFeedback(ConsumerRecord<String, ReviewFeedbackKafkaEvent> record,
                           Acknowledgment ack) {
        ReviewFeedbackKafkaEvent event = record.value();

        if ("ACCEPTED".equals(event.decision())) {
            indexAcceptedFix(event);
        } else {
            log.debug("Skipping REJECTED feedback for fix={} — not indexing to Qdrant", event.fixId());
        }

        ack.acknowledge();
    }

    private void indexAcceptedFix(ReviewFeedbackKafkaEvent event) {
        String content = """
                CWE: %s
                Strategy: %s
                Patch:
                %s
                """.formatted(event.cweId(), event.strategy(), event.patchDiff());

        Document doc = new Document(
                content,
                Map.of(
                    "fixId", event.fixId(),
                    "vulnerabilityId", event.vulnerabilityId(),
                    "cweId", event.cweId(),
                    "strategy", event.strategy() != null ? event.strategy() : "",
                    "decision", "ACCEPTED",
                    "decidedAt", event.decidedAt() != null ? event.decidedAt().toString() : ""
                )
        );

        vectorStore.add(List.of(doc));
        log.info("Indexed accepted fix={} cwe={} into Qdrant for future retrieval",
                event.fixId(), event.cweId());
    }
}
