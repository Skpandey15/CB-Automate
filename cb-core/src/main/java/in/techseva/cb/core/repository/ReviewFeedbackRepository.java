package in.techseva.cb.core.repository;

import in.techseva.cb.core.domain.ReviewFeedback;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReviewFeedbackRepository extends MongoRepository<ReviewFeedback, String> {
    List<ReviewFeedback> findByVulnerabilityIdAndProcessedFalse(String vulnerabilityId);
}
