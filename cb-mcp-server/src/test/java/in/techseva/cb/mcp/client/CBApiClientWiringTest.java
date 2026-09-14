package in.techseva.cb.mcp.client;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.core.env.MapPropertySource;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Same regression class as GitHubEnterpriseClientWiringTest (cb-pr) and
 * FixAgentToolsWiringTest (cb-agent): CBApiClient also has a primary
 * constructor plus a package-private test-only one, so it needs
 * @Autowired on the primary constructor or Spring cannot resolve which
 * to use at real startup.
 */
class CBApiClientWiringTest {

    @Configuration
    @ComponentScan(basePackageClasses = CBApiClient.class, useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE, classes = CBApiClient.class))
    static class Cfg {
        @Bean
        RestClient.Builder restClientBuilder() {
            return RestClient.builder();
        }
    }

    @Test
    void springCanAutowireTheBean() {
        try (var ctx = new AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("test", Map.of("cb-api.api-key", "test-key")));
            ctx.register(Cfg.class);
            ctx.refresh();

            assertThat(ctx.getBean(CBApiClient.class)).isNotNull();
        }
    }
}
