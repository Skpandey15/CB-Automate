package in.techseva.cb.pr.service;

import in.techseva.cb.core.domain.Fix;
import in.techseva.cb.core.domain.FixStatus;
import in.techseva.cb.core.domain.Severity;
import in.techseva.cb.core.domain.Vulnerability;
import in.techseva.cb.core.domain.VulnerabilityStatus;
import in.techseva.cb.core.domain.VulnerabilityType;
import in.techseva.cb.core.ontology.OntologyMapper;
import in.techseva.cb.core.repository.FixRepository;
import in.techseva.cb.core.repository.VulnerabilityRepository;
import in.techseva.cb.core.service.AuditService;
import in.techseva.cb.pr.client.GitHubEnterpriseClient;
import in.techseva.cb.pr.kafka.FeedbackKafkaPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for the OPA governance bypass documented in ADR-0007:
 * PRPollerService.raiseBatchPR used to call GitHub with no policy check at
 * all. These tests pin that a denied fix is excluded from the batch and
 * never reaches GitHub.
 */
@ExtendWith(MockitoExtension.class)
class PRPollerServiceTest {

    @Mock VulnerabilityRepository vulnerabilityRepo;
    @Mock FixRepository fixRepo;
    @Mock GitHubEnterpriseClient githubClient;
    @Mock OntologyMapper ontologyMapper;
    @Mock AuditService auditService;
    @Mock AIReviewService aiReviewService;
    @Mock OpaGovernanceService opaGovernance;
    @Mock FeedbackKafkaPublisher feedbackPublisher;

    private PRPollerService newService() {
        return new PRPollerService(vulnerabilityRepo, fixRepo, githubClient, ontologyMapper,
                auditService, aiReviewService, opaGovernance, feedbackPublisher,
                "/tmp/does-not-matter", "acme", "widgets", "token", "");
    }

    private Vulnerability vuln() {
        return new Vulnerability("id1", "SONAR-001", "CWE-89", Severity.CRITICAL,
                "java:S2078", 42, "30min", VulnerabilityStatus.FIX_VALIDATED,
                "Potential SQL injection", "A03:2021", "my-proj",
                "my-proj:src/Foo.java", "src/Foo.java", null, 0,
                Instant.now(), Instant.now(), null, null,
                VulnerabilityType.CODE, null, null, null, null, null);
    }

    private Fix fix() {
        return new Fix("fix1", "id1", "--- a\n+++ b\n", null, "parameterize",
                0.9, "gpt-4o", "explanation", null, null, null, null,
                FixStatus.BUILD_VALIDATED, true, "build ok", 100, Instant.now());
    }

    @Test
    void pollForValidatedFixes_opaDenies_neverCallsGitHubAndMarksFailed() {
        Vulnerability vuln = vuln();
        Fix fix = fix();
        when(vulnerabilityRepo.findByStatus(VulnerabilityStatus.FIX_VALIDATED)).thenReturn(List.of(vuln));
        when(fixRepo.findFirstByVulnerabilityIdOrderByGeneratedAtDesc(vuln.id())).thenReturn(Optional.of(fix));
        when(opaGovernance.evaluate(vuln, fix))
                .thenReturn(new OpaGovernanceService.GovernanceResult(false, List.of("no tests changed")));

        newService().pollForValidatedFixes();

        verify(githubClient, never()).createPullRequest(any(), any(), any(), any(), any());
        verify(feedbackPublisher).publishRejected(fix, vuln, List.of("no tests changed"));

        ArgumentCaptor<Vulnerability> savedCaptor = ArgumentCaptor.forClass(Vulnerability.class);
        verify(vulnerabilityRepo).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().status()).isEqualTo(VulnerabilityStatus.FAILED);
    }

    @Test
    void pollForValidatedFixes_noBuildValidatedFixes_neverCallsOpa() {
        Vulnerability vuln = vuln();
        Fix notYetValidated = fix().withStatus(FixStatus.PENDING);
        when(vulnerabilityRepo.findByStatus(VulnerabilityStatus.FIX_VALIDATED)).thenReturn(List.of(vuln));
        when(fixRepo.findFirstByVulnerabilityIdOrderByGeneratedAtDesc(vuln.id()))
                .thenReturn(Optional.of(notYetValidated));

        newService().pollForValidatedFixes();

        verify(opaGovernance, never()).evaluate(any(), any());
        verify(githubClient, never()).createPullRequest(any(), any(), any(), any(), any());
    }
}
