package in.techseva.cb.agent.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.lang.Nullable;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class AgentConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentConfig.class);

    @Value("${spring.elasticsearch.uris:http://localhost:9200}")
    private String elasticsearchUri;

    // Primary = OpenAI if available, else Anthropic
    @Bean("primaryChatClient")
    public ChatClient primaryChatClient(
            @Nullable @Qualifier("openAiChatModel") ChatModel openAiModel,
            @Nullable @Qualifier("anthropicChatModel") ChatModel anthropicModel) {
        ChatModel model = openAiModel != null ? openAiModel : anthropicModel;
        if (model == null) {
            throw new IllegalStateException(
                    "No AI model available — set OPENAI_API_KEY or ANTHROPIC_API_KEY");
        }
        return ChatClient.builder(model).build();
    }

    // Fallback = Anthropic if available, else OpenAI
    @Bean("fallbackChatClient")
    public ChatClient fallbackChatClient(
            @Nullable @Qualifier("anthropicChatModel") ChatModel anthropicModel,
            @Nullable @Qualifier("openAiChatModel") ChatModel openAiModel) {
        ChatModel model = anthropicModel != null ? anthropicModel : openAiModel;
        if (model == null) {
            throw new IllegalStateException(
                    "No AI model available — set OPENAI_API_KEY or ANTHROPIC_API_KEY");
        }
        return ChatClient.builder(model).build();
    }

    @Value("${spring.ai.ollama.chat.enabled:true}")
    private boolean ollamaEnabled;

    // Ollama for low-severity routing (zero-cost local inference)
    @Bean("ollamaChatClient")
    public ChatClient ollamaChatClient(
            @Nullable @Qualifier("ollamaChatModel") ChatModel ollamaModel,
            @Qualifier("primaryChatClient") ChatClient primaryFallback) {
        if (ollamaEnabled && ollamaModel != null) {
            log.info("Ollama enabled — using local inference for low-severity routing");
            return ChatClient.builder(ollamaModel).build();
        }
        log.info("Ollama disabled — routing all severities to primary model (GPT-4o)");
        return primaryFallback;
    }

    @Bean
    public ElasticsearchClient elasticsearchClient() {
        String uri = elasticsearchUri.replace("http://", "");
        String[] parts = uri.split(":");
        String host = parts[0];
        int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 9200;

        RestClient restClient = RestClient.builder(new HttpHost(host, port, "http")).build();
        var transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        return new ElasticsearchClient(transport);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, Object>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setConcurrency(2);

        // Skip poison-pill messages immediately; retry transient listener errors up to 3x
        var errorHandler = new DefaultErrorHandler(
            (rec, ex) -> log.error("Skipping bad record topic={} partition={} offset={}: {}",
                rec.topic(), rec.partition(), rec.offset(), ex.getMessage()),
            new FixedBackOff(1000L, 3L)
        );
        errorHandler.addNotRetryableExceptions(DeserializationException.class);
        factory.setCommonErrorHandler(errorHandler);

        return factory;
    }
}
