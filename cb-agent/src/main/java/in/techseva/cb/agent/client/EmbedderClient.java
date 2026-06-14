package in.techseva.cb.agent.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

@Component
public class EmbedderClient {

    private static final Logger log = LoggerFactory.getLogger(EmbedderClient.class);

    private final RestClient restClient;

    public EmbedderClient(@Value("${embedder.url:http://localhost:8090}") String embedderUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(embedderUrl)
                .build();
    }

    @CircuitBreaker(name = "embedder-api", fallbackMethod = "emptyEmbedding")
    @Retry(name = "embedder-api")
    public List<Double> embed(String text) {
        EmbedRequest request = new EmbedRequest(text);
        EmbedResponse response = restClient.post()
                .uri("/embed")
                .body(request)
                .retrieve()
                .body(EmbedResponse.class);
        if (response == null) throw new RuntimeException("Null response from embedder");
        log.debug("Embedded text ({} chars) → {} dims", text.length(), response.embedding().size());
        return response.embedding();
    }

    public List<Double> emptyEmbedding(String text, Throwable t) {
        log.warn("Embedder circuit open: {}", t.getMessage());
        return List.of();
    }

    public record EmbedRequest(@JsonProperty("text") String text) {}
    public record EmbedResponse(@JsonProperty("embedding") List<Double> embedding) {}
}
