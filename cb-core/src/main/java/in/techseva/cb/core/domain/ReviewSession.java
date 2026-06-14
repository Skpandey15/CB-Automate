package in.techseva.cb.core.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * Tracks the CB self-review posted on each PR.
 * githubCommentId is the ID of the comment with ACCEPT/REJECT checkboxes.
 * status: PENDING | NO_FINDINGS | ACCEPTED | REJECTED
 */
@Document("cb_review_sessions")
public record ReviewSession(
        @Id String id,
        @Indexed @Field("vulnerabilityId") @JsonProperty("vulnerabilityId") String vulnerabilityId,
        @Field("prNumber") @JsonProperty("prNumber") int prNumber,
        @Field("prUrl") @JsonProperty("prUrl") String prUrl,
        @Field("githubCommentId") @JsonProperty("githubCommentId") Long githubCommentId,
        @Field("status") @JsonProperty("status") String status,
        @Field("cbFindingsCount") @JsonProperty("cbFindingsCount") int cbFindingsCount,
        @Field("createdAt") @JsonProperty("createdAt") Instant createdAt
) {
    public ReviewSession withStatus(String newStatus) {
        return new ReviewSession(id, vulnerabilityId, prNumber, prUrl,
                githubCommentId, newStatus, cbFindingsCount, createdAt);
    }
}
