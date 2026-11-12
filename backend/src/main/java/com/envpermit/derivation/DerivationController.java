package com.envpermit.derivation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class DerivationController {

    private final Derivation derivation;

    DerivationController(Derivation derivation) {
        this.derivation = derivation;
    }

    @PutMapping("/api/projects/{projectId}/attributes")
    JsonNode saveAttributes(@AuthenticationPrincipal Jwt jwt, @PathVariable long projectId,
                            @RequestBody JsonNode attrs) {
        return derivation.saveAttributes(projectId, jwt, attrs);
    }

    @PostMapping("/api/projects/{projectId}/derivations")
    JsonNode derive(@AuthenticationPrincipal Jwt jwt, @PathVariable long projectId) {
        return derivation.derive(projectId, jwt);
    }

    @GetMapping("/api/projects/{projectId}/derivations/latest")
    ResponseEntity<JsonNode> latest(@AuthenticationPrincipal Jwt jwt, @PathVariable long projectId) {
        JsonNode latest = derivation.latest(projectId, jwt);
        return latest == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(latest);
    }

    @GetMapping("/api/projects/{projectId}/derivations/missing-inputs")
    List<Derivation.Missing> missingInputs(@AuthenticationPrincipal Jwt jwt, @PathVariable long projectId) {
        return derivation.missingInputs(projectId, jwt);
    }

    @ExceptionHandler(Derivation.MissingInputs.class)
    ResponseEntity<Map<String, Object>> missing(Derivation.MissingInputs e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage(), "missingFields", e.fields));
    }
}
