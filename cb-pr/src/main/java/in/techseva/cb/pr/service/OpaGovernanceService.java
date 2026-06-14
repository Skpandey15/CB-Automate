package in.techseva.cb.pr.service;

import in.techseva.cb.core.domain.Fix;
import in.techseva.cb.core.domain.Vulnerability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Calls OPA (Open Policy Agent) to evaluate the patch against governance policies
 * defined in patch_governance.rego before a PR is created.
 *
 * OPA input: { vulnerability, patchDiff, cweId, strategy }
 * OPA output: { result: [ { violations: [...], allow: true/false } ] }
 */
@Service
public class OpaGovernanceService {

    private static final Logger log = LoggerFactory.getLogger(OpaGovernanceService.class);

    private final RestClient restClient;
    private final boolean opaEnabled;
    private final String opaPolicy;

    public OpaGovernanceService(
            @Value("${opa.url:http://opa:8181}") String opaUrl,
            @Value("${opa.policy:cb/governance}") String opaPolicy,
            @Value("${opa.enabled:true}") boolean opaEnabled) {
        this.restClient = RestClient.builder()
                .baseUrl(opaUrl)
                .build();
        this.opaPolicy = opaPolicy;
        this.opaEnabled = opaEnabled;
    }

    public GovernanceResult evaluate(Vulnerability vuln, Fix fix) {
        if (!opaEnabled) {
            log.debug("OPA governance disabled — allowing PR for fix={}", fix.id());
            return GovernanceResult.allowed();
        }

        Map<String, Object> input = Map.of(
                "input", Map.of(
                    "cweId", vuln.cweId() != null ? vuln.cweId() : "",
                    "severity", vuln.severity() != null ? vuln.severity().name() : "UNKNOWN",
                    "patchDiff", fix.patchDiff() != null ? fix.patchDiff() : "",
                    "strategy", fix.strategy() != null ? fix.strategy() : "",
                    "confidence", fix.confidence()
                )
        );

        try {
            var response = restClient.post()
                    .uri("/v1/data/" + opaPolicy.replace('/', '/'))
                    .body(input)
                    .retrieve()
                    .body(OpaResponse.class);

            if (response == null || response.result() == null) {
                log.warn("OPA returned null response for fix={} — defaulting to ALLOW", fix.id());
                return GovernanceResult.allowed();
            }

            boolean allow = Boolean.TRUE.equals(response.result().get("allow"));
            @SuppressWarnings("unchecked")
            List<String> violations = (List<String>) response.result().getOrDefault("violations", List.of());

            log.info("OPA governance for fix={}: allow={} violations={}", fix.id(), allow, violations);
            return new GovernanceResult(allow, violations);

        } catch (Exception e) {
            log.error("OPA governance call failed for fix={}: {} — defaulting to ALLOW", fix.id(), e.getMessage());
            return GovernanceResult.allowed();
        }
    }

    public record GovernanceResult(boolean allowed, List<String> violations) {
        static GovernanceResult allowed() {
            return new GovernanceResult(true, List.of());
        }
    }

    record OpaResponse(Map<String, Object> result) {}
}
