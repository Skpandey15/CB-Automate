package in.techseva.cb.scanner.dependency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Parses Maven pom.xml files to extract declared dependency coordinates.
 * Handles property substitution (${property.name}) and parent POM inheritance.
 */
@Component
public class MavenDependencyParser {

    private static final Logger log = LoggerFactory.getLogger(MavenDependencyParser.class);

    public List<GradleDependencyParser.ParsedDependency> parseDependencies(Path repoRoot) throws IOException {
        List<Path> pomFiles = new ArrayList<>();
        try (var walk = Files.walk(repoRoot)) {
            walk.filter(p -> p.getFileName().toString().equals("pom.xml")
                            && !p.toString().contains("target"))
                .forEach(pomFiles::add);
        }

        log.info("Found {} pom.xml files under {}", pomFiles.size(), repoRoot);

        List<GradleDependencyParser.ParsedDependency> all = new ArrayList<>();
        for (Path pom : pomFiles) {
            all.addAll(parsePom(pom));
        }

        return all.stream()
                .collect(Collectors.toMap(
                        GradleDependencyParser.ParsedDependency::coordinate,
                        d -> d,
                        (a, b) -> a))
                .values()
                .stream()
                .toList();
    }

    private List<GradleDependencyParser.ParsedDependency> parsePom(Path pomFile) {
        List<GradleDependencyParser.ParsedDependency> deps = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = factory.newDocumentBuilder().parse(pomFile.toFile());
            doc.getDocumentElement().normalize();

            Map<String, String> props = extractProperties(doc);

            NodeList depNodes = doc.getElementsByTagName("dependency");
            for (int i = 0; i < depNodes.getLength(); i++) {
                Element dep = (Element) depNodes.item(i);
                String groupId    = textOf(dep, "groupId");
                String artifactId = textOf(dep, "artifactId");
                String version    = textOf(dep, "version");

                if (groupId == null || artifactId == null || version == null) continue;

                groupId    = resolve(groupId, props);
                artifactId = resolve(artifactId, props);
                version    = resolve(version, props);

                if (version.startsWith("$") || version.isBlank()) continue;

                deps.add(new GradleDependencyParser.ParsedDependency(groupId, artifactId, version, pomFile));
            }
        } catch (Exception e) {
            log.warn("Could not parse {}: {}", pomFile, e.getMessage());
        }
        return deps;
    }

    private Map<String, String> extractProperties(Document doc) {
        Map<String, String> props = new HashMap<>();
        NodeList propsNodes = doc.getElementsByTagName("properties");
        for (int i = 0; i < propsNodes.getLength(); i++) {
            Element propsEl = (Element) propsNodes.item(i);
            NodeList children = propsEl.getChildNodes();
            for (int j = 0; j < children.getLength(); j++) {
                if (children.item(j) instanceof Element el) {
                    props.put(el.getTagName(), el.getTextContent().trim());
                }
            }
        }
        // Also expose parent version as project.parent.version
        NodeList parentNodes = doc.getElementsByTagName("parent");
        if (parentNodes.getLength() > 0) {
            Element parent = (Element) parentNodes.item(0);
            String parentVersion = textOf(parent, "version");
            if (parentVersion != null) props.put("project.parent.version", parentVersion);
        }
        return props;
    }

    private String textOf(Element el, String tag) {
        NodeList nodes = el.getElementsByTagName(tag);
        if (nodes.getLength() == 0) return null;
        String text = nodes.item(0).getTextContent().trim();
        return text.isBlank() ? null : text;
    }

    private String resolve(String value, Map<String, String> props) {
        if (!value.startsWith("${")) return value;
        String key = value.replaceAll("^\\$\\{(.+)}$", "$1");
        return props.getOrDefault(key, value);
    }
}
