package in.techseva.cb.core.service;

import in.techseva.cb.core.domain.AuditEvent;
import in.techseva.cb.core.repository.AuditEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditEventRepository repository;

    public AuditService(AuditEventRepository repository) {
        this.repository = repository;
    }

    @Async
    public void log(String entityId, String entityType, String action,
                    String actor, Map<String, Object> details) {
        try {
            repository.save(AuditEvent.of(entityId, entityType, action, actor, details));
        } catch (Exception e) {
            log.error("Failed to persist audit event for entity={} action={}", entityId, action, e);
        }
    }

    public List<AuditEvent> findByEntityId(String entityId) {
        return repository.findByEntityIdOrderByOccurredAtDesc(entityId);
    }

    public List<AuditEvent> findAll() {
        return repository.findAllByOrderByOccurredAtDesc();
    }
}
