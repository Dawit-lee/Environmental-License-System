package com.envpermit.schedule;

import com.envpermit.core.org.Org;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The caller's company discipline list (분야, 설계 §2.1). Everyone in the company reads it; only the company master
 * (role ADMIN) adds, renames, reorders or deactivates. No delete: an inactive one stays on its existing items.
 */
@RestController
class DisciplineController {

    private final JdbcTemplate jdbc;
    private final Org org;

    DisciplineController(JdbcTemplate jdbc, Org org) {
        this.jdbc = jdbc;
        this.org = org;
    }

    record DisciplineView(long id, String code, String name, int sortOrder, boolean active) {
    }

    record CreateRequest(@NotBlank @Size(max = 100) String name) {
    }

    /** Partial: null fields stay as they are. */
    record UpdateRequest(@Pattern(regexp = ".*\\S.*") @Size(max = 100) String name, Integer sortOrder, Boolean active) {
    }

    @GetMapping("/api/disciplines")
    List<DisciplineView> list(@AuthenticationPrincipal Jwt jwt) {
        return list(org.companyOf(jwt));
    }

    @PostMapping("/api/disciplines")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    DisciplineView create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateRequest req) {
        long companyId = master(jwt);
        long id = unique(() -> jdbc.queryForObject("""
                INSERT INTO discipline (company_id, name, sort_order)
                VALUES (?, ?, (SELECT coalesce(max(sort_order), 0) + 10 FROM discipline WHERE company_id = ?))
                RETURNING id""", Long.class, companyId, req.name().trim(), companyId));
        return find(companyId, id);
    }

    @PatchMapping("/api/disciplines/{id}")
    @Transactional
    DisciplineView update(@AuthenticationPrincipal Jwt jwt, @PathVariable long id, @Valid @RequestBody UpdateRequest req) {
        long companyId = master(jwt);
        int rows = unique(() -> jdbc.update("""
                UPDATE discipline SET name = coalesce(?, name), sort_order = coalesce(?, sort_order),
                                      active = coalesce(?, active)
                WHERE id = ? AND company_id = ?""",
                req.name() == null ? null : req.name().trim(), req.sortOrder(), req.active(), id, companyId));
        if (rows == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return find(companyId, id);
    }

    private DisciplineView find(long companyId, long id) {
        return list(companyId).stream().filter(d -> d.id() == id).findFirst().orElseThrow();
    }

    private List<DisciplineView> list(long companyId) {
        return jdbc.query("SELECT id, code, name, sort_order, active FROM discipline WHERE company_id = ? ORDER BY sort_order, id",
                (rs, i) -> new DisciplineView(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                        rs.getInt("sort_order"), rs.getBoolean("active")), companyId);
    }

    private long master(Jwt jwt) {
        if (!"ADMIN".equals(jwt.getClaimAsString("role"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "회사 마스터만 변경할 수 있습니다.");
        }
        return org.companyOf(jwt);
    }

    private static <T> T unique(java.util.function.Supplier<T> write) {
        try {
            return write.get();
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "같은 이름의 분야가 이미 있습니다.");
        }
    }
}
