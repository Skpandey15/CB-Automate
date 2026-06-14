package in.techseva.cb.mcp.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class CBPatcherClient {

    private static final Logger log = LoggerFactory.getLogger(CBPatcherClient.class);

    private final RestClient restClient;

    public CBPatcherClient(@Value("${patcher.api-url:http://cb-patcher:8083}") String patcherUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(600_000);  // builds can take up to 10 min

        this.restClient = RestClient.builder()
            .baseUrl(patcherUrl)
            .requestFactory(factory)
            .build();
    }

    public String triggerBuild(String branchName) {
        log.info("Triggering build for branch={}", branchName);
        try {
            String response = restClient.post()
                .uri("/api/patcher/build")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("branchName", branchName))
                .retrieve()
                .body(String.class);
            return response != null ? response : "{\"status\":\"triggered\"}";
        } catch (Exception e) {
            log.error("Build trigger failed for branch={}: {}", branchName, e.getMessage());
            throw new RuntimeException("Patcher build error: " + e.getMessage(), e);
        }
    }
}
