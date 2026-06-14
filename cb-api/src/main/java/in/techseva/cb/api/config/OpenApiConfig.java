package in.techseva.cb.api.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI complianceBuddyOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Compliance Buddy API")
                        .description("Autonomous security remediation agent — REST API")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("TechSeva Engineering")
                                .url("https://techseva.in")))
                .components(new Components()
                        .addSecuritySchemes("ApiKeyAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-API-Key")));
    }
}
