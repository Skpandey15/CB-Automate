package in.techseva.cb.core.repository;

import in.techseva.cb.core.domain.AuditEvent;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface AuditEventRepository extends MongoRepository<AuditEvent, String> {

    List<AuditEvent> findByEntityIdOrderByOccurredAtDesc(String entityId);

    List<AuditEvent> findByEntityTypeAndOccurredAtAfterOrderByOccurredAtDesc(
            String entityType, Instant after);

    List<AuditEvent> findAllByOrderByOccurredAtDesc();
}
