package in.techseva.cb.api.controller;

import in.techseva.cb.api.client.ScannerClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScanControllerTest {

    @Mock ScannerClient scannerClient;

    private ScanController controller() {
        return new ScanController(scannerClient);
    }

    @Test
    void triggerScan_scannerAvailable_returnsAcceptedWithFindings() {
        when(scannerClient.triggerScan("my-proj")).thenReturn(5);

        var response = controller().triggerScan("my-proj");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody())
                .containsEntry("projectKey", "my-proj")
                .containsEntry("newFindings", 5)
                .containsEntry("status", "completed");
    }

    @Test
    void triggerScan_scannerUnavailable_returnsBadGateway() {
        when(scannerClient.triggerScan("my-proj"))
                .thenThrow(new ScannerClient.ScannerUnavailableException("boom", new RuntimeException("boom")));

        var response = controller().triggerScan("my-proj");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody())
                .containsEntry("projectKey", "my-proj")
                .containsEntry("status", "failed");
    }
}
