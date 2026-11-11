package com.envpermit.operation;

import java.time.LocalDate;
import java.util.Collection;

/**
 * 기한 내 이행률 (design §5.1), the one formula behind every compliance number. Pure: no clock, no database.
 * Denominator = tasks (OBLIGATION + CORRECTIVE) that are not cancelled and whose due date is in [from, min(to, today)];
 * numerator = those finished on or before the due date. Summing tasks of several projects before calling this
 * gives the company rate (counts are added, rates are never averaged).
 */
final class ComplianceRate {

    private ComplianceRate() {
    }

    /** doneOn: the local date of done_at, null unless DONE. */
    record Task(String kind, String status, LocalDate dueDate, LocalDate doneOn) {
    }

    /**
     * percent is null when nothing fell due ("해당 없음", never 0 or 100).
     * late (지연 완료) is part of due; overdue, openCorrective and dueSoon describe today and ignore the period.
     */
    record Counts(int due, int onTime, int late, Double percent, int overdue, int openCorrective, int dueSoon) {
    }

    static Counts of(Collection<Task> tasks, LocalDate from, LocalDate to, LocalDate today) {
        LocalDate end = to.isAfter(today) ? today : to;
        int due = 0, onTime = 0, late = 0, overdue = 0, openCorrective = 0, dueSoon = 0;
        for (Task t : tasks) {
            boolean open = t.status().equals("OPEN");
            if (!t.status().equals("CANCELLED") && !t.dueDate().isBefore(from) && !t.dueDate().isAfter(end)) {
                due++;
                if (t.doneOn() != null && !t.doneOn().isAfter(t.dueDate())) {
                    onTime++;
                } else if (t.doneOn() != null) {
                    late++;
                }
            }
            if (open && t.dueDate().isBefore(today)) {
                overdue++;
            }
            if (open && t.kind().equals("CORRECTIVE")) {
                openCorrective++;
            }
            if (open && t.dueDate().isAfter(today) && !t.dueDate().isAfter(today.plusDays(30))) {
                dueSoon++;
            }
        }
        return new Counts(due, onTime, late, due == 0 ? null : Math.round(onTime * 1000.0 / due) / 10.0,
                overdue, openCorrective, dueSoon);
    }
}
