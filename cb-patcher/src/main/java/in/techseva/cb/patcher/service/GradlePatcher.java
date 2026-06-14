package in.techseva.cb.patcher.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class GradlePatcher {

    private static final Logger log = LoggerFactory.getLogger(GradlePatcher.class);
    // Format: group:artifact:oldVersion -> newVersion
    private static final Pattern PATCH_LINE = Pattern.compile(
            "^([\\w.-]+):([\\w.-]+):([\\w.\\-]+)\\s*->\\s*([\\w.\\-]+)$");

    public void applyGradlePatch(String repoRoot, String gradlePatch) throws IOException {
        if (gradlePatch == null || gradlePatch.isBlank()) return;

        for (String patchLine : gradlePatch.split("\\n")) {
            patchLine = patchLine.strip();
            if (patchLine.isEmpty() || patchLine.startsWith("#")) continue;
            Matcher m = PATCH_LINE.matcher(patchLine);
            if (!m.matches()) {
                log.warn("Unrecognized gradle patch line: {}", patchLine);
                continue;
            }
            String group = m.group(1);
            String artifact = m.group(2);
            String oldVer = m.group(3);
            String newVer = m.group(4);
            patchGradleFile(repoRoot, group, artifact, oldVer, newVer);
        }
    }

    private void patchGradleFile(String repoRoot, String group, String artifact,
                                  String oldVer, String newVer) throws IOException {
        Path buildGradle = Paths.get(repoRoot, "build.gradle");
        if (!Files.exists(buildGradle)) {
            log.warn("build.gradle not found at {}", buildGradle);
            return;
        }

        String oldDep = group + ":" + artifact + ":" + oldVer;
        String newDep = group + ":" + artifact + ":" + newVer;
        List<String> lines = Files.readAllLines(buildGradle);
        boolean changed = false;

        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(oldDep)) {
                lines.set(i, lines.get(i).replace(oldDep, newDep));
                changed = true;
                log.info("Patched gradle: {} -> {}", oldDep, newDep);
            }
        }

        if (changed) {
            Files.write(buildGradle, lines);
        } else {
            log.warn("Dependency not found in build.gradle: {}", oldDep);
        }
    }
}
