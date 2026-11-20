package com.envpermit.core.auth;

import com.envpermit.core.ApiErrors;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The public demo login (seeded by db/dev R__dev_user_demo.sql) is shared by anyone on the internet, so it may only read:
 * every non-GET request outside /api/auth/** is 403. Runs after bearer auth; not a bean, so Boot does not register it twice.
 */
public class DemoReadOnly extends OncePerRequestFilter {

    public static final String EMAIL = "demo@envpermit.local";
    private static final Set<String> READS = Set.of("GET", "HEAD", "OPTIONS");
    /** Opening a cited page on the review screen logs a view; for the demo it is skipped quietly, not a red 403. */
    private static final Pattern PAGE_VIEW = Pattern.compile("/api/suggestions/\\d+/page-views");

    private final JdbcTemplate jdbc;

    public DemoReadOnly(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One PK lookup, only on writes. */
    public static boolean isDemo(JdbcTemplate jdbc, long userId) {
        return Boolean.TRUE.equals(jdbc.query("SELECT email = ? FROM users WHERE id = ?",
                rs -> rs.next() && rs.getBoolean(1), EMAIL, userId));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (!READS.contains(req.getMethod()) && !req.getRequestURI().startsWith("/api/auth/")
                && SecurityContextHolder.getContext().getAuthentication() != null
                && SecurityContextHolder.getContext().getAuthentication().getPrincipal() instanceof Jwt jwt
                && isDemo(jdbc, Long.parseLong(jwt.getSubject()))) {
            if (PAGE_VIEW.matcher(req.getRequestURI()).matches()) {
                res.setStatus(HttpStatus.NO_CONTENT.value());
                return;
            }
            ApiErrors.write(res, HttpStatus.FORBIDDEN, "데모 계정은 조회만 가능합니다.");
            return;
        }
        chain.doFilter(req, res);
    }
}
