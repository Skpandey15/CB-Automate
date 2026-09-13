package in.techseva.cb.pr.service;

import in.techseva.cb.core.domain.Fix;
import in.techseva.cb.core.domain.FixStatus;
import in.techseva.cb.core.domain.Severity;
import in.techseva.cb.core.domain.Vulnerability;
import in.techseva.cb.core.domain.VulnerabilityStatus;
import in.techseva.cb.core.domain.VulnerabilityType;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * ADR-0007 requires fail-CLOSED governance semantics: an unavailable policy
 * engine means authorization is unknown, not granted. These tests pin that
 * behavior for every failure mode OpaGovernanceService can hit. Uses
 * MockRestServiceServer (no real socket) rather than an embedded HTTP
 * server, since this environment's network stack intercepts loopback HTTP.
 */
class OpaGovernanceServiceTest {

    private MockRestServiceServer mockServer;

    private OpaGovernanceService serviceWithMockedTransport(boolean opaEnabled) {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        return new OpaGovernanceService(builder, "http://opa:8181", "cb/governance", opaEnabled);
    }

    private ResponseActions expectOpaCall() {
        return mockServer.expect(method(org.springframework.http.HttpMethod.POST));
    }

    private Vulnerability vuln() {
        return new Vulnerability("id1", "SONAR-001", "CWE-89", Severity.CRITICAL,
                "java:S2078", 42, "30min", VulnerabilityStatus.FIX_VALIDATED,
                "Potential SQL injection", "A03:2021", "my-proj",
                "my-proj:src/Foo.java", "src/Foo.java", null, 0,
                Instant.now(), Instant.now(), null, null,
                VulnerabilityType.CODE, null, null, null, null, null);
    }

    private Fix fix() {
        return new Fix("fix1", "id1", "--- a\n+++ b\n", null, "parameterize",
                0.9, "gpt-4o", "explanation", null, null, null, null,
                FixStatus.BUILD_VALIDATED, true, "build ok", 100, Instant.now());
    }

    @Test
    void evaluate_allowResponse_isAllowed() {
        var service = serviceWithMockedTransport(true);
        expectOpaCall().andRespond(withSuccess(
                "{\"result\":{\"allow\":true,\"violations\":[]}}", MediaType.APPLICATION_JSON));

        var result = service.evaluate(vuln(), fix());

        assertThat(result.allowed()).isTrue();
        mockServer.verify();
    }

    @Test
    void evaluate_denyResponse_isBlocked() {
        var service = serviceWithMockedTransport(true);
        expectOpaCall().andRespond(withSuccess(
                "{\"result\":{\"allow\":false,\"violations\":[\"no tests changed\"]}}", MediaType.APPLICATION_JSON));

        var result = service.evaluate(vuln(), fix());

        assertThat(result.allowed()).isFalse();
        assertThat(result.violations()).containsExactly("no tests changed");
    }

    @Test
    void evaluate_nullResult_failsClosed() {
        var service = serviceWithMockedTransport(true);
        expectOpaCall().andRespond(withSuccess("{\"result\":null}", MediaType.APPLICATION_JSON));

        var result = service.evaluate(vuln(), fix());

        assertThat(result.allowed())
                .as("a null OPA result must deny, not allow — ADR-0007 fail-closed semantics")
                .isFalse();
    }

    @Test
    void evaluate_malformedBody_failsClosed() {
        var service = serviceWithMockedTransport(true);
        expectOpaCall().andRespond(withSuccess("not json at all", MediaType.APPLICATION_JSON));

        var result = service.evaluate(vuln(), fix());

        assertThat(result.allowed())
                .as("an unparseable OPA response must deny, not allow")
                .isFalse();
    }

    @Test
    void evaluate_serverError_failsClosed() {
        var service = serviceWithMockedTransport(true);
        expectOpaCall().andRespond(withServerError());

        var result = service.evaluate(vuln(), fix());

        assertThat(result.allowed())
                .as("a 5xx from OPA must deny, not allow")
                .isFalse();
    }

    @Test
    void evaluate_transportFailure_failsClosed() {
        var service = serviceWithMockedTransport(true);
        expectOpaCall().andRespond(request -> { throw new IOException("connection refused"); });

        var result = service.evaluate(vuln(), fix());

        assertThat(result.allowed())
                .as("an unreachable OPA must deny, not allow")
                .isFalse();
    }

    @Test
    void evaluate_opaDisabled_stillAllows() {
        // An explicit operator opt-out remains an intentional bypass, not a failure —
        // distinct from every failure mode above, which must all deny.
        var service = serviceWithMockedTransport(false);

        var result = service.evaluate(vuln(), fix());

        assertThat(result.allowed()).isTrue();
        mockServer.verify(); // no HTTP call should have been made at all
    }
}
