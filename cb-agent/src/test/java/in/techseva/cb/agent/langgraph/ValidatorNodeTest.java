package in.techseva.cb.agent.langgraph;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ValidatorNode is the last automated gate before a generated fix is
 * treated as valid and handed to the build/patch pipeline. These tests
 * cover every rejection path, since a bug here means an invalid or
 * dangerous "fix" sails through unvalidated.
 */
class ValidatorNodeTest {

    private final ValidatorNode node = new ValidatorNode();

    private AgentWorkflowState stateWithFix(String fix) {
        return new AgentWorkflowState(new java.util.HashMap<>(Map.of(
                AgentWorkflowState.GENERATED_FIX, fix,
                AgentWorkflowState.RETRY_COUNT, 0
        )));
    }

    @Test
    void emptyFix_isRejectedAndRetryCountIncrements() throws Exception {
        var result = node.apply(stateWithFix(""));

        assertThat(result.get(AgentWorkflowState.VALIDATION_OK)).isEqualTo(false);
        assertThat(result.get(AgentWorkflowState.RETRY_COUNT)).isEqualTo(1);
        assertThat(result.get(AgentWorkflowState.ERROR)).isEqualTo("Empty fix generated");
    }

    @Test
    void missingPatchDiffField_isRejected() throws Exception {
        var result = node.apply(stateWithFix("{\"confidence\": 0.9}"));

        assertThat(result.get(AgentWorkflowState.VALIDATION_OK)).isEqualTo(false);
        assertThat((String) result.get(AgentWorkflowState.ERROR)).contains("missing required fields");
    }

    @Test
    void belowMinimumConfidence_isRejected() throws Exception {
        var fix = "{\"patchDiff\": \"--- a\\n+++ b\\n\", \"confidence\": 0.1}";

        var result = node.apply(stateWithFix(fix));

        assertThat(result.get(AgentWorkflowState.VALIDATION_OK)).isEqualTo(false);
        assertThat(result.get(AgentWorkflowState.CONFIDENCE)).isEqualTo(0.1);
        assertThat((String) result.get(AgentWorkflowState.ERROR)).contains("below minimum");
    }

    @Test
    void dangerousSystemExitCall_isRejectedRegardlessOfConfidence() throws Exception {
        var fix = "{\"patchDiff\": \"System.exit(1);\", \"confidence\": 0.99}";

        var result = node.apply(stateWithFix(fix));

        assertThat(result.get(AgentWorkflowState.VALIDATION_OK)).isEqualTo(false);
        assertThat((String) result.get(AgentWorkflowState.ERROR)).contains("dangerous system calls");
    }

    @Test
    void dangerousRuntimeExecCall_isRejected() throws Exception {
        var fix = "{\"patchDiff\": \"Runtime.getRuntime().exec(cmd);\", \"confidence\": 0.99}";

        var result = node.apply(stateWithFix(fix));

        assertThat(result.get(AgentWorkflowState.VALIDATION_OK)).isEqualTo(false);
    }

    @Test
    void validFix_isAccepted() throws Exception {
        var fix = "{\"patchDiff\": \"--- a\\n+++ b\\n\", \"confidence\": 0.85}";

        var result = node.apply(stateWithFix(fix));

        assertThat(result.get(AgentWorkflowState.VALIDATION_OK)).isEqualTo(true);
        assertThat(result.get(AgentWorkflowState.CONFIDENCE)).isEqualTo(0.85);
        assertThat(result).doesNotContainKey(AgentWorkflowState.ERROR);
    }

    @Test
    void codeFencedJson_isUnwrappedAndValidated() throws Exception {
        var fix = "```json\n{\"patchDiff\": \"--- a\\n+++ b\\n\", \"confidence\": 0.7}\n```";

        var result = node.apply(stateWithFix(fix));

        assertThat(result.get(AgentWorkflowState.VALIDATION_OK)).isEqualTo(true);
    }
}
