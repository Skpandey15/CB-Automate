package in.techseva.cb.api.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class ProductionSafetyGuardTest {

    @Test
    void insecureDefaultKey_refusesToStart() {
        var guard = new ProductionSafetyGuard("dev-key-change-in-prod");

        assertThatThrownBy(guard::verifyNoInsecureDefaults)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dev-key-change-in-prod");
    }

    @Test
    void insecureDefaultAmongMultipleKeys_stillRefusesToStart() {
        var guard = new ProductionSafetyGuard("real-key-1, dev-key-change-in-prod ,real-key-2");

        assertThatThrownBy(guard::verifyNoInsecureDefaults)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void realKey_startsFine() {
        var guard = new ProductionSafetyGuard("a-real-generated-secret-key");

        assertThatCode(guard::verifyNoInsecureDefaults).doesNotThrowAnyException();
    }
}
