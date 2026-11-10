package com.envpermit.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.envpermit.core.company.Company;
import com.envpermit.core.company.CompanyRepository;
import com.envpermit.core.user.User;
import com.envpermit.core.user.UserRepository;
import com.envpermit.notification.NotificationJob;
import com.envpermit.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/**
 * ④ version-diff/02: approving OBLIGATION_REVISION suggestions. v1 = 3 approved obligations with deadlines
 * (3-2 quarterly, 5, P2-1); v2 diff = CHANGED 3-2 (→ monthly), UNCHANGED 5, REMOVED P2-1, ADDED 7.
 * kim = creator (self + bulk approve), lee = plain member.
 */
class RevisionApplyApiTest extends IntegrationTest {

    static final String STALE = "비교 후 의무사항이 수정됨";

    @Autowired UserRepository users;
    @Autowired CompanyRepository companies;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper mapper;
    @Autowired NotificationJob job;

    String kim;
    String lee;
    long leeId;
    long kimId;
    long projectId;
    long permitId;
    long v1;
    long v2;
    long runId;
    Map<String, Long> v1Ids = new HashMap<>(); // citation_key → obligation id
    Map<String, JsonNode> byType = new HashMap<>(); // change_type → suggestion

    @BeforeEach
    void seed() throws Exception {
        truncateAll();
        long companyId = companies.save(new Company("A환경")).getId();
        users.save(new User("master@a.com", encoder.encode("pw"), "마스터", User.Role.ADMIN, companyId));
        kimId = users.save(new User("kim@a.com", encoder.encode("pw"), "김에이", User.Role.USER, companyId)).getId();
        leeId = users.save(new User("lee@a.com", encoder.encode("pw"), "이에이", User.Role.USER, companyId)).getId();
        call(login("master@a.com"), json(put("/api/company/ai-extraction"), "{\"enabled\":true}")).andExpect(status().isOk());
        kim = login("kim@a.com");
        lee = login("lee@a.com");
        projectId = id(call(kim, json(post("/api/projects"), """
                {"name":"광명 자원회수시설","facilityType":"INCINERATION","location":"광명"}""")));
        call(kim, json(post("/api/projects/" + projectId + "/members"), "{\"userId\":" + leeId + "}"))
                .andExpect(status().isCreated());
        permitId = id(call(kim, json(post("/api/projects/" + projectId + "/permits"), """
                {"name":"환경영향평가 협의","category":"ENVIRONMENT","status":"COMPLETED"}""")));
        v1 = upload("v1.pdf", null);

        llm.answer("obligations-ok.json");
        long v1Run = runId(call(kim, post(base(v1) + "/extractions")).andExpect(status().isAccepted()));
        Map<String, String> due = Map.of("3-2", "2026-03-31", "5", "2026-06-30");
        for (JsonNode s : suggestions(v1Run)) {
            String key = s.at("/payload/citation_key").asText();
            String date = due.getOrDefault(key, "2026-02-28");
            long live = applied(accept(kim, s, "\"firstDueDate\":\"" + date + "\"").andExpect(status().isOk()));
            v1Ids.put(key, live);
        }

        v2 = upload("v2.pdf", v1);
        llm.answer("revision-v2.json");
        runId = runId(call(kim, post(base(v2) + "/revisions")).andExpect(status().isAccepted()));
        reload();
    }

    // --- per judgement ------------------------------------------------------------------------------------------

    @Test
    void changed_updates_the_same_row_logs_before_and_needs_the_next_due_date_when_frequency_changes() throws Exception {
        JsonNode changed = byType.get("CHANGED");
        accept(kim, changed, null).andExpect(status().isUnprocessableEntity())
                .andExpect(status().reason("다음 기한일 필요"));

        long live = applied(accept(kim, changed, "\"firstDueDate\":\"2026-02-15\"").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED")));

        assertThat(live).isEqualTo(v1Ids.get("3-2"));
        Map<String, Object> row = obligation(live);
        assertThat(row).containsEntry("frequency", "MONTHLY").containsEntry("source_document_id", v2)
                .containsEntry("status", "ACTIVE").containsEntry("next_due_date", java.sql.Date.valueOf("2026-02-15"));
        Map<String, Object> log = jdbc.queryForMap(
                "SELECT change_type, before ->> 'frequency' AS f, (before ->> 'source_document_id')::bigint AS doc, "
                        + "before ->> 'next_due_date' AS due, by FROM permit_obligation_revision_log WHERE obligation_id = ?", live);
        assertThat(log).containsEntry("change_type", "CHANGED").containsEntry("f", "QUARTERLY")
                .containsEntry("doc", v1).containsEntry("due", "2026-03-31");
        assertThat(deadline(live)).isEqualTo("2026-02-15");
    }

    @Test
    void removed_needs_the_absence_check_then_closes_keeps_the_row_and_stops_alerts() throws Exception {
        JsonNode removed = byType.get("REMOVED");
        long id = v1Ids.get("P2-1");
        accept(kim, removed, null).andExpect(status().isUnprocessableEntity())
                .andExpect(status().reason("v2 원문 확인 체크 필요"));

        accept(kim, removed, "\"confirmAbsent\":true").andExpect(status().isOk());

        assertThat(obligation(id)).containsEntry("status", "CLOSED").containsEntry("closed_reason", "REVISION_REMOVED")
                .containsEntry("source_document_id", v1);
        assertThat(deadline(id)).isNull();
        call(kim, get("/api/suggestions/" + removed.get("id").asLong() + "/events"))
                .andExpect(jsonPath("$[1].type").value("ACCEPTED"))
                .andExpect(jsonPath("$[1].data.confirm_absent").value(true));
    }

    @Test
    void unchanged_moves_text_and_source_but_keeps_the_deadline_and_added_creates_an_obligation() throws Exception {
        accept(kim, byType.get("UNCHANGED"), null).andExpect(status().isOk());
        long live = applied(accept(kim, byType.get("ADDED"), "\"firstDueDate\":\"2026-07-31\"").andExpect(status().isOk()));

        long five = v1Ids.get("5");
        assertThat(obligation(five)).containsEntry("source_document_id", v2)
                .containsEntry("next_due_date", java.sql.Date.valueOf("2026-06-30"));
        assertThat(deadline(five)).isEqualTo("2026-06-30");
        assertThat(obligation(live)).containsEntry("source_document_id", v2).containsEntry("citation_key", "7");
        assertThat(deadline(live)).isEqualTo("2026-07-31");
        assertThat(jdbc.queryForList("SELECT change_type FROM permit_obligation_revision_log", String.class))
                .containsExactly("UNCHANGED");
    }

    @Test
    void unverifiable_new_text_needs_its_page_opened_except_unchanged() throws Exception {
        jdbc.update("UPDATE ai_suggestion SET payload = jsonb_set(payload, '{new,quote_check}', '\"UNVERIFIABLE\"') "
                + "WHERE kind = 'OBLIGATION_REVISION' AND payload ->> 'change_type' IN ('ADDED', 'UNCHANGED')");
        JsonNode added = byType.get("ADDED");

        accept(kim, byType.get("UNCHANGED"), null).andExpect(status().isOk());
        accept(kim, added, null).andExpect(status().isUnprocessableEntity());
        call(kim, json(post("/api/suggestions/" + added.get("id").asLong() + "/page-views"), "{\"pageNo\":2}"))
                .andExpect(status().isNoContent());
        accept(kim, added, null).andExpect(status().isOk());
    }

    // --- alert integrity --------------------------------------------------------------------------------------------

    @Test
    void after_the_revision_each_obligation_has_exactly_one_alert_and_only_the_removed_one_is_gone() throws Exception {
        Map<Long, String> before = deadlines();
        assertThat(before).hasSize(3);

        accept(kim, byType.get("CHANGED"), "\"firstDueDate\":\"2026-02-15\"").andExpect(status().isOk());
        accept(kim, byType.get("REMOVED"), "\"confirmAbsent\":true").andExpect(status().isOk());
        accept(kim, byType.get("UNCHANGED"), null).andExpect(status().isOk());
        long added = applied(accept(kim, byType.get("ADDED"), "\"firstDueDate\":\"2026-07-31\"").andExpect(status().isOk()));

        assertThat(jdbc.queryForList("SELECT target_id FROM deadline_event WHERE kind = 'OBLIGATION' "
                + "GROUP BY target_id HAVING count(*) > 1", Long.class)).isEmpty();
        assertThat(deadlines()).containsOnly(
                Map.entry(v1Ids.get("3-2"), "2026-02-15"),
                Map.entry(v1Ids.get("5"), "2026-06-30"),
                Map.entry(added, "2026-07-31"));
    }

    @Test
    void pending_or_rejected_revisions_change_nothing_live() throws Exception {
        Map<Long, String> before = deadlines();
        String live = jdbc.queryForList("SELECT * FROM permit_obligation ORDER BY id").toString();

        JsonNode removed = byType.get("REMOVED");
        call(kim, json(post("/api/suggestions/" + removed.get("id").asLong() + "/reject"),
                "{\"reason\":\"v2에도 있음\",\"version\":" + removed.get("version").asLong() + "}")).andExpect(status().isOk());

        assertThat(deadlines()).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM permit_obligation ORDER BY id").toString()).isEqualTo(live);
        assertThat(obligation(v1Ids.get("P2-1"))).containsEntry("status", "ACTIVE").containsEntry("source_document_id", v1);
    }

    // --- version mismatch → rediff ------------------------------------------------------------------------------

    @Test
    void obligation_edited_after_the_diff_blocks_approval_until_rediff() throws Exception {
        long id = v1Ids.get("3-2");
        call(kim, json(post("/api/obligations/" + id + "/complete"), "{}")).andExpect(status().isOk());
        JsonNode changed = byType.get("CHANGED");

        accept(kim, changed, "\"firstDueDate\":\"2026-02-15\"").andExpect(status().isConflict())
                .andExpect(status().reason(STALE));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM permit_obligation_revision_log", Long.class)).isZero();

        long sid = changed.get("id").asLong();
        call(kim, json(post("/api/suggestions/" + sid + "/rediff"), "{\"version\":" + changed.get("version").asLong() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload.change_type").value("CHANGED"))
                .andExpect(jsonPath("$.payload.prev_obligation_version").value(((Number) obligation(id).get("version")).intValue()));
        reload();
        accept(kim, byType.get("CHANGED"), "\"firstDueDate\":\"2026-02-15\"").andExpect(status().isOk());
        call(kim, get("/api/suggestions/" + sid + "/events")).andExpect(jsonPath("$[1].type").value("REDIFFED"));
    }

    @Test
    void rediff_turns_into_added_when_the_partner_was_removed_by_a_revision_meanwhile() throws Exception {
        jdbc.update("UPDATE permit_obligation SET status = 'CLOSED', closed_reason = 'REVISION_REMOVED', version = version + 1 "
                + "WHERE id = ?", v1Ids.get("3-2"));
        JsonNode changed = byType.get("CHANGED");

        call(kim, json(post("/api/suggestions/" + changed.get("id").asLong() + "/rediff"),
                "{\"version\":" + changed.get("version").asLong() + "}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.payload.change_type").value("ADDED"))
                .andExpect(jsonPath("$.payload.prev_obligation_id").doesNotExist());
    }

    // --- bulk --------------------------------------------------------------------------------------------------

    @Test
    void bulk_approval_skips_removed_and_changed_needing_a_date() throws Exception {
        String ids = byType.values().stream().map(s -> s.get("id").asText()).reduce((a, b) -> a + "," + b).orElseThrow();

        JsonNode result = mapper.readTree(call(kim, json(post("/api/suggestions/bulk-accept"), "{\"ids\":[" + ids + "]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        Map<String, String> outcome = new HashMap<>();
        for (JsonNode r : result) {
            String type = byType.entrySet().stream().filter(e -> e.getValue().get("id").asLong() == r.get("id").asLong())
                    .findFirst().orElseThrow().getKey();
            outcome.put(type, r.get("ok").asBoolean() ? "ok" : r.get("reason").asText());
        }
        assertThat(outcome).containsOnly(Map.entry("UNCHANGED", "ok"), Map.entry("ADDED", "ok"),
                Map.entry("REMOVED", "개별 확인 필요"), Map.entry("CHANGED", "다음 기한일 필요"));
        assertThat(obligation(v1Ids.get("P2-1"))).containsEntry("status", "ACTIVE");
    }

    // --- pair / unpair ------------------------------------------------------------------------------------------

    @Test
    void plain_member_splits_and_merges_pairs_originals_close_for_good_and_replacements_need_approval() throws Exception {
        JsonNode changed = byType.get("CHANGED");
        long cid = changed.get("id").asLong();

        call(lee, json(post("/api/suggestions/" + cid + "/unpair"), "{\"version\":" + changed.get("version").asLong() + "}"))
                .andExpect(status().isOk());

        JsonNode original = suggestion(cid);
        assertThat(original.get("status").asText()).isEqualTo("REJECTED");
        assertThat(original.get("rejectReason").asText()).isEqualTo("매칭 분리");
        assertThat(original.get("reviewedBy").asLong()).isEqualTo(leeId);
        call(kim, json(post("/api/suggestions/" + cid + "/reopen"), "{\"version\":" + original.get("version").asLong() + "}"))
                .andExpect(status().isConflict());
        call(kim, get("/api/suggestions/" + cid + "/events")).andExpect(jsonPath("$[2].type").value("UNPAIRED"));

        JsonNode pending = pendingOf(original);
        assertThat(pending).hasSize(5); // ADDED 7, REMOVED P2-1, UNCHANGED 5 + the split ADDED/REMOVED of 3-2
        JsonNode splitAdded = find(pending, "ADDED", "3-2");
        JsonNode splitRemoved = find(pending, "REMOVED", null);
        assertThat(splitRemoved).isNotNull();

        call(lee, json(post("/api/suggestions/pair"), """
                {"addedId":%d,"removedId":%d,"addedVersion":%d,"removedVersion":%d}""".formatted(
                splitAdded.get("id").asLong(), splitRemoved.get("id").asLong(),
                splitAdded.get("version").asLong(), splitRemoved.get("version").asLong())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload.change_type").value("CHANGED"))
                .andExpect(jsonPath("$.payload.match.method").value("MANUAL"))
                .andExpect(jsonPath("$.status").value("PENDING"));
        assertThat(suggestion(splitAdded.get("id").asLong()).get("rejectReason").asText()).isEqualTo("매칭 결합");
        assertThat(suggestion(splitRemoved.get("id").asLong()).get("rejectReason").asText()).isEqualTo("매칭 결합");

        reload();
        JsonNode merged = byType.get("CHANGED");
        accept(lee, merged, "\"firstDueDate\":\"2026-02-15\"").andExpect(status().isForbidden());
        assertThat(applied(accept(kim, merged, "\"firstDueDate\":\"2026-02-15\"").andExpect(status().isOk())))
                .isEqualTo(v1Ids.get("3-2"));
    }

    @Test
    void pairing_is_only_for_pending_rows_of_the_right_kind() throws Exception {
        JsonNode added = byType.get("ADDED");
        call(kim, json(post("/api/suggestions/" + added.get("id").asLong() + "/unpair"),
                "{\"version\":" + added.get("version").asLong() + "}")).andExpect(status().isUnprocessableEntity());
        JsonNode unchanged = byType.get("UNCHANGED");
        accept(kim, unchanged, null).andExpect(status().isOk());
        call(kim, json(post("/api/suggestions/" + unchanged.get("id").asLong() + "/unpair"),
                "{\"version\":" + suggestion(unchanged.get("id").asLong()).get("version").asLong() + "}"))
                .andExpect(status().isConflict());
    }

    // --- audit ---------------------------------------------------------------------------------------------------

    @Test
    void revision_log_is_append_only() throws Exception {
        accept(kim, byType.get("UNCHANGED"), null).andExpect(status().isOk());

        assertThatThrownBy(() -> jdbc.update("UPDATE permit_obligation_revision_log SET before = '{}'"))
                .rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM permit_obligation_revision_log"))
                .rootCause().hasMessageContaining("append-only");
    }

    // --- site implementation (version-diff/03) -------------------------------------------------------------------

    @Test
    void approving_changed_or_added_opens_a_site_row_for_the_approver_and_nothing_else_does() throws Exception {
        accept(kim, byType.get("CHANGED"), "\"firstDueDate\":\"2026-02-15\"").andExpect(status().isOk());
        accept(kim, byType.get("REMOVED"), "\"confirmAbsent\":true").andExpect(status().isOk());
        accept(kim, byType.get("UNCHANGED"), null).andExpect(status().isOk());
        long added = applied(accept(kim, byType.get("ADDED"), "\"firstDueDate\":\"2026-07-31\"").andExpect(status().isOk()));

        JsonNode rows = implementations("open");
        assertThat(rows).hasSize(2);
        assertThat(rows.findValuesAsText("obligationId")).containsExactlyInAnyOrder(
                String.valueOf(v1Ids.get("3-2")), String.valueOf(added));
        assertThat(rows.findValuesAsText("assigneeId")).containsOnly(String.valueOf(kimId));
        assertThat(rows.findValuesAsText("changeType")).containsExactlyInAnyOrder("CHANGED", "ADDED");
        assertThat(rows.get(0).get("targetDate").isNull()).isTrue();
        assertThat(implementations("done")).isEmpty();
        call(lee, get("/api/revision-implementations/count")).andExpect(jsonPath("$.count").value(2));
    }

    @Test
    void target_date_reminds_the_assignee_until_done_and_progress_counts_follow() throws Exception {
        long sid = byType.get("CHANGED").get("id").asLong();
        accept(kim, byType.get("CHANGED"), "\"firstDueDate\":\"2026-02-15\"").andExpect(status().isOk());
        accept(kim, byType.get("ADDED"), "\"firstDueDate\":\"2026-07-31\"").andExpect(status().isOk());

        call(kim, json(patch("/api/revision-implementations/" + sid), "{\"assigneeId\":999999,\"targetDate\":\"2026-02-20\"}"))
                .andExpect(status().isUnprocessableEntity());
        call(kim, json(patch("/api/revision-implementations/" + sid), """
                {"assigneeId":%d,"targetDate":"2026-02-20","note":"CO 50→30ppm 운영팀 설정 변경"}""".formatted(leeId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigneeId").value(leeId))
                .andExpect(jsonPath("$.note").value("CO 50→30ppm 운영팀 설정 변경"));
        assertThat(jdbc.queryForMap("SELECT due_date::text AS d, assignee_id FROM deadline_event "
                + "WHERE kind = 'SITE_IMPLEMENTATION' AND target_id = ?", sid))
                .containsEntry("d", "2026-02-20").containsEntry("assignee_id", leeId);

        job.createDueNotifications(); // D-46 → the D-60 reminder
        assertThat(siteNotifications(leeId)).isEqualTo(1);
        assertThat(siteNotifications(kimId)).isZero();

        call(lee, post("/api/revision-implementations/" + sid + "/complete")).andExpect(status().isOk())
                .andExpect(jsonPath("$.doneBy").value(leeId))
                .andExpect(jsonPath("$.doneAt").isNotEmpty());
        call(lee, post("/api/revision-implementations/" + sid + "/complete")).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deadline_event WHERE kind = 'SITE_IMPLEMENTATION'", Long.class))
                .isZero();

        clock.set(Instant.parse("2026-02-15T00:00:00Z")); // would be D-7 of the site target
        job.createDueNotifications();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification n JOIN deadline_event e ON e.id = n.deadline_event_id "
                + "WHERE n.user_id = ? AND e.kind <> 'OBLIGATION'", Long.class, leeId)).isZero();
        kim = login("kim@a.com"); // the old token expired with the clock jump

        assertThat(implementations("open")).hasSize(1);
        assertThat(implementations("done")).hasSize(1);
        assertThat(implementations(null)).hasSize(2);
        call(kim, get("/api/revision-implementations/count")).andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void outsiders_cannot_see_or_touch_site_rows() throws Exception {
        long sid = byType.get("ADDED").get("id").asLong();
        accept(kim, byType.get("ADDED"), "\"firstDueDate\":\"2026-07-31\"").andExpect(status().isOk());
        users.save(new User("park@a.com", encoder.encode("pw"), "박에이", User.Role.USER,
                users.findById(kimId).orElseThrow().getCompanyId()));
        String park = login("park@a.com");

        call(park, get("/api/projects/" + projectId + "/revision-implementations")).andExpect(status().isForbidden());
        call(park, post("/api/revision-implementations/" + sid + "/complete")).andExpect(status().isForbidden());
        call(park, get("/api/revision-implementations/count")).andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void permit_obligations_carry_their_revision_log() throws Exception {
        accept(kim, byType.get("CHANGED"), "\"firstDueDate\":\"2026-02-15\"").andExpect(status().isOk());
        long id = v1Ids.get("3-2");

        call(kim, get("/api/projects/" + projectId + "/permits/" + permitId + "/obligations"))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[?(@.id == %d)].sourceDocumentId".formatted(id)).value(org.hamcrest.Matchers.contains((int) v2)))
                .andExpect(jsonPath("$[?(@.id == %d)].citationKey".formatted(id)).value(org.hamcrest.Matchers.contains("3-2")));
        call(kim, get("/api/obligations/" + id + "/revision-log"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].changeType").value("CHANGED"))
                .andExpect(jsonPath("$[0].before.frequency").value("QUARTERLY"))
                .andExpect(jsonPath("$[0].byName").value("김에이"));
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    private JsonNode implementations(String status) throws Exception {
        return mapper.readTree(call(kim, get("/api/projects/" + projectId + "/revision-implementations"
                + (status == null ? "" : "?status=" + status))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private long siteNotifications(long userId) {
        return jdbc.queryForObject("SELECT count(*) FROM notification n JOIN deadline_event e ON e.id = n.deadline_event_id "
                + "WHERE e.kind = 'SITE_IMPLEMENTATION' AND n.user_id = ?", Long.class, userId);
    }

    /** Re-read this run's PENDING suggestions by change type. */
    private void reload() throws Exception {
        byType.clear();
        for (JsonNode s : suggestions(runId)) {
            if (s.get("status").asText().equals("PENDING")) {
                byType.put(s.at("/payload/change_type").asText(), s);
            }
        }
    }

    private JsonNode pendingOf(JsonNode original) throws Exception {
        return mapper.valueToTree(java.util.stream.StreamSupport.stream(suggestions(original.get("runId").asLong()).spliterator(), false)
                .filter(s -> s.get("status").asText().equals("PENDING")).toList());
    }

    private static JsonNode find(JsonNode list, String type, String newKey) {
        for (JsonNode s : list) {
            if (s.at("/payload/change_type").asText().equals(type)
                    && (newKey == null || newKey.equals(s.at("/payload/new/citation_key").asText()))
                    && (newKey != null || !s.at("/payload/prev/citation_key").asText().equals("P2-1"))) {
                return s;
            }
        }
        return null;
    }

    private ResultActions accept(String token, JsonNode s, String extra) throws Exception {
        String body = "{" + (extra == null ? "" : extra + ",") + "\"version\":" + s.get("version").asLong() + "}";
        return call(token, json(post("/api/suggestions/" + s.get("id").asLong() + "/accept"), body));
    }

    private Map<String, Object> obligation(long id) {
        return jdbc.queryForMap("SELECT * FROM permit_obligation WHERE id = ?", id);
    }

    private String deadline(long obligationId) {
        return jdbc.queryForList("SELECT due_date::text FROM deadline_event WHERE kind = 'OBLIGATION' AND target_id = ?",
                String.class, obligationId).stream().findFirst().orElse(null);
    }

    private Map<Long, String> deadlines() {
        Map<Long, String> m = new HashMap<>();
        jdbc.queryForList("SELECT target_id, due_date::text AS d FROM deadline_event WHERE kind = 'OBLIGATION'")
                .forEach(r -> m.put((Long) r.get("target_id"), (String) r.get("d")));
        return m;
    }

    private JsonNode suggestion(long id) throws Exception {
        for (JsonNode s : suggestions(null)) {
            if (s.get("id").asLong() == id) {
                return s;
            }
        }
        throw new AssertionError("no suggestion " + id);
    }

    private JsonNode suggestions(Long run) throws Exception {
        return mapper.readTree(call(kim, get("/api/projects/" + projectId + "/suggestions" + (run == null ? "" : "?runId=" + run)))
                .andReturn().getResponse().getContentAsString());
    }

    private String base(long documentId) {
        return "/api/projects/" + projectId + "/permit-items/" + permitId + "/documents/" + documentId;
    }

    private long upload(String name, Long supersedes) throws Exception {
        MockMultipartHttpServletRequestBuilder req = multipart("/api/projects/" + projectId + "/permits/" + permitId + "/documents");
        req.file(new MockMultipartFile("file", name, "application/pdf", ExtractionApiTest.PERMIT_PDF));
        if (supersedes != null) {
            req.param("supersedesDocumentId", supersedes.toString());
        }
        return id(call(kim, req).andExpect(status().isCreated()));
    }

    private ResultActions call(String token, MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.header("Authorization", "Bearer " + token));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder req, String body) {
        return req.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static long id(ResultActions r) throws Exception {
        return ((Number) JsonPath.read(r.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }

    private static long applied(ResultActions r) throws Exception {
        return ((Number) JsonPath.read(r.andReturn().getResponse().getContentAsString(), "$.appliedEntityId")).longValue();
    }

    private static long runId(ResultActions r) throws Exception {
        return ((Number) JsonPath.read(r.andReturn().getResponse().getContentAsString(), "$.runId")).longValue();
    }

    private String login(String email) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"pw\"}".formatted(email)))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }
}
