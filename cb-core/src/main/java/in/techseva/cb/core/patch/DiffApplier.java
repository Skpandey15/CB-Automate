package in.techseva.cb.core.patch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DiffApplier {

    private static final Logger log = LoggerFactory.getLogger(DiffApplier.class);
    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,(\\d+))? @@.*");
    private static final Pattern FILE_HEADER = Pattern.compile("^\\+\\+\\+ b/(.+)$");

    public void applyDiff(String repoRoot, String unifiedDiff) throws IOException {
        applyDiff(repoRoot, unifiedDiff, null);
    }

    public void applyDiff(String repoRoot, String unifiedDiff, String fallbackFilePath) throws IOException {
        if (unifiedDiff == null || unifiedDiff.isBlank()) {
            log.debug("Empty diff, nothing to apply");
            return;
        }

        List<FilePatch> patches = parseDiff(stripCodeFences(unifiedDiff), fallbackFilePath);
        Path repoPath = Paths.get(repoRoot);
        for (FilePatch patch : patches) {
            Path targetFile = resolveFile(repoPath, patch.filePath());
            if (targetFile == null) {
                log.warn("Target file not found anywhere under {}: {}", repoRoot, patch.filePath());
                continue;
            }
            List<String> lines = new ArrayList<>(Files.readAllLines(targetFile));
            applyHunks(lines, patch.hunks());
            Files.write(targetFile, lines);
            log.info("Applied patch to {}", targetFile);
        }
    }

    /**
     * Resolves the diff file path to an actual file under repoRoot.
     * Tries the path as-is first, then searches one level of subdirectories
     * (handles repos where source lives in a subdirectory like springboot-microservices/).
     */
    private Path resolveFile(Path repoRoot, String diffPath) throws IOException {
        // Try direct path first
        Path direct = repoRoot.resolve(diffPath);
        if (Files.exists(direct)) return direct;

        // Try each immediate subdirectory (e.g. springboot-microservices/auth-service/...)
        try (var children = Files.list(repoRoot)) {
            Optional<Path> found = children
                .filter(Files::isDirectory)
                .map(sub -> sub.resolve(diffPath))
                .filter(Files::exists)
                .findFirst();
            if (found.isPresent()) {
                log.debug("Resolved {} under subdirectory {}", diffPath, found.get());
                return found.get();
            }
        }

        // Search the whole tree by filename as last resort
        String fileName = Paths.get(diffPath).getFileName().toString();
        try (var walk = Files.walk(repoRoot)) {
            Optional<Path> found = walk
                .filter(p -> p.getFileName().toString().equals(fileName))
                .filter(p -> p.toString().replace('\\', '/').endsWith(diffPath.replace('\\', '/')))
                .findFirst();
            return found.orElse(null);
        }
    }

    private String stripCodeFences(String diff) {
        String trimmed = diff.strip();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) trimmed = trimmed.substring(firstNewline + 1).stripLeading();
            int lastFence = trimmed.lastIndexOf("```");
            if (lastFence > 0) trimmed = trimmed.substring(0, lastFence).stripTrailing();
        }
        return trimmed;
    }

    private List<FilePatch> parseDiff(String diff, String fallbackFilePath) throws IOException {
        List<FilePatch> patches = new ArrayList<>();
        String currentFile = null;
        List<Hunk> hunks = new ArrayList<>();
        List<String> hunkLines = null;
        int hunkStart = 0;

        try (BufferedReader reader = new BufferedReader(new StringReader(diff))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher fileMatcher = FILE_HEADER.matcher(line);
                if (fileMatcher.matches()) {
                    if (currentFile != null && hunkLines != null) {
                        hunks.add(new Hunk(hunkStart, new ArrayList<>(hunkLines)));
                    }
                    if (currentFile != null) {
                        patches.add(new FilePatch(currentFile, new ArrayList<>(hunks)));
                    }
                    currentFile = fileMatcher.group(1);
                    hunks = new ArrayList<>();
                    hunkLines = null;
                    continue;
                }
                Matcher hunkMatcher = HUNK_HEADER.matcher(line);
                if (hunkMatcher.matches()) {
                    // If we see a hunk header but no +++ b/ yet, use the fallback file path
                    if (currentFile == null && fallbackFilePath != null) {
                        log.debug("No +++ b/ header found, using fallback filePath: {}", fallbackFilePath);
                        currentFile = fallbackFilePath;
                        hunks = new ArrayList<>();
                    }
                    if (hunkLines != null) {
                        hunks.add(new Hunk(hunkStart, new ArrayList<>(hunkLines)));
                    }
                    hunkStart = Integer.parseInt(hunkMatcher.group(1));
                    hunkLines = new ArrayList<>();
                    continue;
                }
                if (hunkLines != null && (line.startsWith("+") || line.startsWith("-") || line.startsWith(" "))) {
                    hunkLines.add(line);
                }
            }
        }

        if (currentFile != null) {
            if (hunkLines != null) hunks.add(new Hunk(hunkStart, hunkLines));
            patches.add(new FilePatch(currentFile, hunks));
        }

        return patches;
    }

    private void applyHunks(List<String> fileLines, List<Hunk> hunks) {
        int offset = 0;
        for (Hunk hunk : hunks) {
            int lineIdx = hunk.startLine() - 1 + offset;
            List<String> newLines = new ArrayList<>();
            int originalConsumed = 0;

            for (String hunkLine : hunk.lines()) {
                if (hunkLine.startsWith("+")) {
                    newLines.add(hunkLine.substring(1));
                } else if (hunkLine.startsWith("-")) {
                    originalConsumed++;
                } else if (hunkLine.startsWith(" ")) {
                    newLines.add(hunkLine.substring(1));
                    originalConsumed++;
                }
            }

            int removeCount = originalConsumed;
            for (int i = 0; i < removeCount && lineIdx < fileLines.size(); i++) {
                fileLines.remove(lineIdx);
            }
            fileLines.addAll(lineIdx, newLines);
            offset += newLines.size() - removeCount;
        }
    }

    record FilePatch(String filePath, List<Hunk> hunks) {}
    record Hunk(int startLine, List<String> lines) {}
}
