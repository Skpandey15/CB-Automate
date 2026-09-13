package in.techseva.cb.patcher.service;

import in.techseva.cb.core.domain.Fix;
import in.techseva.cb.core.domain.FixStatus;
import in.techseva.cb.core.repository.FixRepository;
import in.techseva.cb.core.repository.VulnerabilityRepository;
import in.techseva.cb.core.service.AuditService;
import in.techseva.cb.patcher.kafka.ValidatedFixKafkaPublisher;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Exercises the actual JGit checkout/revert mechanics behind
 * PatcherService.buildBranch/rollbackFix (added to fix the two
 * cb-mcp-server MCP tools that previously called nonexistent
 * cb-patcher endpoints). Uses a real local (file://) origin repo, so
 * this needs no network and is unaffected by this environment's
 * loopback-HTTP quirks -- git-over-filesystem is a plain local
 * operation.
 */
@ExtendWith(MockitoExtension.class)
class PatcherServiceTest {

    @Mock DiffApplier diffApplier;
    @Mock GradlePatcher gradlePatcher;
    @Mock FixRepository fixRepo;
    @Mock VulnerabilityRepository vulnerabilityRepo;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock AuditService auditService;
    @Mock ValidatedFixKafkaPublisher validatedFixPublisher;

    @TempDir Path tempDir;

    private Path originDir;
    private Path workspaceDir;

    @BeforeEach
    void setUpRepos() throws Exception {
        originDir = tempDir.resolve("origin.git");
        Path seedDir = tempDir.resolve("seed");
        workspaceDir = tempDir.resolve("workspace");

        Git.init().setBare(true).setDirectory(originDir.toFile()).call().close();

        try (Git seed = Git.init().setDirectory(seedDir.toFile()).call()) {
            Files.writeString(seedDir.resolve("README.md"), "hello\n");
            seed.add().addFilepattern(".").call();
            seed.commit().setMessage("initial commit")
                    .setAuthor("Test", "test@example.com").call();
            seed.branchCreate().setName("main").setForce(true).call();
            seed.remoteAdd().setName("origin").setUri(
                    new org.eclipse.jgit.transport.URIish(originDir.toUri().toString())).call();
            seed.push().setRemote("origin").add("main").call();

            seed.checkout().setCreateBranch(true).setName("cb/fix-test").call();
            Files.writeString(seedDir.resolve("Vulnerable.java"), "// vulnerable line\n");
            seed.add().addFilepattern(".").call();
            seed.commit().setMessage("fix(security): patch it")
                    .setAuthor("Compliance Buddy", "cb-bot@techseva.in").call();
            seed.push().setRemote("origin").add("cb/fix-test").call();
        }

        Git.cloneRepository()
                .setURI(originDir.toUri().toString())
                .setDirectory(workspaceDir.toFile())
                .setBranch("main")
                .call()
                .close();
    }

    private PatcherService newService() {
        // skipBuildValidation=true: we're testing checkout/revert mechanics,
        // not BuildValidator's own subprocess logic (which has no gradlew/pom
        // in this throwaway repo anyway).
        var buildValidator = new BuildValidator(1, workspaceDir.toString(), true);
        return new PatcherService(diffApplier, gradlePatcher, buildValidator, fixRepo,
                vulnerabilityRepo, eventPublisher, auditService, validatedFixPublisher,
                workspaceDir.toString(), "irrelevant-owner", "irrelevant-repo", "");
    }

    @Test
    void buildBranch_checksOutTheRequestedBranch() throws Exception {
        var result = newService().buildBranch("cb/fix-test");

        assertThat(result.success()).isTrue();
        assertThat(Files.exists(workspaceDir.resolve("Vulnerable.java")))
                .as("workspace should now be on cb/fix-test, which added this file")
                .isTrue();
        try (Git git = Git.open(workspaceDir.toFile())) {
            assertThat(git.getRepository().getBranch()).isEqualTo("cb/fix-test");
        }
    }

    @Test
    void rollbackFix_revertsTipCommitAndMarksFixRejected() throws Exception {
        Fix fix = fix("fix1");
        when(fixRepo.findById("fix1")).thenReturn(Optional.of(fix));

        var result = newService().rollbackFix("fix1", "cb/fix-test");

        assertThat(result.success()).isTrue();
        assertThat(Files.exists(workspaceDir.resolve("Vulnerable.java")))
                .as("the revert should undo the commit that added this file")
                .isFalse();

        var fixCaptor = org.mockito.ArgumentCaptor.forClass(Fix.class);
        org.mockito.Mockito.verify(fixRepo).save(fixCaptor.capture());
        assertThat(fixCaptor.getValue().status()).isEqualTo(FixStatus.REJECTED);

        // Confirm the revert was actually pushed to origin, not just local
        try (Git bare = Git.open(originDir.toFile())) {
            var branchRef = bare.getRepository().findRef("cb/fix-test");
            assertThat(branchRef).isNotNull();
            try (var revWalk = new org.eclipse.jgit.revwalk.RevWalk(bare.getRepository())) {
                var tip = revWalk.parseCommit(branchRef.getObjectId());
                assertThat(tip.getShortMessage()).contains("Revert");
            }
        }
    }

    private Fix fix(String id) {
        return new Fix(id, "vuln1", "--- a\n+++ b\n", null, "parameterize",
                0.9, "gpt-4o", "explanation", null, null, "cb/fix-test", null,
                FixStatus.BUILD_VALIDATED, true, "build ok", 100, Instant.now());
    }
}
