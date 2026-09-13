package in.techseva.cb.pr.service;

import in.techseva.cb.core.domain.Fix;
import in.techseva.cb.core.domain.FixStatus;
import in.techseva.cb.core.domain.Severity;
import in.techseva.cb.core.domain.Vulnerability;
import in.techseva.cb.core.domain.VulnerabilityStatus;
import in.techseva.cb.core.domain.VulnerabilityType;
import in.techseva.cb.core.events.FixValidatedEvent;
import in.techseva.cb.core.ontology.OntologyMapper;
import in.techseva.cb.core.repository.FixRepository;
import in.techseva.cb.core.repository.VulnerabilityRepository;
import in.techseva.cb.core.service.AuditService;
import in.techseva.cb.pr.client.GitHubEnterpriseClient;
import in.techseva.cb.pr.client.Version1Client;
import in.techseva.cb.pr.kafka.FeedbackKafkaPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PRService is the single-fix PR-creation path (as opposed to
 * PRPollerService's batch path, covered separately). These tests focus on
 * the OPA gate and the two branches it leads to, since that gate is the
 * safety mechanism ADR-0007 is built around.
 */
@ExtendWith(MockitoExtension.class)
class PRServiceTest {

    @Mock GitHubEnterpriseClient githubClient;
    @Mock Version1Client version1Client;
    @Mock OntologyMapper ontologyMapper;
    @Mock OpaGovernanceService opaGovernance;
    @Mock FeedbackKafkaPublisher feedbackPublisher;
    @Mock FixRepository fixRepo;
    @Mock VulnerabilityRepository vulnerabilityRepo;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock AuditService auditService;

    private PRService newService() {
        return new PRService(githubClient, version1Client, ontologyMapper, opaGovernance,
                feedbackPublisher, fixRepo, vulnerabilityRepo, eventPublisher, auditService,
                "acme", "widgets", "/tmp/does-not-matter", "gh-token");
    }

    private Vulnerability vuln() {
        return new Vulnerability("vuln1", "SONAR-001", "CWE-89", Severity.CRITICAL,
                "java:S2078", 42, "30min", VulnerabilityStatus.FIX_VALIDATED,
                "Potential SQL injection", "A03:2021", "my-proj",
                "my-proj:src/Foo.java", "src/Foo.java", null, 0,
                Instant.now(), Instant.now(), null, null,
                VulnerabilityType.CODE, null, null, null, null, null);
    }

    private Fix fix() {
        return new Fix("fix1", "vuln1", "--- a\n+++ b\n", null, "parameterize",
                0.9, "gpt-4o", "explanation", null, null, null, null,
                FixStatus.BUILD_VALIDATED, true, "build ok", 100, Instant.now());
    }

    @Test
    void onFixValidated_opaDenies_neverCallsGitHubAndMarksFailed() {
        Vulnerability vuln = vuln();
        Fix fix = fix();
        when(opaGovernance.evaluate(vuln, fix))
                .thenReturn(new OpaGovernanceService.GovernanceResult(false, List.of("no tests changed")));

        newService().onFixValidated(new FixValidatedEvent(this, vuln, fix));

        verify(githubClient, never()).createPullRequest(any(), any(), any(), any(), any());
        verify(feedbackPublisher).publishRejected(fix, vuln, List.of("no tests changed"));

        var vulnCaptor = ArgumentCaptor.forClass(Vulnerability.class);
        verify(vulnerabilityRepo).save(vulnCaptor.capture());
        assertThat(vulnCaptor.getValue().status()).isEqualTo(VulnerabilityStatus.FAILED);
        verify(auditService).log(eq(fix.id()), eq("Fix"), eq("OPA_BLOCKED"), eq("pr-service"), any());
    }

    @Test
    void onFixValidated_githubReturnsNull_recordsFailureWithoutThrowing() {
        Vulnerability vuln = vuln();
        Fix fix = fix();
        when(opaGovernance.evaluate(vuln, fix))
                .thenReturn(OpaGovernanceService.GovernanceResult.allow());
        // commitAndPush will fail against a nonexistent /tmp path before GitHub is
        // ever called -- onFixValidated must catch that and record PR_FAILED rather
        // than let the exception propagate out of the @Async event listener.
        newService().onFixValidated(new FixValidatedEvent(this, vuln, fix));

        verify(githubClient, never()).createPullRequest(any(), any(), any(), any(), any());
        verify(auditService).log(eq(fix.id()), eq("Fix"), eq("PR_FAILED"), eq("pr-service"), any());
        verify(feedbackPublisher, never()).publishAccepted(any(), any());
    }
}
