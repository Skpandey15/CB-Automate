package in.techseva.cb.core.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

@Document("cb_review_feedbacks")
public record ReviewFeedback(
        @Id String id,
        @Indexed @Field("vulnerabilityId") @JsonProperty("vulnerabilityId") String vulnerabilityId,
        @Field("prNumber") @JsonProperty("prNumber") int prNumber,
        @Field("rejectionReason") @JsonProperty("rejectionReason") String rejectionReason,
        @Field("processed") @JsonProperty("processed") boolean processed,
        @Field("createdAt") @JsonProperty("createdAt") Instant createdAt
) {
    public ReviewFeedback withProcessed() {
        return new ReviewFeedback(id, vulnerabilityId, prNumber, rejectionReason, true, createdAt);
    }
}
