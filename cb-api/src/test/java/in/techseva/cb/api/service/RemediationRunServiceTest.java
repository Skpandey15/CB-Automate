package in.techseva.cb.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.techseva.cb.api.domain.RemediationRun;
import in.techseva.cb.api.domain.RemediationRunStatus;
import in.techseva.cb.api.repository.RemediationRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises the REAL subprocess plumbing (ProcessBuilder spawn, output
 * capture, report.json parsing) against a fake stand-in script
 * (fixtures/fake_remediate.py) that mimics remediate.py's exact CLI/output
 * contract -- not a mock of ProcessBuilder itself, which cannot be mocked
 * meaningfully since it is not an interface.
 */
@ExtendWith(MockitoExtension.class)
class RemediationRunServiceTest {

    @Mock RemediationRunRepository repository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir Path runsDir;
    private String fakeScriptPath;

    @BeforeEach
    void resolveFakeScript() throws URISyntaxException {
        var url = getClass().getClassLoader().getResource("fixtures/fake_remediate.py");
        assertThat(url).as("fake_remediate.py fixture must be on the test classpath").isNotNull();
        fakeScriptPath = Paths.get(url.toURI()).toString();
    }

    private RemediationRunService service() {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return new RemediationRunService(repository, objectMapper,
                windows ? "python" : "python3", fakeScriptPath, runsDir.toString(), 60);
    }

    private void seedRunningRecord(String runId, String repo, String branch) {
        when(repository.findById(runId)).thenReturn(Optional.of(
                RemediationRun.starting(runId, repo, branch, false, List.of())));
    }

    @Test
    void runAsync_fixedRepo_recordsFixedStatusWithFixCount() {
        String runId = "run-1";
        seedRunningRecord(runId, "fixture/fixed-repo", "main");

        service().runAsync(runId, "fixture/fixed-repo", "main", false, List.of());

        var captor = ArgumentCaptor.forClass(RemediationRun.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(RemediationRunStatus.FIXED);
        assertThat(captor.getValue().message()).contains("1 fix(es) applied, 0 unresolved");
        assertThat(captor.getValue().reportPath()).isNotNull();
        assertThat(captor.getValue().completedAt()).isNotNull();
    }

    @Test
    void runAsync_partialRepo_recordsPartialStatus() {
        String runId = "run-2";
        seedRunningRecord(runId, "fixture/partial-repo", "main");

        service().runAsync(runId, "fixture/partial-repo", "main", false, List.of());

        var captor = ArgumentCaptor.forClass(RemediationRun.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(RemediationRunStatus.PARTIAL);
        assertThat(captor.getValue().message()).contains("1 fix(es) applied, 1 unresolved");
    }

    @Test
    void runAsync_noFindingsRepo_recordsNoFindingsStatus() {
        String runId = "run-3";
        seedRunningRecord(runId, "fixture/no-findings-repo", "main");

        service().runAsync(runId, "fixture/no-findings-repo", "main", false, List.of());

        var captor = ArgumentCaptor.forClass(RemediationRun.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(RemediationRunStatus.NO_FINDINGS);
    }

    @Test
    void runAsync_failedRepo_recordsFailedStatusWithErrorMessage() {
        String runId = "run-4";
        seedRunningRecord(runId, "fixture/failed-repo", "main");

        service().runAsync(runId, "fixture/failed-repo", "main", false, List.of());

        var captor = ArgumentCaptor.forClass(RemediationRun.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(RemediationRunStatus.FAILED);
        assertThat(captor.getValue().message()).isEqualTo("branch does not exist");
    }

    @Test
    void runAsync_processCrashesBeforeAnyReport_recordsFailedWithCapturedOutput() {
        String runId = "run-5";
        seedRunningRecord(runId, "fixture/crash-repo", "main");

        service().runAsync(runId, "fixture/crash-repo", "main", false, List.of());

        var captor = ArgumentCaptor.forClass(RemediationRun.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(RemediationRunStatus.FAILED);
        assertThat(captor.getValue().message()).contains("simulated crash before any report was written");
    }

    @Test
    void isValidRepo_rejectsAnythingNotOwnerSlashRepo() {
        assertThat(RemediationRunService.isValidRepo("owner/repo")).isTrue();
        assertThat(RemediationRunService.isValidRepo("owner/repo; rm -rf /")).isFalse();
        assertThat(RemediationRunService.isValidRepo("not-a-repo")).isFalse();
        assertThat(RemediationRunService.isValidRepo(null)).isFalse();
    }

    @Test
    void isValidBranch_rejectsFlagLikeOrControlCharacterInput() {
        assertThat(RemediationRunService.isValidBranch("main")).isTrue();
        assertThat(RemediationRunService.isValidBranch("feature/x")).isTrue();
        assertThat(RemediationRunService.isValidBranch("--publish")).isFalse();
        assertThat(RemediationRunService.isValidBranch("bad\nbranch")).isFalse();
        assertThat(RemediationRunService.isValidBranch("")).isFalse();
        assertThat(RemediationRunService.isValidBranch(null)).isFalse();
    }

    @Test
    void isValidRecipient_requiresPlausibleEmailShape() {
        assertThat(RemediationRunService.isValidRecipient("a@b.com")).isTrue();
        assertThat(RemediationRunService.isValidRecipient("not-an-email")).isFalse();
        assertThat(RemediationRunService.isValidRecipient(null)).isFalse();
    }
}
