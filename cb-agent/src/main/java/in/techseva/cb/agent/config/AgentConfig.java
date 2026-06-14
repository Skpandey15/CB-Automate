package in.techseva.cb.agent.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.Nullable;

@Configuration
public class AgentConfig {


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

    // Fallback = Anthropic if available, else OpenAI (same as primary when only one key)
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
}
