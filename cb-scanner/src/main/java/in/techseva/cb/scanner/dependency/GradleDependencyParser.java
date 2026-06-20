package in.techseva.cb.scanner.dependency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Gradle build files to extract declared dependency coordinates.
 * Handles explicit versions AND BOM-managed deps (Spring Boot / Spring Cloud).
 */
@Component
public class GradleDependencyParser {

    private static final Logger log = LoggerFactory.getLogger(GradleDependencyParser.class);

    // 'group:artifact:version' or "group:artifact:version"
    private static final Pattern STRING_DEP = Pattern.compile(
            "['\"]([\\w.\\-]+):([\\w.\\-]+):([\\w.\\-]+)['\"]");

    // group: 'com.example', name: 'lib', version: '1.0.0'  (Groovy map notation)
    private static final Pattern MAP_DEP = Pattern.compile(
            "group\\s*:\\s*['\"]([\\w.\\-]+)['\"].*?name\\s*:\\s*['\"]([\\w.\\-]+)['\"].*?version\\s*:\\s*['\"]([\\w.\\-]+)['\"]");

    // BOM-managed: 'group:artifact' (no version) — version comes from Spring BOM
    private static final Pattern UNVERSIONED_DEP = Pattern.compile(
            "(?:implementation|api|compileOnly|runtimeOnly|testImplementation|annotationProcessor)\\s+['\"]([\\w.\\-]+):([\\w.\\-]+)['\"]");

    // Spring Boot plugin version: id 'org.springframework.boot' version '3.x.y'
    private static final Pattern SPRING_BOOT_VERSION = Pattern.compile(
            "id\\s+['\"]org\\.springframework\\.boot['\"]\\s+version\\s+['\"]([\\d.]+)['\"]");

    // Spring Cloud version in ext block: set('springCloudVersion', "2023.x.y")
    private static final Pattern SPRING_CLOUD_VERSION = Pattern.compile(
            "springCloudVersion['\"]?\\s*[,=]\\s*['\"]([\\d.]+)['\"]");

    // ${versions.spring} style variable reference
    private static final Pattern VAR_REF = Pattern.compile("\\$\\{?([\\w.]+)}?");

    public record ParsedDependency(String group, String artifact, String version, Path sourceFile) {
        public String coordinate() { return group + ":" + artifact + ":" + version; }
        public String purl()       { return "pkg:maven/" + group + "/" + artifact + "@" + version; }
    }

    public List<ParsedDependency> parseDependencies(Path repoRoot) throws IOException {
        Properties gradleProps = loadGradleProperties(repoRoot);

        List<Path> buildFiles = new ArrayList<>();
        try (var walk = Files.walk(repoRoot)) {
            walk.filter(p -> {
                String name = p.getFileName().toString();
                return (name.equals("build.gradle") || name.equals("build.gradle.kts"))
                        && !p.toString().contains("build" + java.io.File.separator + "generated");
            }).forEach(buildFiles::add);
        }

        if (!buildFiles.isEmpty()) {
            log.info("Found {} build.gradle file(s) under {}", buildFiles.size(), repoRoot);
        }

        List<ParsedDependency> all = new ArrayList<>();
        for (Path file : buildFiles) {
            all.addAll(parseFile(file, gradleProps));
        }

        return all.stream()
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toMap(
                                ParsedDependency::coordinate,
                                d -> d,
                                (a, b) -> a),
                        m -> new ArrayList<>(m.values())));
    }

    private List<ParsedDependency> parseFile(Path file, Properties gradleProps) {
        List<ParsedDependency> deps = new ArrayList<>();
        try {
            String content = Files.readString(file);

            // Explicit versions
            deps.addAll(matchPattern(STRING_DEP, content, gradleProps, file));
            deps.addAll(matchPattern(MAP_DEP, content, gradleProps, file));

            // BOM-managed (no explicit version) — resolve from Spring Boot / Cloud versions
            deps.addAll(matchUnversioned(content, file));

        } catch (IOException e) {
            log.warn("Could not read {}: {}", file, e.getMessage());
        }
        return deps;
    }

    private List<ParsedDependency> matchUnversioned(String content, Path file) {
        List<ParsedDependency> deps = new ArrayList<>();

        // Extract BOM versions declared in this file
        String springBootVer = extractFirst(SPRING_BOOT_VERSION, content, null);
        String springCloudVer = extractFirst(SPRING_CLOUD_VERSION, content, null);

        if (springBootVer == null && springCloudVer == null) return deps; // no BOM → skip

        Matcher m = UNVERSIONED_DEP.matcher(content);
        while (m.find()) {
            String group    = m.group(1);
            String artifact = m.group(2);

            String version = resolveVersion(group, artifact, springBootVer, springCloudVer);
            if (version == null) {
                log.debug("Cannot resolve BOM version for {}:{} — skipping", group, artifact);
                continue;
            }
            deps.add(new ParsedDependency(group, artifact, version, file));
            log.debug("BOM-resolved {}:{}:{}", group, artifact, version);
        }
        return deps;
    }

    private String resolveVersion(String group, String artifact,
                                  String springBootVer, String springCloudVer) {
        // Spring Boot starters and framework jars → use Spring Boot version
        if (group.startsWith("org.springframework.boot") && springBootVer != null) return springBootVer;
        // Spring Cloud starters → use Spring Cloud version
        if (group.startsWith("org.springframework.cloud") && springCloudVer != null) return springCloudVer;
        // Spring Framework core (managed by Spring Boot BOM)
        if (group.equals("org.springframework") && springBootVer != null) return springBootVer;
        // Lombok and other common Spring Boot BOM entries — use Spring Boot version as proxy
        if (group.equals("org.projectlombok") && springBootVer != null) return springBootVer;
        if (group.equals("io.micrometer") && springBootVer != null) return springBootVer;
        return null;
    }

    private String extractFirst(Pattern pattern, String content, String defaultValue) {
        Matcher m = pattern.matcher(content);
        return m.find() ? m.group(1) : defaultValue;
    }

    private List<ParsedDependency> matchPattern(Pattern pattern, String content,
                                                 Properties props, Path file) {
        List<ParsedDependency> deps = new ArrayList<>();
        Matcher m = pattern.matcher(content);
        while (m.find()) {
            String group    = resolve(m.group(1), props);
            String artifact = resolve(m.group(2), props);
            String version  = resolve(m.group(3), props);

            if (version.contains("$") || version.isBlank()) continue;
            if (group.startsWith(":")) continue;

            deps.add(new ParsedDependency(group, artifact, version, file));
        }
        return deps;
    }

    private String resolve(String value, Properties props) {
        Matcher m = VAR_REF.matcher(value);
        if (m.matches()) {
            String key = m.group(1);
            return props.getProperty(key, props.getProperty(key.replace('.', '_'), value));
        }
        return value;
    }

    private Properties loadGradleProperties(Path repoRoot) {
        Properties props = new Properties();
        Path propsFile = repoRoot.resolve("gradle.properties");
        if (Files.exists(propsFile)) {
            try (var in = Files.newInputStream(propsFile)) {
                props.load(in);
                log.debug("Loaded {} keys from gradle.properties", props.size());
            } catch (IOException e) {
                log.warn("Could not read gradle.properties: {}", e.getMessage());
            }
        }
        return props;
    }
}
