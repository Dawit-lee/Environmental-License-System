package com.envpermit.core.org;

import com.envpermit.core.user.User;
import com.envpermit.core.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
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

/**
 * The caller's company org chart (department > team) and team assignment of its users.
 * Everyone in the company reads it; only the company master (role ADMIN) changes it.
 * Rows of another company answer 404, so their ids are not revealed.
 */
@RestController
public class OrgController {

    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final Org org;

    public OrgController(JdbcTemplate jdbc, UserRepository users, Org org) {
        this.jdbc = jdbc;
        this.users = users;
        this.org = org;
    }

    record NameRequest(@NotBlank @Size(max = 100) String name) {
    }

    record TeamView(long id, String name) {
    }

    record DepartmentView(long id, String name, List<TeamView> teams) {
    }

    record UserView(long id, String name, String email, Long teamId) {
    }

    record TeamAssignment(Long teamId) {
    }

    @GetMapping("/api/departments")
    List<DepartmentView> departments(@AuthenticationPrincipal Jwt jwt) {
        Map<Long, DepartmentView> byId = new LinkedHashMap<>();
        jdbc.query("""
                SELECT d.id, d.name, t.id AS team_id, t.name AS team_name
                FROM department d LEFT JOIN team t ON t.department_id = d.id
                WHERE d.company_id = ? ORDER BY d.name, d.id, t.name, t.id""", rs -> {
            String name = rs.getString("name");
            DepartmentView d = byId.computeIfAbsent(rs.getLong("id"), k -> new DepartmentView(k, name, new ArrayList<>()));
            long teamId = rs.getLong("team_id");
            if (!rs.wasNull()) {
                d.teams().add(new TeamView(teamId, rs.getString("team_name")));
            }
        }, org.companyOf(jwt));
        return List.copyOf(byId.values());
    }

    @PostMapping("/api/departments")
    ResponseEntity<TeamView> createDepartment(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody NameRequest req) {
        long companyId = master(jwt);
        Long id = unique(() -> jdbc.queryForObject(
                "INSERT INTO department (company_id, name) VALUES (?, ?) RETURNING id", Long.class,
                companyId, req.name().trim()));
        return ResponseEntity.status(HttpStatus.CREATED).body(new TeamView(id, req.name().trim()));
    }

    @PutMapping("/api/departments/{id}")
    TeamView renameDepartment(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                              @Valid @RequestBody NameRequest req) {
        long companyId = master(jwt);
        found(unique(() -> jdbc.update("UPDATE department SET name = ? WHERE id = ? AND company_id = ?",
                req.name().trim(), id, companyId)));
        return new TeamView(id, req.name().trim());
    }

    /** Its teams go with it; their users and projects become unassigned. */
    @DeleteMapping("/api/departments/{id}")
    ResponseEntity<Void> deleteDepartment(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        found(jdbc.update("DELETE FROM department WHERE id = ? AND company_id = ?", id, master(jwt)));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/departments/{id}/teams")
    ResponseEntity<TeamView> createTeam(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                        @Valid @RequestBody NameRequest req) {
        long companyId = master(jwt);
        found(jdbc.queryForObject("SELECT count(*) FROM department WHERE id = ? AND company_id = ?",
                Integer.class, id, companyId));
        Long teamId = unique(() -> jdbc.queryForObject(
                "INSERT INTO team (department_id, name) VALUES (?, ?) RETURNING id", Long.class, id, req.name().trim()));
        return ResponseEntity.status(HttpStatus.CREATED).body(new TeamView(teamId, req.name().trim()));
    }

    @PutMapping("/api/teams/{id}")
    TeamView renameTeam(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @Valid @RequestBody NameRequest req) {
        long companyId = master(jwt);
        found(unique(() -> jdbc.update("""
                UPDATE team SET name = ? WHERE id = ?
                  AND department_id IN (SELECT id FROM department WHERE company_id = ?)""",
                req.name().trim(), id, companyId)));
        return new TeamView(id, req.name().trim());
    }

    @DeleteMapping("/api/teams/{id}")
    ResponseEntity<Void> deleteTeam(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        found(jdbc.update("DELETE FROM team WHERE id = ? AND department_id IN "
                + "(SELECT id FROM department WHERE company_id = ?)", id, master(jwt)));
        return ResponseEntity.noContent().build();
    }

    /** Users of my company — for team assignment and the PM/EM pickers. */
    @GetMapping("/api/company/users")
    List<UserView> companyUsers(@AuthenticationPrincipal Jwt jwt) {
        return users.findByCompanyIdOrderByName(org.companyOf(jwt)).stream()
                .map(u -> new UserView(u.getId(), u.getName(), u.getEmail(), u.getTeamId())).toList();
    }

    @PutMapping("/api/company/users/{id}/team")
    @Transactional
    UserView assignTeam(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @RequestBody TeamAssignment req) {
        long companyId = master(jwt);
        User u = users.findById(id).filter(x -> Long.valueOf(companyId).equals(x.getCompanyId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        org.requireTeam(companyId, req.teamId());
        u.setTeamId(req.teamId());
        return new UserView(u.getId(), u.getName(), u.getEmail(), u.getTeamId());
    }

    private long master(Jwt jwt) {
        if (!"ADMIN".equals(jwt.getClaimAsString("role"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "회사 마스터만 변경할 수 있습니다.");
        }
        return org.companyOf(jwt);
    }

    private static void found(Integer rows) {
        if (rows == null || rows == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private static <T> T unique(Supplier<T> write) {
        try {
            return write.get();
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "같은 이름이 이미 있습니다.");
        }
    }
}
