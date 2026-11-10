package com.envpermit.notification;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface DeadlineEventRepository extends JpaRepository<DeadlineEvent, Long> {

    Optional<DeadlineEvent> findByKindAndTargetId(String kind, long targetId);

    void deleteByKindAndTargetId(String kind, long targetId);

    List<DeadlineEvent> findByDueDateBetween(LocalDate from, LocalDate to);

    List<DeadlineEvent> findByDueDateAndKindIn(LocalDate dueDate, java.util.Collection<String> kinds);

    @Query("select e from DeadlineEvent e where e.dueDate between ?2 and ?3 and e.projectId in "
            + "(select m.id.projectId from ProjectMember m where m.id.userId = ?1) order by e.dueDate, e.id")
    List<DeadlineEvent> findVisible(long userId, LocalDate from, LocalDate to);
}
