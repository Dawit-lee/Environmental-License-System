package com.envpermit.core.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Brute-force guard (H4): {@value #MAX_FAILURES} failed logins for one account from one IP within {@link #WINDOW}
 * lock that pair for {@link #LOCK}. A success clears the count.
 * shortcut: in memory, so a restart forgets and two servers count separately; move to a DB table when scaling out.
 */
@Component
public class LoginThrottle {

    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(10);
    static final Duration LOCK = Duration.ofMinutes(15);
    private static final int PRUNE_ABOVE = 10_000;

    private record State(int failures, Instant windowStart, Instant lockedUntil) {
    }

    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final Clock clock;

    LoginThrottle(Clock clock) {
        this.clock = clock;
    }

    boolean locked(String email, String ip) {
        State s = states.get(email + " " + ip);
        return s != null && s.lockedUntil() != null && clock.instant().isBefore(s.lockedUntil());
    }

    void failed(String email, String ip) {
        Instant now = clock.instant();
        if (states.size() > PRUNE_ABOVE) { // many accounts tried: drop finished windows so memory stays bounded
            states.values().removeIf(s -> expired(s, now));
        }
        states.compute(email + " " + ip, (k, s) -> {
            State cur = s == null || expired(s, now) ? new State(0, now, null) : s;
            int n = cur.failures() + 1;
            return new State(n, cur.windowStart(), n >= MAX_FAILURES ? now.plus(LOCK) : null);
        });
    }

    void succeeded(String email, String ip) {
        states.remove(email + " " + ip);
    }

    private static boolean expired(State s, Instant now) {
        return !now.isBefore(s.windowStart().plus(WINDOW)) && (s.lockedUntil() == null || !now.isBefore(s.lockedUntil()));
    }

    /** Tests only: the shared context would otherwise carry failures from one test into the next. */
    public void reset() {
        states.clear();
    }
}
