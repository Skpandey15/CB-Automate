package in.techseva.cb.api.controller;

import in.techseva.cb.api.controller.RemediationRunController.CreateRunRequest;
import in.techseva.cb.api.domain.RemediationRun;
import in.techseva.cb.api.domain.RemediationRunStatus;
import in.techseva.cb.api.service.RemediationRunService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RemediationRunControllerTest {

    @Mock RemediationRunService service;

    private RemediationRunController controller() {
        return new RemediationRunController(service);
    }

    @Test
    void create_invalidRepo_returns400WithoutStartingAnyRun() {
        var response = controller().create(new CreateRunRequest("not-a-repo", "main", false, List.of()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(service, never()).startRun(any(), any(), anyBoolean(), any());
    }

    @Test
    void create_invalidBranch_returns400() {
        var response = controller().create(new CreateRunRequest("owner/repo", "--publish", false, List.of()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void create_publishTrueWithNoRecipients_returns400() {
        var response = controller().create(new CreateRunRequest("owner/repo", "main", true, List.of()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(service, never()).startRun(any(), any(), anyBoolean(), any());
    }

    @Test
    void create_publishTrueWithInvalidRecipient_returns400() {
        var response = controller().create(
                new CreateRunRequest("owner/repo", "main", true, List.of("not-an-email")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void create_valid_returns202AndStartsRunThenTriggersAsyncExecution() {
        var run = new RemediationRun("run-1", "owner/repo", "main", false, List.of(),
                RemediationRunStatus.RUNNING, null, null, Instant.now(), null);
        when(service.startRun("owner/repo", "main", false, List.of())).thenReturn(run);

        var response = controller().create(new CreateRunRequest("owner/repo", "main", false, List.of()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isEqualTo(run);
        verify(service, times(1)).startRun("owner/repo", "main", false, List.of());
        // Must be a SEPARATE call from startRun (not startRun calling it
        // internally) -- see RemediationRunService.startRun's Javadoc on
        // why a same-class call would bypass the @Async proxy.
        verify(service, times(1)).runAsync("run-1", "owner/repo", "main", false, List.of());
    }

    @Test
    void get_found_returns200() {
        var run = new RemediationRun("run-1", "owner/repo", "main", false, List.of(),
                RemediationRunStatus.FIXED, "/path/report.json", "1 fix(es) applied, 0 unresolved",
                Instant.now(), Instant.now());
        when(service.findRun("run-1")).thenReturn(Optional.of(run));

        var response = controller().get("run-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(run);
    }

    @Test
    void get_notFound_returns404() {
        when(service.findRun("missing")).thenReturn(Optional.empty());

        var response = controller().get("missing");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
