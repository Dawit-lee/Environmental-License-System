package com.envpermit.core.project;

import java.time.LocalDate;

/** 착수일·완료일 edited on the project form: the schedule moves its relative items in the same transaction (설계 §2.3). */
public record ProjectDatesChanged(long projectId, long actorId, LocalDate oldStart, LocalDate newStart,
                                  LocalDate oldEnd, LocalDate newEnd) {
}
