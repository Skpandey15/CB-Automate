package in.techseva.cb.agent.langgraph;

import in.techseva.cb.agent.service.OllamaRoutingService;
import in.techseva.cb.core.domain.Severity;
import in.techseva.cb.core.domain.Vulnerability;
import in.techseva.cb.core.domain.VulnerabilityStatus;
import in.techseva.cb.core.domain.VulnerabilityType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlannerNodeTest {

    @Mock OllamaRoutingService routingService;

    private Vulnerability vuln() {
        return new Vulnerability("id1", "SONAR-001", "CWE-89", Severity.CRITICAL,
                "java:S2078", 42, "30min", VulnerabilityStatus.DETECTED,
                "msg", "A03:2021", "my-proj", "my-proj:src/Foo.java", "src/Foo.java",
                null, 3, Instant.now(), Instant.now(), null, null,
                VulnerabilityType.CODE, null, null, null, null, null);
    }

    @Test
    void apply_setsModelFromRoutingServiceAndPreservesRetryCount() throws Exception {
        Vulnerability vuln = vuln();
        when(routingService.modelNameForVulnerability(vuln)).thenReturn("gpt-4o");
        var state = new AgentWorkflowState(new HashMap<>(Map.of(
                AgentWorkflowState.VULNERABILITY, vuln,
                AgentWorkflowState.RETRY_COUNT, 2
        )));

        var result = new PlannerNode(routingService).apply(state);

        assertThat(result.get(AgentWorkflowState.LLM_MODEL)).isEqualTo("gpt-4o");
        assertThat(result.get(AgentWorkflowState.RETRY_COUNT)).isEqualTo(2);
    }

    @Test
    void apply_noVulnerabilityInState_throws() {
        var state = new AgentWorkflowState(new HashMap<>());

        assertThatThrownBy(() -> new PlannerNode(routingService).apply(state))
                .isInstanceOf(IllegalStateException.class);
    }
}
