package in.techseva.cb.scanner;

import in.techseva.cb.core.domain.Vulnerability;
import in.techseva.cb.core.domain.VulnerabilityStatus;
import in.techseva.cb.core.events.VulnerabilityDetectedEvent;
import in.techseva.cb.core.repository.VulnerabilityRepository;
import in.techseva.cb.core.service.AuditService;
import in.techseva.cb.scanner.client.SonarQubeClient;
import in.techseva.cb.scanner.mapper.SonarIssueMapper;
import in.techseva.cb.scanner.service.ScannerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScannerServiceTest {

    @Mock SonarQubeClient sonarClient;
    @Mock VulnerabilityRepository vulnerabilityRepo;
    @Mock SonarIssueMapper issueMapper;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock AuditService auditService;

    @InjectMocks ScannerService scannerService;

    @Test
    void scanProject_newIssue_publishesEvent() {
        var issue = new SonarQubeClient.SonarIssue(
                "SONAR-001", "java:S2078", "CRITICAL", "my-proj:src/Foo.java",
                "my-proj", 42, "Potential SQL injection", "30min",
                List.of("owasp-a3"), "VULNERABILITY");

        var paging = new SonarQubeClient.SonarPaging(1, 100, 1);
        var response = new SonarQubeClient.SonarIssuesResponse(paging, List.of(issue));

        var vuln = new Vulnerability("id1", "SONAR-001", "CWE-89", null,
                "java:S2078", 42, "30min", VulnerabilityStatus.DETECTED,
                "Potential SQL injection", "A03:2021", "my-proj",
                "my-proj:src/Foo.java", "src/Foo.java", null, 0,
                Instant.now(), Instant.now(), null, null);

        when(sonarClient.getSecurityIssues("my-proj", 1)).thenReturn(response);
        when(vulnerabilityRepo.existsBySonarIssueKey("SONAR-001")).thenReturn(false);
        when(issueMapper.toVulnerability(issue, "my-proj")).thenReturn(vuln);
        when(vulnerabilityRepo.save(any())).thenReturn(vuln);

        // Need to inject monitoredProjects — use direct instantiation
        var svc = new ScannerService(sonarClient, vulnerabilityRepo, issueMapper,
                eventPublisher, auditService, List.of("my-proj"));

        int count = svc.scanProject("my-proj");

        assertThat(count).isEqualTo(1);
        var captor = ArgumentCaptor.forClass(VulnerabilityDetectedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getVulnerability().sonarIssueKey()).isEqualTo("SONAR-001");
    }

    @Test
    void scanProject_existingIssue_skipped() {
        var issue = new SonarQubeClient.SonarIssue(
                "SONAR-002", "java:S2078", "MAJOR", "my-proj:src/Bar.java",
                "my-proj", 10, "SQL injection", "1h", List.of(), "VULNERABILITY");

        var paging = new SonarQubeClient.SonarPaging(1, 100, 1);
        var response = new SonarQubeClient.SonarIssuesResponse(paging, List.of(issue));

        when(sonarClient.getSecurityIssues("my-proj", 1)).thenReturn(response);
        when(vulnerabilityRepo.existsBySonarIssueKey("SONAR-002")).thenReturn(true);

        var svc = new ScannerService(sonarClient, vulnerabilityRepo, issueMapper,
                eventPublisher, auditService, List.of("my-proj"));
        int count = svc.scanProject("my-proj");

        assertThat(count).isEqualTo(0);
        verify(eventPublisher, never()).publishEvent(any());
    }
}
