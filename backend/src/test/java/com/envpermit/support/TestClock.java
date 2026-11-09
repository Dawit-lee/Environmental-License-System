package com.envpermit.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** Fake clock for tests. External-adapter fakes (Claude, 법제처, mail) go in this package too. */
public class TestClock extends Clock {

    private Instant now;
    private final ZoneId zone;

    public TestClock(Instant start, ZoneId zone) {
        this.now = start;
        this.zone = zone;
    }

    public void advance(Duration d) {
        now = now.plus(d);
    }

    public void set(Instant instant) {
        now = instant;
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new TestClock(now, zone);
    }
}
