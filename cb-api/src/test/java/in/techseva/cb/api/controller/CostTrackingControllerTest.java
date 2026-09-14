package in.techseva.cb.api.controller;

import in.techseva.cb.core.domain.CostRecord;
import in.techseva.cb.core.repository.CostRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CostTrackingControllerTest {

    @Mock CostRepository costRepository;

    private CostRecord record(long inputTokens, long outputTokens, double cost, boolean accepted) {
        return new CostRecord("id1", "vuln1", "fix1", "CWE-89", "gpt-4o", "GENERATOR",
                inputTokens, outputTokens, cost, accepted, Instant.now());
    }

    @Test
    void summary_aggregatesTokensCostAndAcceptedSplit() {
        when(costRepository.findByRecordedAtAfter(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(record(1000, 500, 0.01, true), record(2000, 1000, 0.02, false)));

        var response = new CostTrackingController(costRepository).summary(7);

        var body = response.getBody();
        assertThat(body.get("totalRecords")).isEqualTo(2);
        assertThat(body.get("totalInputTokens")).isEqualTo(3000L);
        assertThat(body.get("totalOutputTokens")).isEqualTo(1500L);
        assertThat(body.get("totalCostUsd")).isEqualTo("0.0300");
        assertThat(body.get("acceptedFixes")).isEqualTo(1L);
        assertThat(body.get("rejectedFixes")).isEqualTo(1L);
    }

    @Test
    void summary_usesRequestedDaysWindow() {
        when(costRepository.findByRecordedAtAfter(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        new CostTrackingController(costRepository).summary(30);

        var captor = ArgumentCaptor.forClass(Instant.class);
        verify(costRepository).findByRecordedAtAfter(captor.capture());
        long daysAgo = java.time.Duration.between(captor.getValue(), Instant.now()).toDays();
        assertThat(daysAgo).isBetween(29L, 30L);
    }

    @Test
    void byCwe_delegatesToRepository() {
        var summary = new CostRepository.CostSummaryByCwe("CWE-89", 1.5, 1500L, 10L);
        when(costRepository.sumCostByCwe(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(summary));

        var response = new CostTrackingController(costRepository).byCwe(30);

        assertThat(response.getBody()).hasSize(1);
    }

    @Test
    void byModel_delegatesToRepository() {
        var summary = new CostRepository.CostSummaryByModel("gpt-4o", 1.5, 300L);
        when(costRepository.sumCostByModel(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(summary));

        var response = new CostTrackingController(costRepository).byModel(30);

        assertThat(response.getBody()).hasSize(1);
    }
}
