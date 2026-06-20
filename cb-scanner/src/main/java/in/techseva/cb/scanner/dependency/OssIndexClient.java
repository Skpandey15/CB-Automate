package in.techseva.cb.scanner.dependency;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Client for the Sonatype OSS Index component-report API.
 * Checks Maven coordinates against known CVEs.
 *
 * Free tier: 128 coordinates per request, ~64 requests/min unauthenticated.
 * Set OSS_INDEX_USERNAME + OSS_INDEX_TOKEN for higher rate limits.
 *
 * API docs: https://ossindex.sonatype.org/rest
 */
@Component
public class OssIndexClient {

    private static final Logger log = LoggerFactory.getLogger(OssIndexClient.class);
    private static final String API_URL = "https://ossindex.sonatype.org/api/v3/component-report";
    private static final int BATCH_SIZE = 128;

    private final RestClient restClient;

    public OssIndexClient(
            @Value("${dep.scanner.oss-index.username:}") String username,
            @Value("${dep.scanner.oss-index.token:}") String token) {

        var builder = RestClient.builder().baseUrl(API_URL);
        if (!username.isBlank() && !token.isBlank()) {
            String basic = java.util.Base64.getEncoder()
                    .encodeToString((username + ":" + token).getBytes());
            builder.defaultHeader("Authorization", "Basic " + basic);
            log.info("OSS Index client configured with authentication");
        } else {
            log.info("OSS Index client running unauthenticated (rate-limited to ~64 req/min)");
        }
        this.restClient = builder.build();
    }

    /**
     * Query OSS Index for a list of purls (pkg:maven/group/artifact@version).
     * Returns only components that have at least one vulnerability.
     */
    public List<ComponentReport> queryVulnerabilities(List<String> purls) {
        if (purls.isEmpty()) return Collections.emptyList();

        List<ComponentReport> vulnerable = new ArrayList<>();
        // Process in batches of 128
        for (int i = 0; i < purls.size(); i += BATCH_SIZE) {
            List<String> batch = purls.subList(i, Math.min(i + BATCH_SIZE, purls.size()));
            vulnerable.addAll(queryBatch(batch));
        }
        return vulnerable;
    }

    private List<ComponentReport> queryBatch(List<String> purls) {
        try {
            log.debug("Querying OSS Index for {} coordinates", purls.size());
            ComponentReport[] reports = restClient.post()
                    .body(Map.of("coordinates", purls))
                    .retrieve()
                    .body(ComponentReport[].class);

            if (reports == null) return Collections.emptyList();

            List<ComponentReport> found = new ArrayList<>();
            for (ComponentReport r : reports) {
                if (r.vulnerabilities() != null && !r.vulnerabilities().isEmpty()) {
                    found.add(r);
                    log.info("OSS Index: {} has {} vulnerabilities", r.coordinates(), r.vulnerabilities().size());
                }
            }
            return found;
        } catch (Exception e) {
            log.error("OSS Index query failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ComponentReport(
            @JsonProperty("coordinates")    String coordinates,
            @JsonProperty("description")    String description,
            @JsonProperty("reference")      String reference,
            @JsonProperty("vulnerabilities") List<OssVulnerability> vulnerabilities
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OssVulnerability(
            @JsonProperty("id")          String id,
            @JsonProperty("displayName") String displayName,
            @JsonProperty("title")       String title,
            @JsonProperty("description") String description,
            @JsonProperty("cvssScore")   Double cvssScore,
            @JsonProperty("cvssVector")  String cvssVector,
            @JsonProperty("cve")         String cve,
            @JsonProperty("cwe")         String cwe,
            @JsonProperty("reference")   String reference
    ) {
        public double severity() { return cvssScore != null ? cvssScore : 0.0; }
    }
}
