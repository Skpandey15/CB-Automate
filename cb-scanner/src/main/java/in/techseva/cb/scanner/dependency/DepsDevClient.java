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
    private static final String ADVISORY_URL = "https://api.deps.dev/v3alpha/advisories";

    private final RestClient restClient;

    public DepsDevClient() {
        this.restClient = RestClient.builder().build();
    }

    /**
     * Returns a list of advisory reports for the given dependency, or empty if none found.
     * Step 1: GET /versions/{version} → read advisoryKeys[]
     * Step 2: GET /advisories/{id}    → fetch full details for each key
     */
    public List<Advisory> getAdvisories(String groupId, String artifactId, String version) {
        try {
            String pkg = groupId + ":" + artifactId;
            String versionUrl = UriComponentsBuilder.fromHttpUrl(BASE_URL)
                    .pathSegment(pkg, "versions", version)
                    .build().toUriString();

            VersionResponse versionResponse = restClient.get()
                    .uri(versionUrl)
                    .retrieve()
                    .body(VersionResponse.class);

            if (versionResponse == null || versionResponse.advisoryKeys() == null
                    || versionResponse.advisoryKeys().isEmpty()) {
                return Collections.emptyList();
            }

            List<Advisory> advisories = new ArrayList<>();
            for (AdvisoryKey key : versionResponse.advisoryKeys()) {
                if (key.id() == null) continue;
                try {
                    Advisory advisory = restClient.get()
                            .uri(ADVISORY_URL + "/" + key.id())
                            .retrieve()
                            .body(Advisory.class);
                    if (advisory != null) advisories.add(advisory);
                } catch (Exception e) {
                    log.debug("deps.dev advisory {}: {}", key.id(), e.getMessage());
                }
            }
            return advisories;
        } catch (Exception e) {
            log.debug("deps.dev lookup: {}:{}:{} — {}", groupId, artifactId, version, e.getMessage());
            return Collections.emptyList();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VersionResponse(
            @JsonProperty("advisoryKeys") List<AdvisoryKey> advisoryKeys) {}

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
            // cvss3Score may be 0 for GHSA advisories without CVSS — default to MAJOR (5.0)
            return (cvss3Score != null && cvss3Score > 0) ? cvss3Score : 5.0;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AdvisoryKey(@JsonProperty("id") String id) {}
}
