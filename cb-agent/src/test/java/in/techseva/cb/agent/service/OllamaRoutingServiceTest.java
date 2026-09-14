package in.techseva.cb.agent.service;

import in.techseva.cb.core.domain.Severity;
import in.techseva.cb.core.domain.Vulnerability;
import in.techseva.cb.core.domain.VulnerabilityStatus;
import in.techseva.cb.core.domain.VulnerabilityType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the documented multi-model routing table (README "Multi-model
 * routing"): CRITICAL/BLOCKER -> GPT-4o, MAJOR -> Ollama qwen2, MINOR/INFO
 * -> Ollama llama3, dependency vulnerabilities always -> GPT-4o regardless
 * of severity.
 */
@ExtendWith(MockitoExtension.class)
class OllamaRoutingServiceTest {

    @Mock ChatClient gpt4oChatClient;
    @Mock ChatClient ollamaChatClient;

    private OllamaRoutingService service;

    @BeforeEach
    void setUp() {
        service = new OllamaRoutingService(gpt4oChatClient, ollamaChatClient);
        ReflectionTestUtils.setField(service, "ollamaLowModel", "llama3:8b");
        ReflectionTestUtils.setField(service, "ollamaMediumModel", "qwen2:7b");
        ReflectionTestUtils.setField(service, "ollamaEnabled", true);
    }

    private Vulnerability vuln(Severity severity, VulnerabilityType type) {
        return new Vulnerability("id1", "SONAR-001", "CWE-89", severity,
                "java:S2078", 42, "30min", VulnerabilityStatus.DETECTED,
                "msg", "A03:2021", "my-proj", "my-proj:src/Foo.java", "src/Foo.java",
                null, 0, Instant.now(), Instant.now(), null, null,
                type, null, null, null, null, null);
    }

    @ParameterizedTest
    @CsvSource({"CRITICAL", "BLOCKER"})
    void criticalOrBlocker_routesToGpt4o(Severity severity) {
        var vuln = vuln(severity, VulnerabilityType.CODE);

        assertThat(service.routeForVulnerability(vuln)).isSameAs(gpt4oChatClient);
        assertThat(service.modelNameForVulnerability(vuln)).isEqualTo("gpt-4o");
    }

    @Test
    void major_routesToOllamaQwen2() {
        var vuln = vuln(Severity.MAJOR, VulnerabilityType.CODE);

        assertThat(service.routeForVulnerability(vuln)).isSameAs(ollamaChatClient);
        assertThat(service.modelNameForVulnerability(vuln)).isEqualTo("qwen2:7b");
    }

    @ParameterizedTest
    @CsvSource({"MINOR", "INFO"})
    void minorOrInfo_routesToOllamaLlama3(Severity severity) {
        var vuln = vuln(severity, VulnerabilityType.CODE);

        assertThat(service.routeForVulnerability(vuln)).isSameAs(ollamaChatClient);
        assertThat(service.modelNameForVulnerability(vuln)).isEqualTo("llama3:8b");
    }

    @Test
    void dependencyVulnerability_alwaysRoutesToGpt4o_regardlessOfSeverity() {
        var vuln = vuln(Severity.INFO, VulnerabilityType.DEPENDENCY);

        assertThat(service.routeForVulnerability(vuln)).isSameAs(gpt4oChatClient);
        assertThat(service.modelNameForVulnerability(vuln)).isEqualTo("gpt-4o");
    }

    @Test
    void ollamaDisabled_alwaysRoutesModelNameToGpt4o() {
        ReflectionTestUtils.setField(service, "ollamaEnabled", false);
        var vuln = vuln(Severity.MINOR, VulnerabilityType.CODE);

        assertThat(service.modelNameForVulnerability(vuln)).isEqualTo("gpt-4o");
    }
}
