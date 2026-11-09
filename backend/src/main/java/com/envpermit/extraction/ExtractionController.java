package com.envpermit.extraction;

import com.envpermit.core.project.ProjectAccess;
import com.envpermit.review.AiRun;
import com.envpermit.review.AiRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class ExtractionController {

    private final Extraction extraction;
    private final AiRunRepository runs;
    private final ProjectAccess access;
    private final Executor executor;

    ExtractionController(Extraction extraction, AiRunRepository runs, ProjectAccess access,
                         @Qualifier(ExtractionConfig.EXECUTOR) Executor executor) {
        this.extraction = extraction;
        this.runs = runs;
        this.access = access;
        this.executor = executor;
    }

    record StartRequest(boolean confirmDuplicate) {
    }

    /** Audit view of a run (design §2.1). The raw response stays in S3; only its key is shown. */
    record RunView(long id, long projectId, String status, String errorMessage, JsonNode inputRef, String model,
                   String promptVersion, String preparer, Integer inputTokens, Integer outputTokens,
                   String rawResponseKey, Long startedBy, Instant startedAt, Instant finishedAt) {
        static RunView of(AiRun r) {
            return new RunView(r.getId(), r.getProjectId(), r.getStatus(), r.getErrorMessage(), r.getInputRef(),
                    r.getModel(), r.getPromptVersion(), r.getPreparer(), r.getInputTokens(), r.getOutputTokens(),
                    r.getRawResponseKey(), r.getStartedBy(), r.getStartedAt(), r.getFinishedAt());
        }
    }

    /** 202 + runId; poll GET /api/ai-runs/{runId}. Body optional: {"confirmDuplicate": true} after the 409. */
    @PostMapping("/api/projects/{projectId}/permit-items/{permitId}/documents/{documentId}/extractions")
    ResponseEntity<Map<String, Long>> start(@AuthenticationPrincipal Jwt jwt, @PathVariable long projectId,
                                            @PathVariable long permitId, @PathVariable long documentId,
                                            @RequestBody(required = false) StartRequest req) {
        AiRun run = extraction.start(projectId, permitId, documentId, jwt, req != null && req.confirmDuplicate());
        executor.execute(() -> extraction.run(run.getId())); // after start() committed
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("runId", run.getId()));
    }

    @GetMapping("/api/ai-runs/{runId}")
    RunView get(@AuthenticationPrincipal Jwt jwt, @PathVariable long runId) {
        AiRun r = runs.findById(runId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        access.member(r.getProjectId(), jwt);
        return RunView.of(r);
    }

    /** AI runs of a project, newest first (the "manual entry" run is left out). */
    @GetMapping("/api/projects/{projectId}/ai-runs")
    List<RunView> list(@AuthenticationPrincipal Jwt jwt, @PathVariable long projectId) {
        access.member(projectId, jwt);
        return runs.findByProjectIdAndPreparerNotOrderByIdDesc(projectId, AiRun.MANUAL).stream().map(RunView::of).toList();
    }
}
