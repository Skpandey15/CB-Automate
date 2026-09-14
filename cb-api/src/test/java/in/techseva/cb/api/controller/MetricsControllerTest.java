package in.techseva.cb.api.controller;

import in.techseva.cb.core.domain.VulnerabilityStatus;
import in.techseva.cb.core.repository.FixRepository;
import in.techseva.cb.core.repository.VulnerabilityRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MetricsControllerTest {

    @Mock VulnerabilityRepository vulnRepo;
    @Mock FixRepository fixRepo;

    @SuppressWarnings("unchecked")
    @Test
    void metrics_computesRemediationRateFromResolvedOverTotal() {
        lenient().when(vulnRepo.countByStatus(VulnerabilityStatus.RESOLVED)).thenReturn(3L);
        when(vulnRepo.count()).thenReturn(10L);

        var response = new MetricsController(vulnRepo, fixRepo).metrics();

        var body = response.getBody();
        assertThat(body).containsKey("vulnerabilities");
        assertThat(body).containsKey("fixes");
        assertThat(((java.util.Map<String, Long>) body.get("vulnerabilities")).get("RESOLVED")).isEqualTo(3L);
        assertThat(body.get("remediationRate")).isEqualTo("30.0%");
    }

    @Test
    void metrics_zeroTotalVulnerabilities_reportsRateAsNotApplicable() {
        when(vulnRepo.count()).thenReturn(0L);

        var response = new MetricsController(vulnRepo, fixRepo).metrics();

        assertThat(response.getBody().get("remediationRate")).isEqualTo("N/A");
    }
}
