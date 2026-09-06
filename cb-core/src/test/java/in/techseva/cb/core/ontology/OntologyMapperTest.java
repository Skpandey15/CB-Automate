package in.techseva.cb.core.ontology;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import in.techseva.cb.core.domain.Severity;
import in.techseva.cb.core.domain.Vulnerability;
import in.techseva.cb.core.domain.VulnerabilityStatus;
import in.techseva.cb.core.domain.VulnerabilityType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OntologyMapperTest {

    private OntologyMapper mapper;

    @BeforeEach
    void setUp() {
        ObjectMapper om = new ObjectMapper();
        om.registerModule(new JavaTimeModule());
        mapper = new OntologyMapper(om);
    }

    @Test
    void vulnerabilityToJsonLd_containsRequiredFields() {
        var vuln = new Vulnerability("id1", "SONAR-001", "CWE-89",
                Severity.CRITICAL, "java:S2078", 42, "30min",
                VulnerabilityStatus.DETECTED, "SQL injection risk",
                "A03:2021", "my-proj", "my-proj:src/Dao.java",
                "src/Dao.java", null, 0, Instant.now(), Instant.now(), null, null,
                VulnerabilityType.CODE, null, null, null, null, null);

        Map<String, Object> jsonLd = mapper.vulnerabilityToJsonLd(vuln);

        assertThat(jsonLd).containsKey("@context");
        assertThat(jsonLd).containsKey("@type");
        assertThat(jsonLd.get("@type")).isEqualTo("cb:Vulnerability");
        assertThat(jsonLd.get("cweId")).isEqualTo("CWE-89");
        assertThat(jsonLd.get("severity")).isEqualTo("CRITICAL");
        assertThat(jsonLd.get("sonarIssueKey")).isEqualTo("SONAR-001");
    }

    @Test
    void vulnerabilityToJsonLdString_isValidJson() throws Exception {
        var vuln = new Vulnerability("id2", "SONAR-002", "CWE-79",
                Severity.MAJOR, "java:S5131", 10, "1h",
                VulnerabilityStatus.DETECTED, "XSS risk",
                "A03:2021", "proj2", "proj2:src/Controller.java",
                "src/Controller.java", null, 0,
                Instant.now(), Instant.now(), null, null,
                VulnerabilityType.CODE, null, null, null, null, null);

        String jsonLd = mapper.vulnerabilityToJsonLdString(vuln);
        assertThat(jsonLd).contains("cb:Vulnerability");
        assertThat(jsonLd).contains("CWE-79");
        // Must be valid JSON
        new ObjectMapper().readTree(jsonLd);
    }
}
