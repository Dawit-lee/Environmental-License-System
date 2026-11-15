package com.envpermit.schedule;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The token routes ({@code /share/**}, {@code /api/me/calendar.ics}): never cached or indexed, no referrer
 * (the token is in the URL), and at most 60 requests per IP per minute (fixed window) → 429.
 */
@Component
class ShareRequestFilter extends OncePerRequestFilter {

    static final int LIMIT_PER_MINUTE = 60;

    private final Clock clock;
    private final Map<String, Integer> counts = new ConcurrentHashMap<>();
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
                minute = now;
            }
        }
        // shortcut: keyed by remote IP only. Calendar services (Google, Outlook) fetch many feeds from shared IPs and a
        // reverse proxy makes every caller one IP: key by IP + token hash once feeds go to real calendar services,
        // and trust X-Forwarded-For from the proxy once one is deployed
        if (counts.merge(req.getRemoteAddr(), 1, Integer::sum) > LIMIT_PER_MINUTE) {
            res.setStatus(429);
            return;
        }
        chain.doFilter(req, res);
    }

    /** Tests only: every test starts at the same fixed minute. */
    void reset() {
        counts.clear();
    }
}
