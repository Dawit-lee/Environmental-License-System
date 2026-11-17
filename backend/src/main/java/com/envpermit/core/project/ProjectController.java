package com.envpermit.core.project;

import static com.envpermit.core.project.ProjectAccess.userId;

import com.envpermit.core.permit.PermitItemRepository;
import com.envpermit.core.permit.PermitItemRepository.PermitCounts;
import com.envpermit.core.org.Org;
import com.fasterxml.jackson.databind.JsonNode;
import com.envpermit.core.user.User;
import com.envpermit.core.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ProjectController {

    private final ProjectRepository projects;
    private final ProjectMemberRepository members;
    private final PermitItemRepository permits;
    private final UserRepository users;
    private final ProjectAccess access;
    private final Org org;
    private final ApplicationEventPublisher events;
    private final JdbcTemplate jdbc;

    public ProjectController(ProjectRepository projects, ProjectMemberRepository members,
                             PermitItemRepository permits, UserRepository users, ProjectAccess access, Org org,
                             ApplicationEventPublisher events, JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.org = org;
        this.events = events;
        this.projects = projects;
        this.members = members;
        this.permits = permits;
        this.users = users;
        this.access = access;
    }

    record ProjectRequest(@NotBlank @Size(max = 200) String name,
                          @NotNull Project.FacilityType facilityType,
                          @NotBlank @Size(max = 300) String location,
                          BigDecimal capacity,
                          @Size(max = 20) String capacityUnit,
                          LocalDate startDate,
                          LocalDate expectedEndDate,
                          LocalDate constructionStartDate,
                          Project.Status status,
                          Long teamId,
                          Long pmUserId,
                          Long emUserId) {
    }

    record ProjectView(long id, String name, Project.FacilityType facilityType, String location,
                       BigDecimal capacity, String capacityUnit, LocalDate startDate, LocalDate expectedEndDate,
                       LocalDate constructionStartDate, Project.Status status, long permitCount, long acquiredCount, long nearExpiryCount,
                       Long teamId, String teamName, String departmentName,
                       Long pmUserId, String pmName, Long emUserId, String emName, boolean member,
                       JsonNode attributes, boolean operationsEnabled) {
    }

    record MemberView(long userId, String name, String email, boolean isAdmin,
                      boolean canSelfApprove, boolean canBulkApprove) {
        static MemberView of(ProjectMember m, User u) {
            return new MemberView(m.getUserId(), u.getName(), u.getEmail(), m.isAdmin(),
                    m.canSelfApprove(), m.canBulkApprove());
        }
    }

    record AddMemberRequest(@NotNull Long userId) {
    }

    record MemberFlags(Boolean isAdmin, Boolean canSelfApprove, Boolean canBulkApprove) {
    }

    // --- projects ---------------------------------------------------------

    /** My projects; {@code scope=company} lists every project of my company (read-only unless {@code member}). */
    @GetMapping("/api/projects")
    List<ProjectView> list(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "mine") String scope) {
        List<Project> list = "company".equals(scope)
                ? projects.findByCompanyIdOrderById(org.companyOf(jwt))
                : projects.findByMember(userId(jwt));
        return views(list, userId(jwt));
    }

    @PostMapping("/api/projects")
    @Transactional
    ResponseEntity<ProjectView> create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ProjectRequest req) {
        Long companyId = users.findById(userId(jwt)).map(User::getCompanyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "소속 회사가 없습니다."));
        checkOrg(companyId, req);
        Project p = new Project(companyId);
        p.apply(req);
        projects.save(p);
        setConstructionStart(p.getId(), req.constructionStartDate());
        members.save(ProjectMember.creator(p.getId(), userId(jwt)));
        return ResponseEntity.status(HttpStatus.CREATED).body(views(List.of(p), userId(jwt)).get(0));
    }

    @GetMapping("/api/projects/{id}")
    ProjectView get(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        access.viewer(id, jwt);
        return views(List.of(projects.findById(id).orElseThrow()), userId(jwt)).get(0);
    }

    @PutMapping("/api/projects/{id}")
    @Transactional
    ProjectView update(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                       @Valid @RequestBody ProjectRequest req) {
        ProjectMember me = access.member(id, jwt);
        Project p = projects.findById(id).orElseThrow();
        // PM·EM carry share-link and module powers, so only a project admin may reassign them (M1)
        if (!me.isAdmin() && !(Objects.equals(p.getTeamId(), req.teamId()) && Objects.equals(p.getPmUserId(), req.pmUserId())
                && Objects.equals(p.getEmUserId(), req.emUserId()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "팀·PM·EM은 프로젝트 어드민만 바꿀 수 있습니다.");
        }
        checkOrg(p.getCompanyId(), req);
        LocalDate oldStart = p.getStartDate();
        LocalDate oldEnd = p.getExpectedEndDate();
        LocalDate oldConstruction = constructionStarts(List.of(id)).get(id);
        p.apply(req);
        setConstructionStart(id, req.constructionStartDate());
        if (!Objects.equals(oldStart, p.getStartDate()) || !Objects.equals(oldEnd, p.getExpectedEndDate())
                || !Objects.equals(oldConstruction, req.constructionStartDate())) {
            events.publishEvent(new ProjectDatesChanged(id, userId(jwt), oldStart, p.getStartDate(), oldEnd,
                    p.getExpectedEndDate(), oldConstruction, req.constructionStartDate())); // relative items follow
        }
        return views(List.of(p), userId(jwt)).get(0);
    }

    @DeleteMapping("/api/projects/{id}")
    @Transactional
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        access.admin(id, jwt);
        projects.deleteById(id); // members, permits and history go with it (ON DELETE CASCADE)
        return ResponseEntity.noContent().build();
    }

    record ModulesRequest(@NotNull Boolean operations) {
    }

    /** design-schedule §8.1: project admins, the PM and the EM switch the 운영 module; every change is logged. */
    @PatchMapping("/api/projects/{id}/modules")
    @Transactional
    ProjectView modules(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @Valid @RequestBody ModulesRequest req) {
        boolean admin = access.member(id, jwt).isAdmin(); // PM·EM powers need membership too (M1)
        Project p = projects.findById(id).orElseThrow();
        long me = userId(jwt);
        if (!admin && !Objects.equals(p.getPmUserId(), me) && !Objects.equals(p.getEmUserId(), me)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "프로젝트 어드민·PM·EM만 모듈을 켜고 끌 수 있습니다.");
        }
        if (p.isOperationsEnabled() != req.operations()) {
            p.setOperationsEnabled(req.operations());
            jdbc.update("INSERT INTO project_module_event (project_id, module, enabled, actor_id) VALUES (?, 'OPERATIONS', ?, ?)",
                    id, req.operations(), me);
        }
        return views(List.of(p), me).get(0);
    }

    // --- members ----------------------------------------------------------

    @GetMapping("/api/projects/{id}/members")
    List<MemberView> members(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        access.viewer(id, jwt);
        List<ProjectMember> list = members.findByIdProjectIdOrderByIdUserId(id);
        Map<Long, User> byId = users.findAllById(list.stream().map(ProjectMember::getUserId).toList())
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));
        return list.stream().map(m -> MemberView.of(m, byId.get(m.getUserId()))).toList();
    }

    @PostMapping("/api/projects/{id}/members")
    @Transactional
    ResponseEntity<MemberView> addMember(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                         @Valid @RequestBody AddMemberRequest req) {
        access.admin(id, jwt);
        Project p = projects.findById(id).orElseThrow();
        User u = users.findById(req.userId())
                .filter(x -> p.getCompanyId().equals(x.getCompanyId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "같은 회사 사용자만 추가할 수 있습니다."));
        if (members.existsById(new ProjectMember.Key(id, u.getId()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 멤버입니다.");
        }
        ProjectMember m = members.save(ProjectMember.regular(id, u.getId()));
        return ResponseEntity.status(HttpStatus.CREATED).body(MemberView.of(m, u));
    }

    @PatchMapping("/api/projects/{id}/members/{userId}")
    @Transactional
    MemberView updateMember(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @PathVariable long userId,
                            @RequestBody MemberFlags req) {
        access.admin(id, jwt);
        ProjectMember m = target(id, userId);
        if (Boolean.FALSE.equals(req.isAdmin())) {
            requireAnotherAdmin(m);
        }
        m.update(req.isAdmin(), req.canSelfApprove(), req.canBulkApprove());
        return MemberView.of(m, users.findById(userId).orElseThrow());
    }

    @DeleteMapping("/api/projects/{id}/members/{userId}")
    @Transactional
    ResponseEntity<Void> removeMember(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                      @PathVariable long userId) {
        access.admin(id, jwt);
        ProjectMember m = target(id, userId);
        requireAnotherAdmin(m);
        members.delete(m);
        events.publishEvent(new MemberRemoved(id, userId, userId(jwt))); // their open tasks go back to 미지정
        return ResponseEntity.noContent().build();
    }

    private ProjectMember target(long projectId, long userId) {
        return members.findById(new ProjectMember.Key(projectId, userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    /** A project must keep at least one admin, or nobody could manage it again. */
    private void requireAnotherAdmin(ProjectMember m) {
        if (m.isAdmin() && members.countByIdProjectIdAndAdminTrue(m.getProjectId()) <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "마지막 어드민은 해제할 수 없습니다.");
        }
    }

    private void checkOrg(long companyId, ProjectRequest req) {
        org.requireTeam(companyId, req.teamId());
        org.requireUser(companyId, req.pmUserId());
        org.requireUser(companyId, req.emUserId());
    }

    List<ProjectView> views(List<Project> list, long userId) {
        if (list.isEmpty()) {
            return List.of();
        }
        List<Long> ids = list.stream().map(Project::getId).toList();
        Map<Long, LocalDate> construction = constructionStarts(ids);
        Map<Long, PermitCounts> counts = permits.countByProject(ids).stream()
                .collect(Collectors.toMap(PermitCounts::projectId, Function.identity()));
        Set<Long> mine = projects.findByMember(userId).stream().map(Project::getId).collect(Collectors.toSet());
        Map<Long, Org.Team> teams = org.teams(list.get(0).getCompanyId());
        Map<Long, String> names = users.findAllById(list.stream()
                        .flatMap(p -> Stream.of(p.getPmUserId(), p.getEmUserId())).filter(Objects::nonNull).toList())
                .stream().collect(Collectors.toMap(User::getId, User::getName));
        return list.stream().map(p -> {
            PermitCounts c = counts.getOrDefault(p.getId(), PermitCounts.none(p.getId()));
            Org.Team t = p.getTeamId() == null ? null : teams.get(p.getTeamId());
            return new ProjectView(p.getId(), p.getName(), p.getFacilityType(), p.getLocation(), p.getCapacity(),
                    p.getCapacityUnit(), p.getStartDate(), p.getExpectedEndDate(), construction.get(p.getId()), p.getStatus(),
                    c.total(), c.completed(), c.nearExpiry(),
                    p.getTeamId(), t == null ? null : t.name(), t == null ? null : t.departmentName(),
                    p.getPmUserId(), names.get(p.getPmUserId()), p.getEmUserId(), names.get(p.getEmUserId()),
                    mine.contains(p.getId()), p.getAttributes(), p.isOperationsEnabled());
        }).toList();
    }

    /** 착공예정일 is the schedule's CONSTRUCTION_START base date (project_schedule_anchor); 착수일 is project.start_date. */
    private Map<Long, LocalDate> constructionStarts(List<Long> ids) {
        Map<Long, LocalDate> m = new HashMap<>();
        jdbc.query("SELECT project_id, date FROM project_schedule_anchor WHERE anchor = 'CONSTRUCTION_START' "
                        + "AND project_id = ANY(?)", rs -> { m.put(rs.getLong(1), rs.getObject(2, LocalDate.class)); },
                (Object) ids.toArray(Long[]::new));
        return m;
    }

    private void setConstructionStart(long id, LocalDate date) {
        if (date == null) {
            jdbc.update("DELETE FROM project_schedule_anchor WHERE project_id = ? AND anchor = 'CONSTRUCTION_START'", id);
        } else {
            jdbc.update("""
                    INSERT INTO project_schedule_anchor (project_id, anchor, date) VALUES (?, 'CONSTRUCTION_START', ?)
                    ON CONFLICT (project_id, anchor) DO UPDATE SET date = excluded.date""", id, java.sql.Date.valueOf(date));
        }
    }
}
