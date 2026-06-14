package in.techseva.cb.core.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.util.Map;

@Document(collection = "cb_audit_events")
public record AuditEvent(
        @Id
        String id,

        @Indexed
        @Field("entityId")
        @JsonProperty("entityId")
        String entityId,

        @Field("entityType")
        @JsonProperty("entityType")
        String entityType,

        @Field("action")
        @JsonProperty("action")
        String action,

        @Field("actor")
        @JsonProperty("actor")
        String actor,

        @Field("details")
        @JsonProperty("details")
        Map<String, Object> details,

        @Indexed(expireAfterSeconds = 7776000) // TTL 90 days
        @Field("occurredAt")
        @JsonProperty("occurredAt")
        Instant occurredAt
) {
    public static AuditEvent of(String entityId, String entityType, String action,
                                String actor, Map<String, Object> details) {
        return new AuditEvent(null, entityId, entityType, action, actor, details, Instant.now());
    }
}
