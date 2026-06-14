package in.techseva.cb.api.controller;

import in.techseva.cb.core.repository.CostRepository;
import in.techseva.cb.core.repository.CostRepository.CostSummaryByCwe;
import in.techseva.cb.core.repository.CostRepository.CostSummaryByModel;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/cost")
@Tag(name = "Cost Tracking", description = "LLM cost visibility per vulnerability and model")
@SecurityRequirement(name = "ApiKeyAuth")
public class CostTrackingController {

    private final CostRepository costRepository;

    public CostTrackingController(CostRepository costRepository) {
        this.costRepository = costRepository;
    }

    @GetMapping("/summary")
    @Operation(summary = "Get cost summary for the last N days",
               description = "Returns total tokens, cost, and record count across all models")
    public ResponseEntity<Map<String, Object>> summary(
            @RequestParam(defaultValue = "7") int days) {
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);
        var records = costRepository.findByRecordedAtAfter(since);
        long totalInputTokens = records.stream().mapToLong(r -> r.inputTokens()).sum();
        long totalOutputTokens = records.stream().mapToLong(r -> r.outputTokens()).sum();
        double totalCost = records.stream().mapToDouble(r -> r.costUsd()).sum();
        long acceptedCount = records.stream().filter(r -> r.accepted()).count();
        return ResponseEntity.ok(Map.of(
                "period", days + " days",
                "since", since.toString(),
                "totalRecords", records.size(),
                "totalInputTokens", totalInputTokens,
                "totalOutputTokens", totalOutputTokens,
                "totalCostUsd", String.format("%.4f", totalCost),
                "acceptedFixes", acceptedCount,
                "rejectedFixes", records.size() - acceptedCount
        ));
    }

    @GetMapping("/by-cwe")
    @Operation(summary = "Get cost breakdown by CWE ID",
               description = "Returns total cost per CWE, sorted by cost descending")
    public ResponseEntity<List<CostSummaryByCwe>> byCwe(
            @RequestParam(defaultValue = "30") int days) {
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);
        return ResponseEntity.ok(costRepository.sumCostByCwe(since));
    }

    @GetMapping("/by-model")
    @Operation(summary = "Get cost breakdown by LLM model",
               description = "Returns total tokens and cost per model (gpt-4o, claude, ollama, etc.)")
    public ResponseEntity<List<CostSummaryByModel>> byModel(
            @RequestParam(defaultValue = "30") int days) {
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);
        return ResponseEntity.ok(costRepository.sumCostByModel(since));
    }
}
