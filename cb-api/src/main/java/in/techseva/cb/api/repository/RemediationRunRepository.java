package in.techseva.cb.api.repository;

import in.techseva.cb.api.domain.RemediationRun;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RemediationRunRepository extends MongoRepository<RemediationRun, String> {
}
