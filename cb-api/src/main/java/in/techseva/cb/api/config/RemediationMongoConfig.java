package in.techseva.cb.api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * cb-core's MongoConfig scopes @EnableMongoRepositories to
 * in.techseva.cb.core.repository only, so RemediationRunRepository
 * (deliberately kept cb-api-local, see RemediationRun's Javadoc) needs
 * its own repository-scanning declaration to be picked up.
 */
@Configuration
@EnableMongoRepositories(basePackages = "in.techseva.cb.api.repository")
public class RemediationMongoConfig {
}
