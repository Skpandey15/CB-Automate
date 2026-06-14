package in.techseva.cb.core.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CodeArtifact(
        @JsonProperty("filePath") String filePath,
        @JsonProperty("language") String language,
        @JsonProperty("content") String content,
        @JsonProperty("startLine") int startLine,
        @JsonProperty("endLine") int endLine
) {}
