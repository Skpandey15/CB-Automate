package in.techseva.cb.api.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class ScannerClient {

    private static final Logger log = LoggerFactory.getLogger(ScannerClient.class);

    private final RestClient restClient;

    public ScannerClient(@Value("${scanner.api-url:http://cb-scanner:8081}") String scannerUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(60_000);

        this.restClient = RestClient.builder()
            .baseUrl(scannerUrl)
            .requestFactory(factory)
            .build();
    }

    public int triggerScan(String projectKey) {
        log.info("Triggering scan for projectKey={}", projectKey);
        try {
            Map<String, Object> response = restClient.post()
                .uri("/api/v1/scanner/scan/{projectKey}", projectKey)
                .retrieve()
                .body(Map.class);
            Object newFindings = response != null ? response.get("newFindings") : null;
            return newFindings instanceof Number ? ((Number) newFindings).intValue() : 0;
        } catch (Exception e) {
            log.error("Scan trigger failed for projectKey={}: {}", projectKey, e.getMessage());
            throw new ScannerUnavailableException("Scanner service call failed: " + e.getMessage(), e);
        }
    }

    public static class ScannerUnavailableException extends RuntimeException {
        public ScannerUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
