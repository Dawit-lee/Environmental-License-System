package com.envpermit.law;

import com.envpermit.law.LawApiClient.ChangedArticle;
import com.envpermit.law.LawApiClient.LawBody;
import com.envpermit.law.LawApiClient.LawRow;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Daily 06:00 KST: for each day after the cursor through yesterday (≤31 per run), reads 법제처's change list
 * (lsHstInf) and stores new 현행 versions of watched laws with their changed articles and 신구조문.
 * One transaction per day; a failing day rolls back and the run stops there. Each stored amendment gets its
 * permit impacts; the run ends with the impact mail.
 * shortcut: single server, no distributed lock; add ShedLock when scaling out.
 */
@Component
public class AmendmentBatch {

    static final String JOB = "law-amendment";
    static final int MAX_DAYS = 31;
    private static final Logger log = LoggerFactory.getLogger(AmendmentBatch.class);

    /** Catalog state of a watched law. */
    record Watched(long catalogId, Long lastMst, LocalDate lastPromulgatedOn) {
    }

    private final LawApiClient api;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final LawImpacts impacts;

    AmendmentBatch(LawApiClient api, JdbcTemplate jdbc, TransactionTemplate tx, Clock clock, LawImpacts impacts) {
        this.impacts = impacts;
        this.api = api;
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * Change lists re-list old versions (연혁, some decades old, and the same law's previous MST),
     * so only a 현행 row newer than the catalog is an amendment.
     */
    static boolean isNewVersion(LawRow r, Watched w) {
        if (!"현행".equals(r.status())) {
            return false;
        }
        if (w.lastMst() != null) {
            return r.mst() > w.lastMst();
        }
        return w.lastPromulgatedOn() == null || r.promulgatedOn() != null && r.promulgatedOn().isAfter(w.lastPromulgatedOn());
    }

    @Scheduled(cron = "0 0 6 * * *", zone = "Asia/Seoul")
    void scheduled() {
        if (!api.configured()) {
            log.warn("LAW_API_OC가 없어 법령 개정 배치를 건너뜁니다.");
            return;
        }
        run();
    }

    public void run() {
        LocalDate yesterday = LocalDate.now(clock).minusDays(1);
        List<LocalDate> cursor = jdbc.queryForList(
                "SELECT last_success_date FROM batch_cursor WHERE job_name = ?", LocalDate.class, JOB);
        if (cursor.isEmpty()) { // first run: no back-filling
            jdbc.update("INSERT INTO batch_cursor (job_name, last_success_date) VALUES (?, ?)", JOB, yesterday);
            return;
        }
        LocalDate last = cursor.get(0);
        for (LocalDate d = last.plusDays(1); !d.isAfter(yesterday) && !d.isAfter(last.plusDays(MAX_DAYS)); d = d.plusDays(1)) {
            LocalDate day = d;
            try {
                tx.executeWithoutResult(s -> processDay(day));
            } catch (RuntimeException e) {
                log.warn("법령 개정 배치 실패, {}에서 멈춤", day, e);
                int failures = jdbc.queryForObject("UPDATE batch_cursor SET consecutive_failures = consecutive_failures + 1 "
                        + "WHERE job_name = ? RETURNING consecutive_failures", Integer.class, JOB);
                if (failures == 2) {
                    impacts.alertAdmins(day, failures);
                }
                break;
            }
        }
        refetchOldNew();
        impacts.mailDigest();
    }

    private void processDay(LocalDate day) {
        Map<String, Watched> watched = watched();
        for (LawRow r : api.changes(day)) {
            Watched w = watched.get(r.lawId());
            if (w == null || !isNewVersion(r, w)
                    || jdbc.queryForObject("SELECT exists(SELECT 1 FROM law_amendment WHERE mst = ?)", Boolean.class, r.mst())) {
                continue;
            }
            LawBody body = api.body(r.mst());
            Long id = jdbc.queryForObject("""
                    INSERT INTO law_amendment (law_catalog_id, mst, promulgated_on, enforced_on, revision_type, reason,
                                               old_new_raw, detected_on)
                    VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?) RETURNING id""", Long.class,
                    w.catalogId(), r.mst(), r.promulgatedOn(), r.enforcedOn(), r.revisionType(), body.reason(),
                    oldNewOrNull(r.mst()), day);
            for (ChangedArticle a : body.changed()) {
                jdbc.update("INSERT INTO law_amendment_article (amendment_id, article_no, jo_code, change_reason, "
                        + "article_enforced_on) VALUES (?, ?, ?, ?, ?)", id, a.articleNo(), a.joCode(), a.changeType(), a.enforcedOn());
            }
            impacts.record(id);
            jdbc.update("UPDATE law_catalog SET last_mst = ?, last_promulgated_on = ?, last_enforced_on = ?, "
                            + "repealed = ? WHERE id = ?", r.mst(), r.promulgatedOn(), r.enforcedOn(),
                    r.revisionType().contains("폐지"), w.catalogId()); // 폐지 spelling unverified (design §11)
            watched.put(r.lawId(), new Watched(w.catalogId(), r.mst(), r.promulgatedOn()));
        }
        jdbc.update("UPDATE batch_cursor SET last_success_date = ?, consecutive_failures = 0 WHERE job_name = ?", day, JOB);
    }

    /**
     * Not stored: laws linked to permit items still in play, plus their 시행령·시행규칙 (parent_id).
     * shortcut: COMPLETED/EXPIRED are "not in play" until a 취소 status exists; revisit with L3.
     */
    private Map<String, Watched> watched() {
        Map<String, Watched> m = new HashMap<>();
        jdbc.query("""
                WITH linked AS (SELECT l.law_catalog_id FROM permit_law_link l
                                JOIN permit_item p ON p.id = l.permit_item_id
                                WHERE p.status NOT IN ('COMPLETED', 'EXPIRED'))
                SELECT id, law_id, last_mst, last_promulgated_on FROM law_catalog
                WHERE id IN (SELECT * FROM linked) OR parent_id IN (SELECT * FROM linked)""",
                rs -> {
                    m.put(rs.getString("law_id"), new Watched(rs.getLong("id"), rs.getObject("last_mst", Long.class),
                            rs.getObject("last_promulgated_on", LocalDate.class)));
                });
        return m;
    }

    /** 신구조문 is optional: the amendment is stored without it and the end of a run retries. */
    private String oldNewOrNull(long mst) {
        try {
            return api.oldAndNew(mst);
        } catch (RuntimeException e) {
            log.warn("신구조문 조회 실패 MST {}: {}", mst, e.getMessage());
            return null;
        }
    }

    private void refetchOldNew() {
        for (Long mst : jdbc.queryForList("SELECT mst FROM law_amendment WHERE old_new_raw IS NULL", Long.class)) {
            String raw = oldNewOrNull(mst);
            if (raw != null) {
                jdbc.update("UPDATE law_amendment SET old_new_raw = ?::jsonb WHERE mst = ?", raw, mst);
            }
        }
    }
}
