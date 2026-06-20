package in.techseva.cb.scanner.dependency;

import in.techseva.cb.core.domain.Severity;
import in.techseva.cb.core.domain.Vulnerability;
import in.techseva.cb.core.domain.VulnerabilityStatus;
import in.techseva.cb.core.domain.VulnerabilityType;
import in.techseva.cb.core.repository.VulnerabilityRepository;
import in.techseva.cb.core.service.AuditService;
import in.techseva.cb.scanner.dependency.GradleDependencyParser.ParsedDependency;
import in.techseva.cb.scanner.dependency.OssIndexClient.ComponentReport;
import in.techseva.cb.scanner.dependency.OssIndexClient.OssVulnerability;
import in.techseva.cb.scanner.kafka.VulnerabilityKafkaPublisher;
import org.eclipse.jgit.api.Git;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Scans build.gradle files for vulnerable dependencies via OSS Index,
 * looks up safe versions from Maven Central, and publishes findings
 * to the same vulnerabilities.detected Kafka topic as the SonarQube scanner.
 */
@Service
public class DependencyScannerService {

    private static final Logger log = LoggerFactory.getLogger(DependencyScannerService.class);

    private final GradleDependencyParser gradleParser;
    private final MavenDependencyParser mavenParser;
    private final OssIndexClient ossIndexClient;
    private final DepsDevClient depsDevClient;
    private final MavenCentralClient mavenCentralClient;
    private final VulnerabilityRepository vulnerabilityRepo;
    private final VulnerabilityKafkaPublisher kafkaPublisher;
    private final AuditService auditService;
    private final String repoRoot;
    private final boolean enabled;
    private final String githubOwner;
    private final String githubRepo;
    private final String githubToken;

    public DependencyScannerService(
            GradleDependencyParser gradleParser,
            MavenDependencyParser mavenParser,
            OssIndexClient ossIndexClient,
            DepsDevClient depsDevClient,
            MavenCentralClient mavenCentralClient,
            VulnerabilityRepository vulnerabilityRepo,
            VulnerabilityKafkaPublisher kafkaPublisher,
            AuditService auditService,
            @Value("${dep.scanner.repo-root:/workspace/repo}") String repoRoot,
            @Value("${dep.scanner.enabled:true}") boolean enabled,
            @Value("${github.owner:}") String githubOwner,
            @Value("${github.repo:}") String githubRepo,
            @Value("${github.token:}") String githubToken) {
        this.gradleParser = gradleParser;
        this.mavenParser = mavenParser;
        this.ossIndexClient = ossIndexClient;
        this.depsDevClient = depsDevClient;
        this.mavenCentralClient = mavenCentralClient;
        this.vulnerabilityRepo = vulnerabilityRepo;
        this.kafkaPublisher = kafkaPublisher;
        this.auditService = auditService;
        this.repoRoot = repoRoot;
        this.enabled = enabled;
        this.githubOwner = githubOwner;
        this.githubRepo = githubRepo;
        this.githubToken = githubToken;
    }

    @Scheduled(fixedDelayString = "${dep.scanner.poll-interval-ms:600000}")
    public void scheduledScan() {
        if (!enabled) return;
        log.info("Starting scheduled dependency vulnerability scan");
        scanDependencies("gradle-project");
    }

    /**
     * Scans the configured repo root for dependency vulnerabilities.
     * @return count of new vulnerability findings
     */
    public int scanDependencies(String projectKey) {
        if (!enabled) {
            log.info("Dependency scanner disabled (dep.scanner.enabled=false)");
            return 0;
        }
        if (repoRoot == null || repoRoot.isBlank()) {
            log.warn("dep.scanner.repo-root not configured — skipping dependency scan");
            return 0;
        }

        Path root = Paths.get(repoRoot);
        try {
            ensureRepoCloned(root);
        } catch (Exception e) {
            log.error("Failed to clone/update repo: {}", e.getMessage(), e);
            return 0;
        }

        try {
            return doScan(root, projectKey);
        } catch (Exception e) {
            log.error("Dependency scan failed: {}", e.getMessage(), e);
            return 0;
        }
    }

    private void ensureRepoCloned(Path root) throws Exception {
        if (!githubOwner.isBlank() && !githubRepo.isBlank()) {
            String cloneUrl = "https://github.com/" + githubOwner + "/" + githubRepo + ".git";
            File repoDir = root.toFile();
            if (!new File(repoDir, ".git").exists()) {
                log.info("Cloning {} into {}", cloneUrl, root);
                var cloneCmd = Git.cloneRepository().setURI(cloneUrl).setDirectory(repoDir);
                if (!githubToken.isBlank()) {
                    cloneCmd.setCredentialsProvider(
                        new org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider("token", githubToken));
                }
                cloneCmd.call().close();
                log.info("Clone complete: {}", root);
            } else {
                log.info("Repo already cloned at {} — pulling latest", root);
                try (Git git = Git.open(repoDir)) {
                    var pullCmd = git.pull();
                    if (!githubToken.isBlank()) {
                        pullCmd.setCredentialsProvider(
                            new org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider("token", githubToken));
                    }
                    pullCmd.call();
                }
            }
        } else {
            log.warn("github.owner/repo not configured — scanning existing repo at {}", root);
        }
    }

    private int doScan(Path root, String projectKey) throws Exception {
        List<ParsedDependency> deps = parseDependencies(root);
        log.info("Parsed {} unique dependencies from {}", deps.size(), root);

        if (deps.isEmpty()) return 0;

        int newFindings = 0;
        for (ParsedDependency dep : deps) {
            List<DepsDevClient.Advisory> advisories = depsDevClient.getAdvisories(
                    dep.group(), dep.artifact(), dep.version());

            if (advisories.isEmpty()) {
                // Fallback to OSS Index for deps not in deps.dev
                List<ComponentReport> reports = ossIndexClient.queryVulnerabilities(List.of(dep.purl()));
                for (ComponentReport report : reports) {
                    for (OssVulnerability ossVuln : report.vulnerabilities()) {
                        String dedupKey = "DEP:" + dep.coordinate() + ":" + ossVuln.id();
                        if (vulnerabilityRepo.existsBySonarIssueKey(dedupKey)) continue;
                        String safeVersion = mavenCentralClient.latestStableVersion(dep.group(), dep.artifact()).orElse(null);
                        Vulnerability vuln = buildVulnerability(dep, ossVuln, safeVersion, projectKey, dedupKey);
                        vulnerabilityRepo.save(vuln);
                        auditService.log(vuln.id(), "Vulnerability", "DETECTED", "dep-scanner",
                                Map.of("purl", dep.purl(), "cve", ossVuln.cve() != null ? ossVuln.cve() : ""));
                        kafkaPublisher.publish(vuln);
                        newFindings++;
                        log.info("OSS Index vuln: {}:{} cve={}", dep.group(), dep.artifact(), ossVuln.cve());
                    }
                }
                continue;
            }

            log.info("deps.dev: {}:{} has {} advisories", dep.group(), dep.artifact(), advisories.size());
            for (DepsDevClient.Advisory advisory : advisories) {
                String dedupKey = "DEP:" + dep.coordinate() + ":" + advisory.id();
                if (vulnerabilityRepo.existsBySonarIssueKey(dedupKey)) {
                    log.debug("Skipping already-known dep vuln: {}", dedupKey);
                    continue;
                }
                String safeVersion = mavenCentralClient.latestStableVersion(dep.group(), dep.artifact()).orElse(null);
                Vulnerability vuln = buildVulnerabilityFromAdvisory(dep, advisory, safeVersion, projectKey, dedupKey);
                Vulnerability saved = vulnerabilityRepo.save(vuln);
                auditService.log(saved.id(), "Vulnerability", "DETECTED", "dep-scanner",
                        Map.of("purl", dep.purl(), "cve", advisory.cveId() != null ? advisory.cveId() : ""));
                kafkaPublisher.publish(saved);
                newFindings++;
                log.info("New dep vuln (deps.dev): {}:{} advisory={} cve={} safeVersion={}",
                        dep.group(), dep.artifact(), advisory.id(), advisory.cveId(), safeVersion);
            }
        }

        log.info("Dependency scan complete: {} new findings", newFindings);
        return newFindings;
    }

    private List<ParsedDependency> parseDependencies(Path root) throws Exception {
        List<ParsedDependency> deps = new ArrayList<>();
        deps.addAll(gradleParser.parseDependencies(root));
        deps.addAll(mavenParser.parseDependencies(root));
        // De-duplicate by coordinate across both parsers
        return deps.stream()
                .collect(java.util.stream.Collectors.toMap(
                        ParsedDependency::coordinate,
                        d -> d,
                        (a, b) -> a))
                .values()
                .stream()
                .toList();
    }

    private Vulnerability buildVulnerabilityFromAdvisory(ParsedDependency dep, DepsDevClient.Advisory advisory,
                                                          String safeVersion, String projectKey, String dedupKey) {
        String cveId   = advisory.cveId();
        String cweId   = "CWE-1104";
        Severity sev   = cvssToSeverity(advisory.severity());
        String message = dep.coordinate() + " has a known vulnerability";
        if (advisory.title() != null) message += ": " + advisory.title();
        if (cveId != null) message += " (" + cveId + ")";
        if (safeVersion != null) message += ". Safe version: " + safeVersion;

        return new Vulnerability(null, dedupKey, cweId, sev,
                "DEP:" + cweId, 0, null, VulnerabilityStatus.DETECTED,
                message, "A06:2021", projectKey, dep.group() + ":" + dep.artifact(),
                dep.sourceFile().toString(), null, 0,
                Instant.now(), Instant.now(), null, null,
                VulnerabilityType.DEPENDENCY, dep.group(), dep.artifact(),
                dep.version(), safeVersion, cveId);
    }

    private Vulnerability buildVulnerability(ParsedDependency dep, OssVulnerability ossVuln,
                                              String safeVersion, String projectKey, String dedupKey) {
        String cweId   = normalizeCwe(ossVuln.cwe());
        Severity sev   = cvssToSeverity(ossVuln.severity());
        String message = buildMessage(dep, ossVuln, safeVersion);

        return new Vulnerability(
                null,
                dedupKey,
                cweId,
                sev,
                "DEP:" + cweId,
                0,                          // no line number for a dep
                null,
                VulnerabilityStatus.DETECTED,
                message,
                "A06:2021",                 // OWASP A06 — Vulnerable and Outdated Components
                projectKey,
                dep.group() + ":" + dep.artifact(),
                dep.sourceFile().toString(), // path to build.gradle that declared the dep
                null,
                0,
                Instant.now(),
                Instant.now(),
                null,
                null,
                VulnerabilityType.DEPENDENCY,
                dep.group(),
                dep.artifact(),
                dep.version(),
                safeVersion,
                ossVuln.cve()
        );
    }

    private String buildMessage(ParsedDependency dep, OssVulnerability ossVuln, String safeVersion) {
        StringBuilder sb = new StringBuilder();
        sb.append(dep.coordinate()).append(" has a known vulnerability");
        if (ossVuln.cve() != null) sb.append(" (").append(ossVuln.cve()).append(")");
        if (ossVuln.title() != null) sb.append(": ").append(ossVuln.title());
        if (safeVersion != null) sb.append(". Safe version: ").append(safeVersion);
        return sb.toString();
    }

    private String normalizeCwe(String cwe) {
        if (cwe == null || cwe.isBlank()) return "CWE-1104";
        String upper = cwe.toUpperCase();
        if (upper.startsWith("CWE-")) return upper;
        if (upper.matches("\\d+")) return "CWE-" + upper;
        return "CWE-1104";
    }

    private Severity cvssToSeverity(double cvss) {
        if (cvss >= 9.0) return Severity.BLOCKER;
        if (cvss >= 7.0) return Severity.CRITICAL;
        if (cvss >= 4.0) return Severity.MAJOR;
        if (cvss >= 1.0) return Severity.MINOR;
        return Severity.INFO;
    }

    private ParsedDependency findDep(List<ParsedDependency> deps, String ossCoordinates) {
        // OSS Index purl: pkg:maven/group/artifact@version
        // Our purl:       pkg:maven/group/artifact@version
        return deps.stream()
                .filter(d -> d.purl().equals(ossCoordinates))
                .findFirst()
                .orElse(null);
    }
}
