package in.techseva.cb.api.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Refuses to start cb-api under the "prod" Spring profile if it would
 * otherwise run with the documented insecure default API key. This
 * profile is opt-in (SPRING_PROFILES_ACTIVE=prod) — the local/dev
 * deployment in k8s/base intentionally does not set it, since the
 * README's own quick-start relies on the default key working out of
 * the box.
 */
@Component
@Profile("prod")
public class ProductionSafetyGuard {

    static final String INSECURE_DEFAULT_KEY = "dev-key-change-in-prod";

    private final String apiKeysProperty;

    public ProductionSafetyGuard(@Value("${api.keys}") String apiKeysProperty) {
        this.apiKeysProperty = apiKeysProperty;
    }

    @PostConstruct
    public void verifyNoInsecureDefaults() {
        boolean usesInsecureDefault = Arrays.stream(apiKeysProperty.split(","))
                .map(String::trim)
                .anyMatch(INSECURE_DEFAULT_KEY::equals);
        if (usesInsecureDefault) {
            throw new IllegalStateException(
                    "Refusing to start under the 'prod' profile: CB_API_KEYS is still set to " +
                    "the documented insecure default '" + INSECURE_DEFAULT_KEY + "'. Set a real, " +
                    "unique API key before deploying to production.");
        }
    }
}
