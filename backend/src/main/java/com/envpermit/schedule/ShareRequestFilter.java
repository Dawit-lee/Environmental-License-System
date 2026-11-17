package com.envpermit.schedule;

import com.envpermit.core.ApiErrors;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The token routes ({@code /share/**}, {@code /api/me/calendar.ics}): never cached or indexed, no referrer
 * (the token is in the URL), and a fixed one-minute window rate limit → 429: at most 60 requests per IP + token (calendar
 * services fetch many users' feeds from one IP), and at most 10 failed lookups (404: unknown, expired, revoked) per IP,
 * so guessing tokens stays slow.
 */
@Component
class ShareRequestFilter extends OncePerRequestFilter {

    static final int LIMIT_PER_MINUTE = 60;
    static final int FAILED_LIMIT_PER_MINUTE = 10;

    private final Clock clock;
    private final Map<String, Integer> counts = new ConcurrentHashMap<>();
    private final Map<String, Integer> failures = new ConcurrentHashMap<>();
    private long minute = Long.MIN_VALUE;

    ShareRequestFilter(Clock clock) {
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String path = req.getRequestURI();
        return !(path.startsWith("/share/") || path.equals("/api/me/calendar.ics"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        res.setHeader("Cache-Control", "private, no-store");
        res.setHeader("X-Robots-Tag", "noindex");
        res.setHeader("Referrer-Policy", "no-referrer");
        synchronized (this) {
            long now = clock.millis() / 60_000;
            if (now != minute) {
                counts.clear();
                failures.clear();
                minute = now;
            }
        }
        String ip = req.getRemoteAddr(); // the real client behind the tunnel (server.forward-headers-strategy)
        String key = ip + " " + req.getRequestURI() + "?" + req.getQueryString(); // the token is in the path or query
        if (failures.getOrDefault(ip, 0) >= FAILED_LIMIT_PER_MINUTE
                || counts.merge(key, 1, Integer::sum) > LIMIT_PER_MINUTE) {
            ApiErrors.write(res, HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 잦습니다. 잠시 후 다시 시도해 주세요.");
            return;
        }
        chain.doFilter(req, res);
        if (res.getStatus() == 404) {
            failures.merge(ip, 1, Integer::sum);
        }
    }

    /** Tests only: every test starts at the same fixed minute. */
    void reset() {
        counts.clear();
        failures.clear();
    }
}
