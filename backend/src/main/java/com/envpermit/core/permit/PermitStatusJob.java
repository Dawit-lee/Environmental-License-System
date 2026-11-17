package com.envpermit.core.permit;

import java.time.Clock;
import java.time.LocalDate;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 만료임박·만료는 날짜만 지나도 바뀌므로 매일(그리고 서버 시작 때) 상태를 다시 계산한다. 이력의 변경자는 비운다(자동). */
@Component
public class PermitStatusJob {

    private final PermitItemRepository permits;
    private final PermitStatusHistoryRepository history;
    private final Clock clock;

    PermitStatusJob(PermitItemRepository permits, PermitStatusHistoryRepository history, Clock clock) {
        this.permits = permits;
        this.history = history;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(cron = "0 5 0 * * *", zone = "Asia/Seoul")
    @Transactional
    public int refreshAll() {
        LocalDate today = LocalDate.now(clock);
        int changed = 0;
        // shortcut: loads every permit; fine for hundreds, query only dated rows if it grows to tens of thousands.
        for (PermitItem p : permits.findAll()) {
            PermitItem.Status before = p.getStatus();
            if (p.refreshStatus(today)) {
                history.save(new PermitStatusHistory(p.getId(), before, p.getStatus(), null, clock.instant()));
                changed++;
            }
        }
        return changed;
    }
}
