package in.techseva.cb.agent.langgraph;

import in.techseva.cb.core.domain.Severity;
import in.techseva.cb.core.domain.Vulnerability;
import in.techseva.cb.core.domain.VulnerabilityStatus;
import in.techseva.cb.core.domain.VulnerabilityType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises the REAL compiled langgraph4j StateGraph (only the five node
 * implementations are mocked) — this is the actual agentic control flow:
 * planner -> retriever -> generator -> validator -> [retry generator |
 * reviewer] -> [retry generator | END]. A bug in the conditional-edge
 * wiring here means the agent either gives up too early, loops forever,
 * or skips the review gate.
 */
@ExtendWith(MockitoExtension.class)
class LangGraphWorkflowTest {

    @Mock PlannerNode plannerNode;
    @Mock RetrieverNode retrieverNode;
    @Mock GeneratorNode generatorNode;
    @Mock ValidatorNode validatorNode;
    @Mock ReviewNode reviewNode;

    private Vulnerability vuln() {
        return new Vulnerability("id1", "SONAR-001", "CWE-89", Severity.CRITICAL,
                "java:S2078", 42, "30min", VulnerabilityStatus.DETECTED,
                "msg", "A03:2021", "my-proj", "my-proj:src/Foo.java", "src/Foo.java",
                null, 0, Instant.now(), Instant.now(), null, null,
                VulnerabilityType.CODE, null, null, null, null, null);
    }

    @Test
    void validatorPassesFirstTry_generatorCalledOnceAndReviewerApproves() throws Exception {
        when(plannerNode.apply(any())).thenReturn(Map.of(AgentWorkflowState.LLM_MODEL, "gpt-4o"));
        when(retrieverNode.apply(any())).thenReturn(Map.of(AgentWorkflowState.CONTEXT_DOCS, java.util.List.of()));
        when(generatorNode.apply(any())).thenReturn(Map.of(AgentWorkflowState.GENERATED_FIX, "{}"));
        when(validatorNode.apply(any())).thenReturn(Map.of(AgentWorkflowState.VALIDATION_OK, true));
        when(reviewNode.apply(any())).thenReturn(Map.of(AgentWorkflowState.VALIDATION_OK, true));

        var workflow = new LangGraphWorkflow(plannerNode, retrieverNode, generatorNode, validatorNode, reviewNode);
        var finalState = workflow.run(vuln());

        assertThat(finalState.validationOk()).isTrue();
        verify(generatorNode, times(1)).apply(any());
        verify(validatorNode, times(1)).apply(any());
        verify(reviewNode, times(1)).apply(any());
    }

    @Test
    void validatorFailsTwiceThenSucceeds_generatorRetriedAndReviewerStillReached() throws Exception {
        when(plannerNode.apply(any())).thenReturn(Map.of());
        when(retrieverNode.apply(any())).thenReturn(Map.of());
        when(generatorNode.apply(any())).thenReturn(Map.of(AgentWorkflowState.GENERATED_FIX, "{}"));

        AtomicInteger validatorCalls = new AtomicInteger(0);
        when(validatorNode.apply(any())).thenAnswer(invocation -> {
            AgentWorkflowState state = invocation.getArgument(0);
            int call = validatorCalls.incrementAndGet();
            if (call < 3) {
                return Map.of(AgentWorkflowState.VALIDATION_OK, false,
                        AgentWorkflowState.RETRY_COUNT, state.retryCount() + 1);
            }
            return Map.of(AgentWorkflowState.VALIDATION_OK, true);
        });
        when(reviewNode.apply(any())).thenReturn(Map.of(AgentWorkflowState.VALIDATION_OK, true));

        var workflow = new LangGraphWorkflow(plannerNode, retrieverNode, generatorNode, validatorNode, reviewNode);
        var finalState = workflow.run(vuln());

        assertThat(finalState.validationOk()).isTrue();
        verify(generatorNode, times(3)).apply(any()); // initial + 2 retries
        verify(validatorNode, times(3)).apply(any());
        verify(reviewNode, times(1)).apply(any());
    }

    @Test
    void validatorAlwaysFails_stopsAtMaxRetriesWithoutReachingReviewer() throws Exception {
        when(plannerNode.apply(any())).thenReturn(Map.of());
        when(retrieverNode.apply(any())).thenReturn(Map.of());
        when(generatorNode.apply(any())).thenReturn(Map.of(AgentWorkflowState.GENERATED_FIX, "{}"));
        when(validatorNode.apply(any())).thenAnswer(invocation -> {
            AgentWorkflowState state = invocation.getArgument(0);
            return Map.of(AgentWorkflowState.VALIDATION_OK, false,
                    AgentWorkflowState.RETRY_COUNT, state.retryCount() + 1);
        });

        var workflow = new LangGraphWorkflow(plannerNode, retrieverNode, generatorNode, validatorNode, reviewNode);
        var finalState = workflow.run(vuln());

        assertThat(finalState.validationOk()).isFalse();
        // MAX_RETRIES = 3: retryCount goes 0 -> 1 -> 2 -> 3 across three
        // validator failures; "3 < MAX_RETRIES(3)" is false on the third,
        // so the graph ends there. Three generator/validator calls total.
        verify(generatorNode, times(3)).apply(any());
        verify(validatorNode, times(3)).apply(any());
        verify(reviewNode, times(0)).apply(any());
    }

    @Test
    void reviewerRejects_loopsBackToGeneratorOnce() throws Exception {
        when(plannerNode.apply(any())).thenReturn(Map.of());
        when(retrieverNode.apply(any())).thenReturn(Map.of());
        when(generatorNode.apply(any())).thenReturn(Map.of(AgentWorkflowState.GENERATED_FIX, "{}"));
        when(validatorNode.apply(any())).thenReturn(Map.of(AgentWorkflowState.VALIDATION_OK, true));

        AtomicInteger reviewCalls = new AtomicInteger(0);
        when(reviewNode.apply(any())).thenAnswer(invocation -> {
            boolean approve = reviewCalls.incrementAndGet() >= 2;
            return Map.of(AgentWorkflowState.VALIDATION_OK, approve);
        });

        var workflow = new LangGraphWorkflow(plannerNode, retrieverNode, generatorNode, validatorNode, reviewNode);
        var finalState = workflow.run(vuln());

        assertThat(finalState.validationOk()).isTrue();
        verify(generatorNode, times(2)).apply(any());
        verify(reviewNode, times(2)).apply(any());
    }
}
