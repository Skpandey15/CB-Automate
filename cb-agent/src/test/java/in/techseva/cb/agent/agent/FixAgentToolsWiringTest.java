package in.techseva.cb.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.techseva.cb.agent.rag.QdrantRAGService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Same regression class as GitHubEnterpriseClientWiringTest (cb-pr): this
 * class also has a primary constructor plus a package-private test-only
 * one, so it needs @Autowired on the primary constructor or Spring cannot
 * resolve which to use at real startup.
 */
class FixAgentToolsWiringTest {

    @Configuration
    @ComponentScan(basePackageClasses = FixAgentTools.class, useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE, classes = FixAgentTools.class))
    static class Cfg {
        @Bean
        RestClient.Builder restClientBuilder() {
            return RestClient.builder();
        }

        @Bean
        QdrantRAGService qdrantRAGService() {
            return Mockito.mock(QdrantRAGService.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Test
    void springCanAutowireTheBean() {
        try (var ctx = new AnnotationConfigApplicationContext(Cfg.class)) {
            assertThat(ctx.getBean(FixAgentTools.class)).isNotNull();
        }
    }
}
