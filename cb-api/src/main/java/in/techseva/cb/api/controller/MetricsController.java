package in.techseva.cb.api.controller;

import in.techseva.cb.core.domain.FixStatus;
import in.techseva.cb.core.domain.VulnerabilityStatus;
import in.techseva.cb.core.repository.FixRepository;
import in.techseva.cb.core.repository.VulnerabilityRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/metrics")
@Tag(name = "Metrics", description = "Operational metrics summary")
@SecurityRequirement(name = "ApiKeyAuth")
public class MetricsController {

    private final VulnerabilityRepository vulnRepo;
    private final FixRepository fixRepo;

    public MetricsController(VulnerabilityRepository vulnRepo, FixRepository fixRepo) {
        this.vulnRepo = vulnRepo;
        this.fixRepo = fixRepo;
    }

    @GetMapping
    @Operation(summary = "Get operational metrics summary")
    public ResponseEntity<Map<String, Object>> metrics() {
        Map<String, Object> m = new LinkedHashMap<>();

        Map<String, Long> vulns = new LinkedHashMap<>();
        for (VulnerabilityStatus s : VulnerabilityStatus.values()) {
            vulns.put(s.name(), vulnRepo.countByStatus(s));
        }
        m.put("vulnerabilities", vulns);

        Map<String, Long> fixes = new LinkedHashMap<>();
        for (FixStatus s : FixStatus.values()) {
            fixes.put(s.name(), fixRepo.countByStatus(s));
        }
        m.put("fixes", fixes);

        long total = vulnRepo.count();
        long resolved = vulnRepo.countByStatus(VulnerabilityStatus.RESOLVED);
        m.put("remediationRate", total > 0 ? String.format("%.1f%%", (double) resolved / total * 100) : "N/A");

        return ResponseEntity.ok(m);
    }
}
