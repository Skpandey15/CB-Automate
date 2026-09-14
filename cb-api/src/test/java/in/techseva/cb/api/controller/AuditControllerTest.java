package in.techseva.cb.api.controller;

import in.techseva.cb.core.domain.AuditEvent;
import in.techseva.cb.core.service.AuditService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditControllerTest {

    @Mock AuditService auditService;

    @Test
    void list_returnsAllEvents() {
        var event = AuditEvent.of("fix1", "Fix", "PR_RAISED", "pr-service", Map.of());
        when(auditService.findAll()).thenReturn(List.of(event));

        var response = new AuditController(auditService).list();

        assertThat(response.getBody()).hasSize(1);
    }

    @Test
    void byEntity_delegatesToAuditServiceWithGivenId() {
        var event = AuditEvent.of("fix1", "Fix", "PR_RAISED", "pr-service", Map.of());
        when(auditService.findByEntityId("fix1")).thenReturn(List.of(event));

        var response = new AuditController(auditService).byEntity("fix1");

        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).entityId()).isEqualTo("fix1");
    }
}
