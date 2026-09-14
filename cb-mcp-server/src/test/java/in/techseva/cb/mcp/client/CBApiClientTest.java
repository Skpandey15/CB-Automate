package in.techseva.cb.mcp.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CBApiClientTest {

    private MockRestServiceServer mockServer;

    private CBApiClient clientWithMockedTransport() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://cb-api.test")
                .defaultHeader("X-API-Key", "test-api-key");
        mockServer = MockRestServiceServer.bindTo(builder).build();
        return new CBApiClient(builder.build());
    }

    @Test
    void startRemediationRun_postsWithApiKeyHeaderAndReturnsBody() {
        var client = clientWithMockedTransport();
        mockServer.expect(requestTo("https://cb-api.test/api/v1/remediation-runs"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-API-Key", "test-api-key"))
                .andRespond(withSuccess("{\"runId\":\"run-1\",\"status\":\"RUNNING\"}", MediaType.APPLICATION_JSON));

        String result = client.startRemediationRun("owner/repo", "main", false, List.of());

        assertThat(result).contains("run-1");
        mockServer.verify();
    }

    @Test
    void startRemediationRun_serverError_throwsRuntimeException() {
        var client = clientWithMockedTransport();
        mockServer.expect(requestTo("https://cb-api.test/api/v1/remediation-runs"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.startRemediationRun("owner/repo", "main", false, List.of()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("cb-api remediation-runs error");
    }

    @Test
    void getRemediationRunStatus_getsWithApiKeyHeaderAndReturnsBody() {
        var client = clientWithMockedTransport();
        mockServer.expect(requestTo("https://cb-api.test/api/v1/remediation-runs/run-1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-API-Key", "test-api-key"))
                .andRespond(withSuccess("{\"status\":\"FIXED\"}", MediaType.APPLICATION_JSON));

        String result = client.getRemediationRunStatus("run-1");

        assertThat(result).contains("FIXED");
    }

    @Test
    void getRemediationRunStatus_serverError_throwsRuntimeException() {
        var client = clientWithMockedTransport();
        mockServer.expect(requestTo("https://cb-api.test/api/v1/remediation-runs/run-1"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.getRemediationRunStatus("run-1"))
                .isInstanceOf(RuntimeException.class);
    }
}
