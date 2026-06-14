package in.techseva.cb.agent.service;

import in.techseva.cb.core.domain.Severity;
import in.techseva.cb.core.domain.Vulnerability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Routes fix generation to the appropriate LLM based on vulnerability severity:
 * - HIGH / CRITICAL → GPT-4o (cloud, highest accuracy)
 * - MAJOR / MINOR / BLOCKER-but-well-understood → Ollama local (zero cost)
 * - LOW / INFO → Ollama local (zero cost)
 */
@Service
public class OllamaRoutingService {

    private static final Logger log = LoggerFactory.getLogger(OllamaRoutingService.class);

    private final ChatClient gpt4oChatClient;
    private final ChatClient ollamaChatClient;

    @Value("${ollama.model.low-severity:llama3:8b}")
    private String ollamaLowModel;

    @Value("${ollama.model.medium-severity:qwen2:7b}")
    private String ollamaMediumModel;

    public OllamaRoutingService(
            @org.springframework.beans.factory.annotation.Qualifier("primaryChatClient") ChatClient gpt4oChatClient,
            @org.springframework.beans.factory.annotation.Qualifier("ollamaChatClient") ChatClient ollamaChatClient) {
        this.gpt4oChatClient = gpt4oChatClient;
        this.ollamaChatClient = ollamaChatClient;
    }

    public ChatClient routeForVulnerability(Vulnerability vuln) {
        return switch (vuln.severity()) {
            case CRITICAL, BLOCKER -> {
                log.debug("Routing {} severity={} to GPT-4o", vuln.id(), vuln.severity());
                yield gpt4oChatClient;
            }
            case MAJOR -> {
                log.debug("Routing {} severity={} to Ollama (qwen2)", vuln.id(), vuln.severity());
                yield ollamaChatClient;
            }
            default -> {
                log.debug("Routing {} severity={} to Ollama (llama3)", vuln.id(), vuln.severity());
                yield ollamaChatClient;
            }
        };
    }

    public String modelNameForVulnerability(Vulnerability vuln) {
        return (vuln.severity() == Severity.CRITICAL || vuln.severity() == Severity.BLOCKER)
                ? "gpt-4o"
                : vuln.severity() == Severity.MAJOR ? ollamaMediumModel : ollamaLowModel;
    }
}
