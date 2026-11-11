package com.envpermit.operation;

import static com.envpermit.core.project.ProjectAccess.userId;

import com.envpermit.core.document.GeneratedDocuments;
import com.envpermit.core.project.ProjectAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 이행 보고서 초안 (design §4, §6 F3): preview saves nothing; generating stores the XLSX as a document of the permit item. */
@RestController
class ComplianceReportController {

    private static final String BASE = "/api/projects/{projectId}/permit-items/{permitId}/compliance-reports";

    private final ComplianceReport report;
    private final GeneratedDocuments documents;
    private final ProjectAccess access;

    ComplianceReportController(ComplianceReport report, GeneratedDocuments documents, ProjectAccess access) {
        this.report = report;
        this.documents = documents;
        this.access = access;
    }

    record Request(@NotNull LocalDate from, @NotNull LocalDate to, List<Long> emissionPointIds) {
    }

    @GetMapping(BASE + "/preview")
    ComplianceReport.Report preview(@AuthenticationPrincipal Jwt jwt, @PathVariable long projectId, @PathVariable long permitId,
                                    @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate from,
                                    @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate to,
                                    @RequestParam(required = false) List<Long> emissionPointIds) {
        access.member(projectId, jwt);
        return report.load(projectId, permitId, from, to, emissionPointIds, userId(jwt));
    }

    @PostMapping(BASE)
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Long> create(@AuthenticationPrincipal Jwt jwt, @PathVariable long projectId, @PathVariable long permitId,
                             @Valid @RequestBody Request req) {
        access.member(projectId, jwt);
        ComplianceReport.Report r = report.load(projectId, permitId, req.from(), req.to(), req.emissionPointIds(), userId(jwt));
        return Map.of("documentId", documents.saveXlsx(permitId, r.fileName(), report.xlsx(r), userId(jwt)));
    }
}
