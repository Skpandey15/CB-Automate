package in.techseva.cb.scanner.service;

import in.techseva.cb.core.domain.Severity;
import in.techseva.cb.core.domain.Vulnerability;
import in.techseva.cb.core.domain.VulnerabilityStatus;
import in.techseva.cb.core.events.VulnerabilityDetectedEvent;
import in.techseva.cb.core.repository.VulnerabilityRepository;
import in.techseva.cb.core.service.AuditService;
import in.techseva.cb.scanner.client.SonarQubeClient;
import in.techseva.cb.scanner.client.SonarQubeClient.SonarIssue;
import in.techseva.cb.scanner.client.SonarQubeClient.SonarIssuesResponse;
import in.techseva.cb.scanner.mapper.SonarIssueMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class ScannerService {

    private static final Logger log = LoggerFactory.getLogger(ScannerService.class);

    private final SonarQubeClient sonarClient;
    private final VulnerabilityRepository vulnerabilityRepo;
    private final SonarIssueMapper issueMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditService auditService;
    private final List<String> monitoredProjects;

    public ScannerService(SonarQubeClient sonarClient,
                          VulnerabilityRepository vulnerabilityRepo,
                          SonarIssueMapper issueMapper,
                          ApplicationEventPublisher eventPublisher,
                          AuditService auditService,
                          @org.springframework.beans.factory.annotation.Value(
                                  "${scanner.projects:}") List<String> monitoredProjects) {
        this.sonarClient = sonarClient;
        this.vulnerabilityRepo = vulnerabilityRepo;
        this.issueMapper = issueMapper;
        this.eventPublisher = eventPublisher;
        this.auditService = auditService;
        this.monitoredProjects = monitoredProjects;
    }

    @Scheduled(fixedDelayString = "${scanner.poll-interval-ms:300000}")
    public void scheduledScan() {
        log.info("Starting scheduled scan for {} projects", monitoredProjects.size());
        monitoredProjects.forEach(this::scanProject);
    }

    public int scanProject(String projectKey) {
        log.info("Scanning project: {}", projectKey);
        int newFindings = 0;
        int page = 1;
        int total;

        do {
            SonarIssuesResponse response = sonarClient.getSecurityIssues(projectKey, page);
            if (response == null || response.issues() == null) break;
            total = response.paging().total();

            for (SonarIssue issue : response.issues()) {
                if (!vulnerabilityRepo.existsBySonarIssueKey(issue.key())) {
                    Vulnerability vuln = issueMapper.toVulnerability(issue, projectKey);
                    Vulnerability saved = vulnerabilityRepo.save(vuln);
                    auditService.log(saved.id(), "Vulnerability", "DETECTED",
                            "scanner", Map.of("sonarKey", issue.key(), "severity", issue.severity()));
                    eventPublisher.publishEvent(new VulnerabilityDetectedEvent(this, saved));
                    newFindings++;
                    log.debug("New vulnerability detected: sonarKey={} severity={}", issue.key(), issue.severity());
                }
            }
            page++;
        } while ((long) (page - 1) * 100 < total);

        log.info("Scan complete for project={}: {} new findings", projectKey, newFindings);
        return newFindings;
    }
}
