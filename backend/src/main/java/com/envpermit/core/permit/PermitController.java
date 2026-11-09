package com.envpermit.core.permit;

import static com.envpermit.core.project.ProjectAccess.userId;

import com.envpermit.core.project.ProjectAccess;
import com.envpermit.core.user.User;
import com.envpermit.core.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class PermitController {

    private final PermitItemRepository permits;
    private final PermitStatusHistoryRepository history;
    private final UserRepository users;
    private final ProjectAccess access;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public PermitController(PermitItemRepository permits, PermitStatusHistoryRepository history,
                            UserRepository users, ProjectAccess access, Clock clock,
                            ApplicationEventPublisher events) {
        this.permits = permits;
        this.history = history;
        this.users = users;
        this.access = access;
        this.clock = clock;
        this.events = events;
    }

    record PermitRequest(@NotBlank @Size(max = 200) String name,
                         @NotNull PermitItem.Category category,
                         @Size(max = 300) String regulation,
                         @Size(max = 500) String regulationUrl,
                         @Size(max = 100) String authority,
                         @Size(max = 500) String authorityUrl,
                         String reason,
                         @Size(max = 100) String approvalDate,
                         LocalDate applicationDate,
                         LocalDate acquisitionDate,
                         LocalDate expiryDate,
                         @NotNull PermitItem.Status status,
                         String nextAction,
                         @Size(max = 500) String nextActionUrl) {
    }

    record PermitView(long id, long projectId, String name, PermitItem.Category category, String regulation,
                      String regulationUrl, String authority, String authorityUrl, String reason,
                      String approvalDate, LocalDate applicationDate, LocalDate acquisitionDate,
                      LocalDate expiryDate, PermitItem.Status status, String nextAction, String nextActionUrl,
                      Long dDay) {
    }

    record HistoryView(long id, PermitItem.Status fromStatus, PermitItem.Status toStatus,
                       String changedBy, Instant changedAt) {
    }

    @GetMapping("/api/projects/{projectId}/permits")
    List<PermitView> list(@AuthenticationPrincipal Jwt jwt, @PathVariable long projectId) {
        access.member(projectId, jwt);
        return permits.findByProjectIdOrderById(projectId).stream().map(this::view).toList();
    }

    @GetMapping("/api/projects/{projectId}/permits/{id}")
    PermitView get(@AuthenticationPrincipal Jwt jwt, @PathVariable long projectId, @PathVariable long id) {
        access.member(projectId, jwt);
        return view(find(projectId, id));
    }

    @PostMapping("/api/projects/{projectId}/permits")
    @Transactional
    ResponseEntity<PermitView> create(@AuthenticationPrincipal Jwt jwt, @PathVariable long projectId,
                                      @Valid @RequestBody PermitRequest req) {
        access.member(projectId, jwt);
        PermitItem p = new PermitItem(projectId);
        p.apply(req);
        permits.save(p);
        history.save(new PermitStatusHistory(p.getId(), null, p.getStatus(), userId(jwt), clock.instant()));
        events.publishEvent(new PermitChanged(p, false));
        return ResponseEntity.status(HttpStatus.CREATED).body(view(p));
    }

    @PutMapping("/api/projects/{projectId}/permits/{id}")
    @Transactional
    PermitView update(@AuthenticationPrincipal Jwt jwt, @PathVariable long projectId, @PathVariable long id,
                      @Valid @RequestBody PermitRequest req) {
        access.member(projectId, jwt);
        PermitItem p = find(projectId, id);
        PermitItem.Status before = p.getStatus();
        p.apply(req);
        if (before != p.getStatus()) {
            history.save(new PermitStatusHistory(id, before, p.getStatus(), userId(jwt), clock.instant()));
        }
        events.publishEvent(new PermitChanged(p, false));
        return view(p);
    }

    @DeleteMapping("/api/projects/{projectId}/permits/{id}")
    @Transactional
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable long projectId,
                                @PathVariable long id) {
        access.member(projectId, jwt);
        PermitItem p = find(projectId, id);
        permits.delete(p);
        events.publishEvent(new PermitChanged(p, true));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/projects/{projectId}/permits/{id}/history")
    List<HistoryView> history(@AuthenticationPrincipal Jwt jwt, @PathVariable long projectId,
                              @PathVariable long id) {
        access.member(projectId, jwt);
        find(projectId, id);
        List<PermitStatusHistory> rows = history.findByPermitItemIdOrderByChangedAtDescIdDesc(id);
        Map<Long, String> names = users.findAllById(rows.stream().map(PermitStatusHistory::getChangedBy).toList())
                .stream().collect(Collectors.toMap(User::getId, User::getName));
        return rows.stream().map(h -> new HistoryView(h.getId(), h.getFromStatus(), h.getToStatus(),
                names.get(h.getChangedBy()), h.getChangedAt())).toList();
    }

    private PermitItem find(long projectId, long id) {
        return permits.findByIdAndProjectId(id, projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private PermitView view(PermitItem p) {
        Long dDay = p.getExpiryDate() == null ? null
                : ChronoUnit.DAYS.between(LocalDate.now(clock), p.getExpiryDate());
        return new PermitView(p.getId(), p.getProjectId(), p.getName(), p.getCategory(), p.getRegulation(),
                p.getRegulationUrl(), p.getAuthority(), p.getAuthorityUrl(), p.getReason(), p.getApprovalDate(),
                p.getApplicationDate(), p.getAcquisitionDate(), p.getExpiryDate(), p.getStatus(), p.getNextAction(),
                p.getNextActionUrl(), dDay);
    }
}
