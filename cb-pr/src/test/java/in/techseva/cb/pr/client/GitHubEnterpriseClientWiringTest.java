package in.techseva.cb.pr.client;

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
 * Regression test: GitHubEnterpriseClient briefly shipped with two
 * constructors and neither marked @Autowired, which Spring cannot resolve
 * -- the bean fails at context startup with "No default constructor
 * found," even though every unit test that constructs it directly (bypassing
 * Spring entirely) stayed green. This test actually exercises Spring's
 * constructor-autowiring resolution, the thing a plain `new` call cannot
 * catch.
 */
class GitHubEnterpriseClientWiringTest {

    @Configuration
    @ComponentScan(basePackageClasses = GitHubEnterpriseClient.class, useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE, classes = GitHubEnterpriseClient.class))
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
                    new MapPropertySource("test", Map.of("github.token", "test-token")));
            ctx.register(Cfg.class);
            ctx.refresh();

            assertThat(ctx.getBean(GitHubEnterpriseClient.class)).isNotNull();
        }
    }
}
