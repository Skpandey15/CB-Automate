package in.techseva.cb.agent.langgraph;

import in.techseva.cb.core.domain.Vulnerability;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.CompileConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;

/**
 * Wires the LangGraph4j multi-agent workflow:
 * START → planner → retriever → generator → validator → [retry|reviewer] → END
 */
@Service
public class LangGraphWorkflow {

    private static final Logger log = LoggerFactory.getLogger(LangGraphWorkflow.class);
    private static final int MAX_RETRIES = 3;

    private final PlannerNode plannerNode;
    private final RetrieverNode retrieverNode;
    private final GeneratorNode generatorNode;
    private final ValidatorNode validatorNode;
    private final ReviewNode reviewNode;

    public LangGraphWorkflow(PlannerNode plannerNode,
                              RetrieverNode retrieverNode,
                              GeneratorNode generatorNode,
                              ValidatorNode validatorNode,
                              ReviewNode reviewNode) {
        this.plannerNode = plannerNode;
        this.retrieverNode = retrieverNode;
        this.generatorNode = generatorNode;
        this.validatorNode = validatorNode;
        this.reviewNode = reviewNode;
    }

    /**
     * Runs the full multi-agent pipeline for a vulnerability and returns the final state.
     */
    public AgentWorkflowState run(Vulnerability vuln) throws Exception {
        var graph = new StateGraph<>(AgentWorkflowState::new)
                .addNode("planner",   plannerNode)
                .addNode("retriever", retrieverNode)
                .addNode("generator", generatorNode)
                .addNode("validator", validatorNode)
                .addNode("reviewer",  reviewNode)
                .addEdge(START,       "planner")
                .addEdge("planner",   "retriever")
                .addEdge("retriever", "generator")
                .addEdge("generator", "validator")
                .addConditionalEdges("validator",
                        state -> {
                            if (state.validationOk()) return "reviewer";
                            int retry = state.retryCount();
                            if (retry < MAX_RETRIES) return "generator";
                            return END;
                        },
                        Map.of("reviewer", "reviewer", "generator", "generator", END, END))
                .addConditionalEdges("reviewer",
                        state -> state.validationOk() ? END : "generator",
                        Map.of(END, END, "generator", "generator"))
                .compile(CompileConfig.builder().build());

        Map<String, Object> initialState = new HashMap<>();
        initialState.put(AgentWorkflowState.VULNERABILITY, vuln);
        initialState.put(AgentWorkflowState.RETRY_COUNT, 0);

        var result = graph.stream(initialState);
        AgentWorkflowState finalState = result
                .stream()
                .filter(n -> n.state() != null)
                .reduce((a, b) -> b)
                .map(n -> n.state())
                .orElseThrow(() -> new IllegalStateException("Workflow produced no output for vuln=" + vuln.id()));

        log.info("LangGraph workflow complete: vuln={} model={} confidence={} validationOk={}",
                vuln.id(), finalState.llmModel(), finalState.confidence(), finalState.validationOk());
        return finalState;
    }
}
