package in.techseva.cb.scanner.dependency;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Client for deps.dev (Google Open Source Insights) API.
 * Free, no auth required. Returns advisories for Maven coordinates.
 * Docs: https://deps.dev/api
 */
@Component
public class DepsDevClient {

    private static final Logger log = LoggerFactory.getLogger(DepsDevClient.class);
    private static final String BASE_URL = "https://api.deps.dev/v3alpha/systems/maven/packages";

    private final RestClient restClient;

    public DepsDevClient() {
        this.restClient = RestClient.builder().build();
    }

    /**
     * Returns a list of advisory reports for the given dependency, or empty if none found.
     */
    public List<Advisory> getAdvisories(String groupId, String artifactId, String version) {
        try {
            // URL: /v3alpha/systems/maven/packages/{package}/versions/{version}:advisories
            String pkg = groupId + ":" + artifactId;
            String url = UriComponentsBuilder.fromHttpUrl(BASE_URL)
                    .pathSegment(pkg, "versions", version + ":advisories")
                    .build().toUriString();

            AdvisoryResponse response = restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(AdvisoryResponse.class);

            if (response == null || response.advisories() == null) return Collections.emptyList();
            return response.advisories();
        } catch (Exception e) {
            log.debug("deps.dev lookup: {}:{}:{} — {}", groupId, artifactId, version, e.getMessage());
            return Collections.emptyList();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AdvisoryResponse(
            @JsonProperty("advisories") List<Advisory> advisories) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Advisory(
            @JsonProperty("advisoryKey") AdvisoryKey advisoryKey,
            @JsonProperty("url")         String url,
            @JsonProperty("title")       String title,
            @JsonProperty("aliases")     List<String> aliases,
            @JsonProperty("cvss3Score")  Double cvss3Score,
            @JsonProperty("cvss3Vector") String cvss3Vector) {

        public String cveId() {
            if (aliases == null) return null;
            return aliases.stream()
                    .filter(a -> a.startsWith("CVE-"))
                    .findFirst()
                    .orElse(null);
        }

        public String id() {
            return advisoryKey != null ? advisoryKey.id() : null;
        }

        public double severity() {
            return cvss3Score != null ? cvss3Score : 0.0;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AdvisoryKey(@JsonProperty("id") String id) {}
}
