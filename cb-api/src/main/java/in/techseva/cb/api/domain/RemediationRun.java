package in.techseva.cb.api.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.util.List;

/**
 * Tracks one invocation of scripts/dependency-remediation/remediate.py
 * triggered via POST /api/v1/remediation-runs (ADR-0002 Track A). Kept
 * local to cb-api rather than cb-core: unlike Vulnerability/Fix, this is
 * not part of the Kafka-consumed pipeline other services react to — it's
 * purely cb-api's own record of a job it ran.
 */
@Document(collection = "cb_remediation_runs")
public record RemediationRun(
        @Id
        String runId,

        @Field("repoUrl") @JsonProperty("repoUrl")
        String repoUrl,

        @Field("branch") @JsonProperty("branch")
        String branch,

        @Field("publish") @JsonProperty("publish")
        boolean publish,

        @Field("recipients") @JsonProperty("recipients")
        List<String> recipients,

        @Field("status") @JsonProperty("status")
        RemediationRunStatus status,

        @Field("reportPath") @JsonProperty("reportPath")
        String reportPath,

        @Field("message") @JsonProperty("message")
        String message,

        @Field("createdAt") @JsonProperty("createdAt")
        Instant createdAt,

        @Field("completedAt") @JsonProperty("completedAt")
        Instant completedAt
) {
    public static RemediationRun starting(String runId, String repoUrl, String branch,
                                           boolean publish, List<String> recipients) {
        return new RemediationRun(runId, repoUrl, branch, publish, recipients,
                RemediationRunStatus.RUNNING, null, null, Instant.now(), null);
    }

    public RemediationRun completed(RemediationRunStatus finalStatus, String reportPath, String message) {
        return new RemediationRun(runId, repoUrl, branch, publish, recipients,
                finalStatus, reportPath, message, createdAt, Instant.now());
    }
}
