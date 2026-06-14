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
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.lang.Nullable;

@Configuration
public class AgentConfig {

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

    // Ollama for low-severity routing (zero-cost local inference)
    @Bean("ollamaChatClient")
    public ChatClient ollamaChatClient(
            @Nullable @Qualifier("ollamaChatModel") ChatModel ollamaModel,
            @Qualifier("primaryChatClient") ChatClient primaryFallback) {
        if (ollamaModel != null) {
            return ChatClient.builder(ollamaModel).build();
        }
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
        return factory;
    }
}
