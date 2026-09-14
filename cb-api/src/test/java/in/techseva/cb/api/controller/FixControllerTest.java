package in.techseva.cb.api.controller;

import in.techseva.cb.core.domain.Fix;
import in.techseva.cb.core.domain.FixStatus;
import in.techseva.cb.core.repository.FixRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FixControllerTest {

    @Mock FixRepository repository;

    private Fix fix() {
        return new Fix("fix1", "vuln1", "--- a\n+++ b\n", null, "parameterize",
                0.9, "gpt-4o", "explanation", null, null, null, null,
                FixStatus.BUILD_VALIDATED, true, "build ok", 100, Instant.now());
    }

    @Test
    void list_returnsAllFixes() {
        when(repository.findAll()).thenReturn(List.of(fix()));

        var response = new FixController(repository).list();

        assertThat(response.getBody()).hasSize(1);
    }

    @Test
    void getById_found_returns200() {
        when(repository.findById("fix1")).thenReturn(Optional.of(fix()));

        var response = new FixController(repository).getById("fix1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void getById_notFound_returns404() {
        when(repository.findById("missing")).thenReturn(Optional.empty());

        var response = new FixController(repository).getById("missing");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
