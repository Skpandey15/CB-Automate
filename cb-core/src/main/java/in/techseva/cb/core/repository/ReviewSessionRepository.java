package in.techseva.cb.core.repository;

import in.techseva.cb.core.domain.ReviewSession;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReviewSessionRepository extends MongoRepository<ReviewSession, String> {
    Optional<ReviewSession> findByVulnerabilityId(String vulnerabilityId);
    List<ReviewSession> findByStatus(String status);
}
