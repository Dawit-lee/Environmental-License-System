package com.envpermit.core.org;

import com.envpermit.core.user.User;
import com.envpermit.core.user.UserRepository;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Company/team lookups shared by the org and project APIs. */
@Component
public class Org {

    public record Team(long id, String name, String departmentName) {
    }

    private final JdbcTemplate jdbc;
    private final UserRepository users;

    public Org(JdbcTemplate jdbc, UserRepository users) {
        this.jdbc = jdbc;
        this.users = users;
    }

    public long companyOf(Jwt jwt) {
        return users.findById(Long.parseLong(jwt.getSubject())).map(User::getCompanyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "소속 회사가 없습니다."));
    }

    public Map<Long, Team> teams(long companyId) {
        Map<Long, Team> byId = new HashMap<>();
        jdbc.query("SELECT t.id, t.name, d.name AS dept FROM team t JOIN department d ON d.id = t.department_id "
                + "WHERE d.company_id = ?", rs -> {
            byId.put(rs.getLong("id"), new Team(rs.getLong("id"), rs.getString("name"), rs.getString("dept")));
        }, companyId);
        return byId;
    }

    /** null is allowed (unassigned); a team of another company is a 400. */
    public void requireTeam(long companyId, Long teamId) {
        if (teamId != null && !teams(companyId).containsKey(teamId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "같은 회사의 팀만 지정할 수 있습니다.");
        }
    }

    /** null is allowed; a user of another company is a 400. */
    public void requireUser(long companyId, Long userId) {
        if (userId != null && users.findById(userId).map(User::getCompanyId)
                .filter(c -> c == companyId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "같은 회사 사용자만 지정할 수 있습니다.");
        }
    }
}
