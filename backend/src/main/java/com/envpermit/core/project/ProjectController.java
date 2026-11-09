package com.envpermit.core.project;

import static com.envpermit.core.project.ProjectAccess.userId;

import com.envpermit.core.permit.PermitItemRepository;
import com.envpermit.core.permit.PermitItemRepository.PermitCounts;
import com.envpermit.core.user.User;
import com.envpermit.core.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ProjectController {

    private final ProjectRepository projects;
    private final ProjectMemberRepository members;
    private final PermitItemRepository permits;
    private final UserRepository users;
    private final ProjectAccess access;

    public ProjectController(ProjectRepository projects, ProjectMemberRepository members,
                             PermitItemRepository permits, UserRepository users, ProjectAccess access) {
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
                          Project.Status status) {
    }

    record ProjectView(long id, String name, Project.FacilityType facilityType, String location,
                       BigDecimal capacity, String capacityUnit, LocalDate startDate, LocalDate expectedEndDate,
                       Project.Status status, long permitCount, long acquiredCount, long nearExpiryCount) {
        static ProjectView of(Project p, PermitCounts c) {
            return new ProjectView(p.getId(), p.getName(), p.getFacilityType(), p.getLocation(), p.getCapacity(),
                    p.getCapacityUnit(), p.getStartDate(), p.getExpectedEndDate(), p.getStatus(),
                    c.total(), c.completed(), c.nearExpiry());
        }
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

    @GetMapping("/api/projects")
    List<ProjectView> list(@AuthenticationPrincipal Jwt jwt) {
        List<Project> mine = projects.findByMember(userId(jwt));
        Map<Long, PermitCounts> counts = permits.countByProject(mine.stream().map(Project::getId).toList())
                .stream().collect(Collectors.toMap(PermitCounts::projectId, Function.identity()));
        return mine.stream()
                .map(p -> ProjectView.of(p, counts.getOrDefault(p.getId(), PermitCounts.none(p.getId()))))
                .toList();
    }

    @PostMapping("/api/projects")
    @Transactional
    ResponseEntity<ProjectView> create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ProjectRequest req) {
        Long companyId = users.findById(userId(jwt)).map(User::getCompanyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "소속 회사가 없습니다."));
        Project p = new Project(companyId);
        p.apply(req);
        projects.save(p);
        members.save(ProjectMember.creator(p.getId(), userId(jwt)));
        return ResponseEntity.status(HttpStatus.CREATED).body(ProjectView.of(p, PermitCounts.none(p.getId())));
    }

    @GetMapping("/api/projects/{id}")
    ProjectView get(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        access.member(id, jwt);
        return view(projects.findById(id).orElseThrow());
    }

    @PutMapping("/api/projects/{id}")
    @Transactional
    ProjectView update(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                       @Valid @RequestBody ProjectRequest req) {
        access.member(id, jwt);
        Project p = projects.findById(id).orElseThrow();
        p.apply(req);
        return view(p);
    }

    @DeleteMapping("/api/projects/{id}")
    @Transactional
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        access.admin(id, jwt);
        projects.deleteById(id); // members, permits and history go with it (ON DELETE CASCADE)
        return ResponseEntity.noContent().build();
    }

    // --- members ----------------------------------------------------------

    @GetMapping("/api/projects/{id}/members")
    List<MemberView> members(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        access.member(id, jwt);
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

    private ProjectView view(Project p) {
        return ProjectView.of(p, permits.countByProject(List.of(p.getId())).stream().findFirst()
                .orElse(PermitCounts.none(p.getId())));
    }
}
