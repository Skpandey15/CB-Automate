package in.techseva.cb.patcher.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class BuildValidator {

    private static final Logger log = LoggerFactory.getLogger(BuildValidator.class);
    private static final int LOG_TAIL_LINES = 200;

    private final int buildTimeoutMinutes;
    private final String repoRoot;

    public BuildValidator(
            @Value("${patcher.build-timeout-minutes:10}") int buildTimeoutMinutes,
            @Value("${patcher.repo-root:/workspace/repo}") String repoRoot) {
        this.buildTimeoutMinutes = buildTimeoutMinutes;
        this.repoRoot = repoRoot;
    }

    public BuildResult runBuild() {
        Path workDir = Paths.get(repoRoot);
        log.info("Running build in {}", workDir);

        List<String> command = detectBuildCommand(workDir);

        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();
            List<String> outputLines = new ArrayList<>();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    outputLines.add(line);
                    log.trace("[BUILD] {}", line);
                }
            }

            boolean finished = process.waitFor(buildTimeoutMinutes, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                log.warn("Build timed out after {} minutes", buildTimeoutMinutes);
                return new BuildResult(false, "BUILD TIMEOUT after " + buildTimeoutMinutes + " minutes");
            }

            int exitCode = process.exitValue();
            boolean success = exitCode == 0;
            String tail = tailLines(outputLines, LOG_TAIL_LINES);
            log.info("Build finished: exitCode={} success={}", exitCode, success);
            return new BuildResult(success, tail);

        } catch (Exception e) {
            log.error("Build execution failed: {}", e.getMessage(), e);
            return new BuildResult(false, "BUILD ERROR: " + e.getMessage());
        }
    }

    private String tailLines(List<String> lines, int n) {
        int start = Math.max(0, lines.size() - n);
        return String.join("\n", lines.subList(start, lines.size()));
    }

    private List<String> detectBuildCommand(Path workDir) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (workDir.resolve("pom.xml").toFile().exists()) {
            log.info("Detected Maven build (pom.xml found)");
            return windows
                    ? List.of("cmd", "/c", "mvn", "test", "-B", "-q")
                    : List.of("mvn", "test", "-B", "-q");
        }
        log.info("Detected Gradle build (falling back to gradlew)");
        return windows
                ? List.of("cmd", "/c", "gradlew.bat", "build", "test", "--no-daemon", "--continue")
                : List.of("./gradlew", "build", "test", "--no-daemon", "--continue");
    }

    public record BuildResult(boolean success, String buildLog) {}
}
