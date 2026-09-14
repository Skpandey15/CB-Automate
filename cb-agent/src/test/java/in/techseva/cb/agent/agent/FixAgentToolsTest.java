package in.techseva.cb.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.techseva.cb.agent.rag.QdrantRAGService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
class FixAgentToolsTest {

    @Mock QdrantRAGService qdrantRAGService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void getRemediationGuideline_knownCwe_returnsSpecificGuidance() {
        var tools = new FixAgentTools(qdrantRAGService, objectMapper,
                RestClient.builder()); // getRemediationGuideline makes no HTTP calls

        assertThat(tools.getRemediationGuideline("CWE-89")).contains("PreparedStatement");
        assertThat(tools.getRemediationGuideline("CWE-79")).contains("htmlEscape");
        assertThat(tools.getRemediationGuideline("CWE-918")).contains("SSRF");
    }

    @Test
    void getRemediationGuideline_unknownCwe_returnsGenericFallback() {
        var tools = new FixAgentTools(qdrantRAGService, objectMapper, RestClient.builder());

        assertThat(tools.getRemediationGuideline("CWE-9999")).contains("No specific guideline");
    }

    @Test
    void getRemediationGuideline_caseInsensitive() {
        var tools = new FixAgentTools(qdrantRAGService, objectMapper, RestClient.builder());

        assertThat(tools.getRemediationGuideline("cwe-89")).contains("PreparedStatement");
    }

    @Test
    void retrieveSimilarFixes_delegatesToQdrantAndSerializesResults() {
        var fix = new QdrantRAGService.FixSummary(
                "CWE-89", "parameterize", "explanation", "--- a\n+++ b\n");
        when(qdrantRAGService.retrieveSimilarFixes("CWE-89", "sql injection"))
                .thenReturn(List.of(fix));
        var tools = new FixAgentTools(qdrantRAGService, objectMapper, RestClient.builder());

        String result = tools.retrieveSimilarFixes("CWE-89", "sql injection");

        assertThat(result).contains("parameterize").contains("CWE-89");
    }

    @Test
    void retrieveSimilarFixes_noneFound_returnsEmptyArray() {
        when(qdrantRAGService.retrieveSimilarFixes("CWE-89", "sql injection"))
                .thenReturn(List.of());
        var tools = new FixAgentTools(qdrantRAGService, objectMapper, RestClient.builder());

        assertThat(tools.retrieveSimilarFixes("CWE-89", "sql injection")).isEqualTo("[]");
    }

    @Test
    void retrieveSimilarFixes_qdrantThrows_returnsEmptyArrayInsteadOfPropagating() {
        when(qdrantRAGService.retrieveSimilarFixes("CWE-89", "sql injection"))
                .thenThrow(new RuntimeException("qdrant unreachable"));
        var tools = new FixAgentTools(qdrantRAGService, objectMapper, RestClient.builder());

        assertThat(tools.retrieveSimilarFixes("CWE-89", "sql injection")).isEqualTo("[]");
    }

    @Test
    void getSafeVersion_success_returnsLatestStableVersion() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://maven.test");
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
        mockServer.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.method(
                        org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"response\":{\"docs\":[{\"v\":\"6.1.5\"},{\"v\":\"6.1.4\"}]}}",
                        MediaType.APPLICATION_JSON));

        var tools = new FixAgentTools(qdrantRAGService, objectMapper, builder.build());

        String result = tools.getSafeVersion("org.springframework", "spring-webmvc");

        assertThat(result).contains("6.1.5").contains("org.springframework:spring-webmvc:6.1.5");
    }

    @Test
    void getSafeVersion_skipsUnstableVersionsPreferringStable() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://maven.test");
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
        mockServer.expect(requestTo(org.hamcrest.Matchers.any(String.class)))
                .andRespond(withSuccess(
                        "{\"response\":{\"docs\":[{\"v\":\"7.0.0-RC1\"},{\"v\":\"6.1.5\"}]}}",
                        MediaType.APPLICATION_JSON));

        var tools = new FixAgentTools(qdrantRAGService, objectMapper, builder.build());

        String result = tools.getSafeVersion("org.springframework", "spring-webmvc");

        assertThat(result).contains("6.1.5").doesNotContain("RC1");
    }

    @Test
    void getSafeVersion_artifactNotFound_returnsErrorJson() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://maven.test");
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
        mockServer.expect(requestTo(org.hamcrest.Matchers.any(String.class)))
                .andRespond(withSuccess("{\"response\":{\"docs\":[]}}", MediaType.APPLICATION_JSON));

        var tools = new FixAgentTools(qdrantRAGService, objectMapper, builder.build());

        String result = tools.getSafeVersion("com.nonexistent", "fake-artifact");

        assertThat(result).contains("error").contains("not found on Maven Central");
    }

    @Test
    void getSafeVersion_mavenCentralUnreachable_returnsErrorJsonInsteadOfThrowing() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://maven.test");
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
        mockServer.expect(requestTo(org.hamcrest.Matchers.any(String.class)))
                .andRespond(withServerError());

        var tools = new FixAgentTools(qdrantRAGService, objectMapper, builder.build());

        String result = tools.getSafeVersion("org.springframework", "spring-webmvc");

        assertThat(result).contains("error");
    }
}
