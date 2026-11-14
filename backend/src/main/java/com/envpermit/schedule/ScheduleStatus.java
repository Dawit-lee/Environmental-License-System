package com.envpermit.schedule;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Pure schedule rules (설계 §2.2). Nothing here is stored: status and delay are computed for "today". */
final class ScheduleStatus {

    enum Status { DONE, DELAYED, IN_PROGRESS, PLANNED }

    /** delayDays: days past the planned end — until today while open, until the actual end once done; 0 if on time. */
    record Computed(Status status, int delayDays) {
    }

    private ScheduleStatus() {
    }

    static Computed of(LocalDate start, LocalDate end, LocalDate actualEnd, LocalDate today) {
        if (actualEnd != null) {
            return new Computed(Status.DONE, daysAfter(end, actualEnd));
        }
        if (end.isBefore(today)) {
            return new Computed(Status.DELAYED, daysAfter(end, today));
        }
        return new Computed(start.isAfter(today) ? Status.PLANNED : Status.IN_PROGRESS, 0);
    }

    private static int daysAfter(LocalDate end, LocalDate date) {
        return (int) Math.max(0, ChronoUnit.DAYS.between(end, date));
    }

    /** True if adding predecessor → successor to the FS edges [pred, succ] would close a cycle. */
    static boolean createsCycle(Collection<long[]> edges, long predecessor, long successor) {
        Map<Long, List<Long>> next = edges.stream().collect(Collectors.groupingBy(
                e -> e[0], Collectors.mapping(e -> e[1], Collectors.toList())));
        // a cycle exists iff predecessor is already reachable from successor
        Deque<Long> todo = new ArrayDeque<>(List.of(successor));
        Set<Long> seen = new HashSet<>();
        while (!todo.isEmpty()) {
            long n = todo.pop();
            if (n == predecessor) return true;
            if (seen.add(n)) todo.addAll(next.getOrDefault(n, List.of()));
        }
        return false;
    }
}
