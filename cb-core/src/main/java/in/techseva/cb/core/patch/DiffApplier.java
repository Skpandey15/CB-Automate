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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DiffApplier {

    private static final Logger log = LoggerFactory.getLogger(DiffApplier.class);
    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,(\\d+))? @@.*");
    private static final Pattern FILE_HEADER = Pattern.compile("^\\+\\+\\+ b/(.+)$");

    public void applyDiff(String repoRoot, String unifiedDiff) throws IOException {
        if (unifiedDiff == null || unifiedDiff.isBlank()) {
            log.debug("Empty diff, nothing to apply");
            return;
        }

        List<FilePatch> patches = parseDiff(unifiedDiff);
        for (FilePatch patch : patches) {
            Path targetFile = Paths.get(repoRoot, patch.filePath());
            if (!Files.exists(targetFile)) {
                log.warn("Target file not found, skipping: {}", targetFile);
                continue;
            }
            List<String> lines = new ArrayList<>(Files.readAllLines(targetFile));
            applyHunks(lines, patch.hunks());
            Files.write(targetFile, lines);
            log.info("Applied patch to {}", targetFile);
        }
    }

    private List<FilePatch> parseDiff(String diff) throws IOException {
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
