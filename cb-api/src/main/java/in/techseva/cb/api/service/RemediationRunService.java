package in.techseva.cb.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.techseva.cb.api.domain.RemediationRun;
import in.techseva.cb.api.domain.RemediationRunStatus;
import in.techseva.cb.api.repository.RemediationRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Wraps scripts/dependency-remediation/remediate.py (ADR-0001) behind a
 * Java API, per ADR-0002 Track A: given {repoUrl, branch}, run the
 * existing dependency-remediation job and report back its outcome.
 * Deliberately does NOT reimplement any of remediate.py's scan/validate/
 * publish logic -- it only invokes it as a subprocess and reads its
 * report.json, the same job contract the standalone CLI/CI workflow use.
 */
@Service
public class RemediationRunService {

    private static final Logger log = LoggerFactory.getLogger(RemediationRunService.class);

    // Mirrors remediate.py's own validation (remediate.py:634,636,643) --
    // defense in depth, not reliance on the script's validation alone.
    private static final Pattern REPO_PATTERN = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");
    private static final Pattern RECIPIENT_PATTERN = Pattern.compile("[^\\s@,;<>]+@[^\\s@,;<>]+\\.[^\\s@,;<>]+");

    private final RemediationRunRepository repository;
    private final ObjectMapper objectMapper;
    private final String pythonExecutable;
    private final String scriptPath;
    private final Path runsBaseDir;
    private final int timeoutSeconds;

    public RemediationRunService(RemediationRunRepository repository,
                                  ObjectMapper objectMapper,
                                  @Value("${remediation.python-executable:}") String configuredPython,
                                  @Value("${remediation.script-path:scripts/dependency-remediation/remediate.py}") String scriptPath,
                                  @Value("${remediation.runs-dir:.cb-runs}") String runsBaseDir,
                                  @Value("${remediation.timeout-seconds:1200}") int timeoutSeconds) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.pythonExecutable = configuredPython.isBlank() ? defaultPythonExecutable() : configuredPython;
        this.scriptPath = scriptPath;
        this.runsBaseDir = Path.of(runsBaseDir);
        this.timeoutSeconds = timeoutSeconds;
    }

    private static String defaultPythonExecutable() {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return windows ? "python" : "python3";
    }

    public static boolean isValidRepo(String repoUrl) {
        return repoUrl != null && REPO_PATTERN.matcher(repoUrl).matches();
    }

    public static boolean isValidBranch(String branch) {
        if (branch == null || branch.isBlank() || branch.startsWith("-")) return false;
        return branch.chars().noneMatch(c -> c == '\r' || c == '\n' || c == 0);
    }

    public static boolean isValidRecipient(String email) {
        return email != null && RECIPIENT_PATTERN.matcher(email).matches();
    }

    /**
     * Creates and persists the initial RUNNING record only -- does NOT
     * itself kick off the async job. A same-class call from here to
     * runAsync() would be a self-invocation that bypasses Spring's
     * @Async proxy entirely (a well-documented Spring AOP limitation),
     * silently running the whole job synchronously and blocking the
     * caller for up to the full timeout. The caller (RemediationRunController)
     * must invoke runAsync() itself as a separate call on this bean, so
     * the call crosses the proxy boundary correctly.
     */
    public RemediationRun startRun(String repoUrl, String branch, boolean publish, List<String> recipients) {
        String runId = UUID.randomUUID().toString();
        return repository.save(RemediationRun.starting(runId, repoUrl, branch, publish, recipients));
    }

    public Optional<RemediationRun> findRun(String runId) {
        return repository.findById(runId);
    }

    @Async("remediationExecutor")
    public void runAsync(String runId, String repoUrl, String branch, boolean publish, List<String> recipients) {
        Path runDir = runsBaseDir.resolve(runId);
        try {
            Files.createDirectories(runDir);
            List<String> command = buildCommand(repoUrl, branch, publish, recipients, runDir);
            log.info("Starting remediation run={} repo={} branch={} publish={}", runId, repoUrl, branch, publish);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            List<String> outputLines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) outputLines.add(line);
            }

            boolean finished = process.waitFor(timeoutSeconds + 60L, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                complete(runId, RemediationRunStatus.FAILED, null, "Process timed out after " + timeoutSeconds + "s");
                return;
            }

            RunOutcome outcome = readReport(runDir, outputLines);
            log.info("Remediation run={} finished status={}", runId, outcome.status());
            complete(runId, outcome.status(), outcome.reportPath(), outcome.message());

        } catch (Exception e) {
            log.error("Remediation run={} failed to execute: {}", runId, e.getMessage(), e);
            complete(runId, RemediationRunStatus.FAILED, null, "Execution error: " + e.getMessage());
        }
    }

    private List<String> buildCommand(String repoUrl, String branch, boolean publish,
                                       List<String> recipients, Path runDir) {
        List<String> command = new ArrayList<>(List.of(
                pythonExecutable, scriptPath,
                "--repo", repoUrl,
                "--branch", branch,
                "--output", runDir.toString(),
                "--timeout", String.valueOf(timeoutSeconds)
        ));
        if (publish) {
            command.add("--publish");
            for (String recipient : recipients) {
                command.add("--recipient");
                command.add(recipient);
            }
        }
        return command;
    }

    /**
     * remediate.py generates its own run_id and writes to
     * {runDir}/{its_run_id}/report.json -- since we pass a fresh, empty
     * runDir per invocation, there is exactly one child directory once
     * the process completes.
     */
    private RunOutcome readReport(Path runDir, List<String> processOutput) throws Exception {
        Optional<Path> childDir;
        try (Stream<Path> children = Files.list(runDir)) {
            childDir = children.filter(Files::isDirectory).findFirst();
        }
        if (childDir.isEmpty()) {
            return new RunOutcome(RemediationRunStatus.FAILED, null,
                    "No report produced by remediation job. Output: " + tailOf(processOutput));
        }
        Path reportJson = childDir.get().resolve("report.json");
        if (!Files.exists(reportJson)) {
            return new RunOutcome(RemediationRunStatus.FAILED, childDir.get().toString(),
                    "report.json not found. Output: " + tailOf(processOutput));
        }

        JsonNode report = objectMapper.readTree(reportJson.toFile());
        RemediationRunStatus status = parseStatus(report.path("status").asText("failed"));
        String message = buildMessage(report);
        return new RunOutcome(status, reportJson.toString(), message);
    }

    private RemediationRunStatus parseStatus(String raw) {
        try {
            return RemediationRunStatus.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unrecognized remediation status '{}', treating as FAILED", raw);
            return RemediationRunStatus.FAILED;
        }
    }

    private String tailOf(List<String> lines) {
        int from = Math.max(0, lines.size() - 20);
        return String.join(" | ", lines.subList(from, lines.size()));
    }

    private String buildMessage(JsonNode report) {
        if (report.has("error")) {
            return report.path("error").asText();
        }
        int fixed = report.path("fixes").isArray() ? report.path("fixes").size() : 0;
        int remaining = report.path("after").isArray() ? report.path("after").size() : 0;
        return fixed + " fix(es) applied, " + remaining + " unresolved";
    }

    private void complete(String runId, RemediationRunStatus status, String reportPath, String message) {
        repository.findById(runId)
                .map(run -> run.completed(status, reportPath, message))
                .ifPresentOrElse(repository::save,
                        () -> log.warn("RemediationRun {} not found when recording completion", runId));
    }

    private record RunOutcome(RemediationRunStatus status, String reportPath, String message) {}
}
