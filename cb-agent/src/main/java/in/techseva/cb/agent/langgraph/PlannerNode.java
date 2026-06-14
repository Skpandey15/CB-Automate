package in.techseva.cb.agent.langgraph;

import in.techseva.cb.agent.service.OllamaRoutingService;
import in.techseva.cb.core.domain.Vulnerability;
import org.bsc.langgraph4j.action.NodeAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * First node: decides which LLM model to use based on vulnerability severity.
 */
@Component
public class PlannerNode implements NodeAction<AgentWorkflowState> {

    private static final Logger log = LoggerFactory.getLogger(PlannerNode.class);

    private final OllamaRoutingService routingService;

    public PlannerNode(OllamaRoutingService routingService) {
        this.routingService = routingService;
    }

    @Override
    public Map<String, Object> apply(AgentWorkflowState state) throws Exception {
        Vulnerability vuln = state.vulnerability()
                .orElseThrow(() -> new IllegalStateException("No vulnerability in state"));

        String model = routingService.modelNameForVulnerability(vuln);
        log.info("PlannerNode: vuln={} cwe={} severity={} → model={}",
                vuln.id(), vuln.cweId(), vuln.severity(), model);

        return Map.of(
                AgentWorkflowState.LLM_MODEL, model,
                AgentWorkflowState.RETRY_COUNT, state.retryCount()
        );
    }
}
