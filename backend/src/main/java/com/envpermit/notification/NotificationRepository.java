package com.envpermit.notification;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface NotificationRepository extends JpaRepository<Notification, Long> {

    boolean existsByDeadlineEventIdAndDueDateAndOffsetDaysAndUserId(long eventId, LocalDate dueDate, int offsetDays,
                                                                    long userId);

    // shortcut: newest 50 only, no paging; add paging when a "see all" page exists
    List<Notification> findTop50ByUserIdOrderByCreatedAtDescDueDateAscIdDesc(long userId);

    Optional<Notification> findByIdAndUserId(long id, long userId);

    List<Notification> findByEmailedAtIsNullOrderByUserIdAscOffsetDaysAscDueDateAscIdAsc();

    @Modifying
    @Query("update Notification n set n.readAt = ?2 where n.userId = ?1 and n.readAt is null")
    void markAllRead(long userId, Instant now);
}
