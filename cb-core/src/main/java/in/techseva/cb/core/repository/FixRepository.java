package in.techseva.cb.core.repository;

import in.techseva.cb.core.domain.Fix;
import in.techseva.cb.core.domain.FixStatus;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FixRepository extends MongoRepository<Fix, String> {

    List<Fix> findByVulnerabilityId(String vulnerabilityId);

    Optional<Fix> findFirstByVulnerabilityIdOrderByGeneratedAtDesc(String vulnerabilityId);

    List<Fix> findByStatus(FixStatus status);

    long countByStatus(FixStatus status);
}
