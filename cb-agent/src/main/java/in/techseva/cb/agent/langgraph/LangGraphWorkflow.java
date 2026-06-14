package in.techseva.cb.agent.langgraph;

import in.techseva.cb.core.domain.Vulnerability;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

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

    public AgentWorkflowState run(Vulnerability vuln) throws Exception {
        var compiled = new StateGraph<>(AgentWorkflowState.SCHEMA, AgentWorkflowState::new)
                .addNode("planner",   async(plannerNode))
                .addNode("retriever", async(retrieverNode))
                .addNode("generator", async(generatorNode))
                .addNode("validator", async(validatorNode))
                .addNode("reviewer",  async(reviewNode))
                .addEdge(START,       "planner")
                .addEdge("planner",   "retriever")
                .addEdge("retriever", "generator")
                .addEdge("generator", "validator")
                .addConditionalEdges("validator",
                        state -> CompletableFuture.completedFuture(
                                state.validationOk() ? "reviewer"
                                : state.retryCount() < MAX_RETRIES ? "generator"
                                : END),
                        Map.of("reviewer", "reviewer", "generator", "generator", END, END))
                .addConditionalEdges("reviewer",
                        state -> CompletableFuture.completedFuture(
                                state.validationOk() ? END : "generator"),
                        Map.of(END, END, "generator", "generator"))
                .compile();

        Map<String, Object> initialState = new HashMap<>();
        initialState.put(AgentWorkflowState.VULNERABILITY, vuln);
        initialState.put(AgentWorkflowState.RETRY_COUNT, 0);

        AgentWorkflowState finalState = compiled.invoke(initialState)
                .orElseThrow(() -> new IllegalStateException(
                        "Workflow produced no output for vuln=" + vuln.id()));
        log.info("LangGraph workflow complete: vuln={} model={} confidence={} validationOk={}",
                vuln.id(), finalState.llmModel(), finalState.confidence(), finalState.validationOk());
        return finalState;
    }

    // Wraps a synchronous NodeAction into the AsyncNodeAction required by StateGraph.addNode()
    private static AsyncNodeAction<AgentWorkflowState> async(NodeAction<AgentWorkflowState> node) {
        return state -> {
            try {
                return CompletableFuture.completedFuture(node.apply(state));
            } catch (Exception e) {
                CompletableFuture<Map<String, Object>> failed = new CompletableFuture<>();
                failed.completeExceptionally(e);
                return failed;
            }
        };
    }
}
