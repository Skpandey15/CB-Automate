package in.techseva.cb.patcher.controller;

import in.techseva.cb.patcher.controller.PatcherController.BuildRequest;
import in.techseva.cb.patcher.controller.PatcherController.RollbackRequest;
import in.techseva.cb.patcher.service.BuildValidator.BuildResult;
import in.techseva.cb.patcher.service.PatcherService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Pins the request/response shapes cb-mcp-server's CBPatcherClient already
 * assumes (POST /api/patcher/build {branchName}, POST /api/patcher/rollback
 * {fixId, branchName}) -- this controller didn't exist before, so those two
 * MCP tools failed on every call.
 */
@ExtendWith(MockitoExtension.class)
class PatcherControllerTest {

    @Mock PatcherService patcherService;

    @Test
    void build_success_returns200WithLog() throws Exception {
        when(patcherService.buildBranch("cb/fix-test")).thenReturn(new BuildResult(true, "BUILD SUCCESS"));

        var response = new PatcherController(patcherService).build(new BuildRequest("cb/fix-test"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "success").containsEntry("buildLog", "BUILD SUCCESS");
    }

    @Test
    void build_failure_returns200WithBuildFailedStatus() throws Exception {
        when(patcherService.buildBranch("cb/fix-test")).thenReturn(new BuildResult(false, "compile error"));

        var response = new PatcherController(patcherService).build(new BuildRequest("cb/fix-test"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "build-failed");
    }

    @Test
    void build_missingBranchName_returns400() throws Exception {
        var response = new PatcherController(patcherService).build(new BuildRequest(""));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void build_serviceThrows_returns500() throws Exception {
        when(patcherService.buildBranch("cb/fix-test")).thenThrow(new RuntimeException("clone failed"));

        var response = new PatcherController(patcherService).build(new BuildRequest("cb/fix-test"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void rollback_success_returns200() throws Exception {
        when(patcherService.rollbackFix("fix1", "cb/fix-test")).thenReturn(new BuildResult(true, "reverted"));

        var response = new PatcherController(patcherService).rollback(new RollbackRequest("fix1", "cb/fix-test"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "success");
    }

    @Test
    void rollback_missingFixId_returns400() throws Exception {
        var response = new PatcherController(patcherService).rollback(new RollbackRequest(null, "cb/fix-test"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
