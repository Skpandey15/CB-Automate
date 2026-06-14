package in.techseva.cb.api.controller;

import in.techseva.cb.core.domain.Fix;
import in.techseva.cb.core.repository.FixRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/fixes")
@Tag(name = "Fixes", description = "Query generated fixes")
@SecurityRequirement(name = "ApiKeyAuth")
public class FixController {

    private final FixRepository repository;

    public FixController(FixRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    @Operation(summary = "List all fixes")
    public ResponseEntity<List<Fix>> list() {
        return ResponseEntity.ok(repository.findAll());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a fix by ID")
    public ResponseEntity<Fix> getById(@PathVariable String id) {
        return repository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
