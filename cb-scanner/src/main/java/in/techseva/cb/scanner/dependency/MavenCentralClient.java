package in.techseva.cb.scanner.dependency;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Queries Maven Central Search API for the latest stable release of a dependency.
 * Used to suggest a safe version when a CVE is found in the current version.
 */
@Component
public class MavenCentralClient {

    private static final Logger log = LoggerFactory.getLogger(MavenCentralClient.class);
    private static final String SEARCH_URL = "https://search.maven.org/solrsearch/select";

    private final RestClient restClient;

    public MavenCentralClient() {
        this.restClient = RestClient.builder().baseUrl(SEARCH_URL).build();
    }

    /**
     * Returns the latest stable version for the given group:artifact,
     * or empty if the artifact cannot be found or the network is unavailable.
     */
    public Optional<String> latestStableVersion(String group, String artifact) {
        try {
            String query = "g:" + group + "+AND+a:" + artifact;
            SearchResponse response = restClient.get()
                    .uri("?q={q}&core=gav&rows=20&wt=json", query)
                    .retrieve()
                    .body(SearchResponse.class);

            if (response == null || response.response() == null
                    || response.response().docs() == null
                    || response.response().docs().isEmpty()) {
                return Optional.empty();
            }

            return response.response().docs().stream()
                    .map(SearchDoc::v)
                    .filter(v -> v != null && isStable(v))
                    .max((a, b) -> compareVersions(parseVersion(a), parseVersion(b)))
                    .or(() -> response.response().docs().stream()
                            .map(SearchDoc::v)
                            .filter(v -> v != null)
                            .findFirst());
        } catch (Exception e) {
            log.warn("Maven Central lookup failed for {}:{} — {}", group, artifact, e.getMessage());
            return Optional.empty();
        }
    }

    private boolean isStable(String version) {
        String lower = version.toLowerCase();
        return !lower.contains("alpha") && !lower.contains("beta")
                && !lower.contains("rc") && !lower.contains("snapshot")
                && !lower.contains("milestone") && !lower.contains(".m");
    }

    private List<Integer> parseVersion(String v) {
        try {
            String[] parts = v.replaceAll("[^0-9.]", ".").split("\\.");
            List<Integer> result = new ArrayList<>();
            for (String part : parts) {
                if (!part.isBlank()) {
                    try { result.add(Integer.parseInt(part)); } catch (NumberFormatException e) { result.add(0); }
                }
            }
            return result;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private int compareVersions(List<Integer> a, List<Integer> b) {
        int len = Math.max(a.size(), b.size());
        for (int i = 0; i < len; i++) {
            int av = i < a.size() ? a.get(i) : 0;
            int bv = i < b.size() ? b.get(i) : 0;
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchResponse(@JsonProperty("response") SearchResponseBody response) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchResponseBody(
            @JsonProperty("numFound") int numFound,
            @JsonProperty("docs") List<SearchDoc> docs) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchDoc(
            @JsonProperty("g") String g,
            @JsonProperty("a") String a,
            @JsonProperty("v") String v) {}
}
