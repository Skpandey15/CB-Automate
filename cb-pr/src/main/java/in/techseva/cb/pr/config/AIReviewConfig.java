package in.techseva.cb.pr.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.Nullable;

@Configuration
public class AIReviewConfig {

    @Bean("prReviewChatClient")
    public ChatClient prReviewChatClient(
            @Nullable @Qualifier("openAiChatModel") ChatModel openAiModel,
            @Nullable @Qualifier("anthropicChatModel") ChatModel anthropicModel) {
        ChatModel model = openAiModel != null ? openAiModel : anthropicModel;
        if (model == null) {
            throw new IllegalStateException("No AI model available for PR review — set OPENAI_API_KEY");
        }
        return ChatClient.builder(model).build();
    }
}
