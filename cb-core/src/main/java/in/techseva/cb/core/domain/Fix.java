package in.techseva.cb.core.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

@Document(collection = "cb_fixes")
public record Fix(
        @Id
        String id,

        @Indexed
        @Field("vulnerabilityId")
        @JsonProperty("vulnerabilityId")
        String vulnerabilityId,

        @Field("patchDiff")
        @JsonProperty("patchDiff")
        String patchDiff,

        @Field("gradlePatch")
        @JsonProperty("gradlePatch")
        String gradlePatch,

        @Field("strategy")
        @JsonProperty("strategy")
        String strategy,

        @Field("confidence")
        @JsonProperty("confidence")
        double confidence,

        @Field("llmModel")
        @JsonProperty("llmModel")
        String llmModel,

        @Field("explanation")
        @JsonProperty("explanation")
        String explanation,

        @Field("prUrl")
        @JsonProperty("prUrl")
        String prUrl,

        @Field("prNumber")
        @JsonProperty("prNumber")
        Integer prNumber,

        @Field("branchName")
        @JsonProperty("branchName")
        String branchName,

        @Field("version1TaskId")
        @JsonProperty("version1TaskId")
        String version1TaskId,

        @Field("status")
        @JsonProperty("status")
        FixStatus status,

        @Field("buildValidated")
        @JsonProperty("buildValidated")
        boolean buildValidated,

        @Field("buildLog")
        @JsonProperty("buildLog")
        String buildLog,

        @Field("tokensUsed")
        @JsonProperty("tokensUsed")
        int tokensUsed,

        @Field("generatedAt")
        @JsonProperty("generatedAt")
        Instant generatedAt
) {
    public Fix withStatus(FixStatus newStatus) {
        return new Fix(id, vulnerabilityId, patchDiff, gradlePatch, strategy, confidence,
                llmModel, explanation, prUrl, prNumber, branchName, version1TaskId,
                newStatus, buildValidated, buildLog, tokensUsed, generatedAt);
    }

    public Fix withBuildResult(boolean validated, String log) {
        return new Fix(id, vulnerabilityId, patchDiff, gradlePatch, strategy, confidence,
                llmModel, explanation, prUrl, prNumber, branchName, version1TaskId,
                validated ? FixStatus.BUILD_VALIDATED : FixStatus.BUILD_FAILED,
                validated, log, tokensUsed, generatedAt);
    }

    public Fix withPrDetails(String url, int number, String branch) {
        return new Fix(id, vulnerabilityId, patchDiff, gradlePatch, strategy, confidence,
                llmModel, explanation, url, number, branch, version1TaskId,
                FixStatus.PR_OPEN, buildValidated, buildLog, tokensUsed, generatedAt);
    }
}
