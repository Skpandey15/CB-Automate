package in.techseva.cb.api.client;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScannerClientTest {

    private WireMockServer wireMockServer;
    private ScannerClient scannerClient;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(0);
        wireMockServer.start();
        configureFor("localhost", wireMockServer.port());
        scannerClient = new ScannerClient("http://localhost:" + wireMockServer.port());
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    void triggerScan_scannerRespondsOk_returnsNewFindingsCount() {
        stubFor(post(urlEqualTo("/api/v1/scanner/scan/my-proj"))
                .willReturn(aResponse()
                        .withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"projectKey\":\"my-proj\",\"newFindings\":7,\"status\":\"completed\"}")));

        int findings = scannerClient.triggerScan("my-proj");

        assertThat(findings).isEqualTo(7);
        verify(postRequestedFor(urlEqualTo("/api/v1/scanner/scan/my-proj")));
    }

    @Test
    void triggerScan_scannerReturns500_throwsScannerUnavailableException() {
        stubFor(post(urlEqualTo("/api/v1/scanner/scan/my-proj"))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> scannerClient.triggerScan("my-proj"))
                .isInstanceOf(ScannerClient.ScannerUnavailableException.class);
    }

    @Test
    void triggerScan_scannerUnreachable_throwsScannerUnavailableException() {
        wireMockServer.stop();

        assertThatThrownBy(() -> scannerClient.triggerScan("my-proj"))
                .isInstanceOf(ScannerClient.ScannerUnavailableException.class);
    }
}
