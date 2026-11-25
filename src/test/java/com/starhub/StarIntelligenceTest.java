package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

import static com.starhub.IntelligenceStore.HOUR;
import static com.starhub.IntelligenceStore.MINUTE;
import static com.starhub.ModelGateway.JSON;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StarIntelligenceTest {
    private static final String GENERATION = "1cebc4ee-3960-417a-919c-9f068273321a";
    private static final String KEY = "1cebc4ee-3960-417a-919c-9f068273321b";
    private final AtomicLong now = new AtomicLong(Instant.parse("2026-11-24T10:00:00Z").toEpochMilli());
    private final String url = "jdbc:h2:mem:" + IntelligenceConfig.id() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
    private IntelligenceConfig config;
    private IntelligenceStore store;
    private Fixture backend;
    private Properties settings;

    @BeforeEach void setup() throws Exception {
        settings = new Properties();
        var p = settings;
        p.putAll(Map.of("intelligence.owner.id", "1", "intelligence.owner.generation", GENERATION,
                "intelligence.worker", "true", "vault.key", Base64.getEncoder().encodeToString(new byte[32]),
                "intelligence.mail.to", "recipient@example.net", "intelligence.mail.from", "notify@example.net",
                "intelligence.mail.key", "re_test_intelligence"));
        config = IntelligenceConfig.read(p);
        store = new IntelligenceStore(config);
        backend = new Fixture();
        try (var c = DriverManager.getConnection(url); var sql = c.createStatement()) {
            sql.execute("CREATE TABLE users(id BIGINT PRIMARY KEY,generation CHAR(36),active BOOLEAN)");
            sql.execute("INSERT INTO users VALUES(1,'" + GENERATION + "',TRUE)");
            sql.execute("CREATE TABLE api_keys(id CHAR(36),user_id BIGINT,provider VARCHAR(10),selected BOOLEAN)");
            sql.execute("INSERT INTO api_keys VALUES('" + KEY + "',1,'openai',TRUE)");
            String migration = Files.readString(Path.of("deploy/migrations/020_star_intelligence.sql"));
            for (String ddl : migration.replace("control JSON", "control CLOB").split(";")) {
                if (!ddl.isBlank()) sql.execute(ddl);
            }
        }
    }

    @Test void serverCyclesWithoutBrowserAndKeepsActionAcrossRestartAndUnchangedEvidence() throws Exception {
        engine().tick();
        var first = state();
        String id = first.snapshot().path("action").path("id").asText();
        assertTrue(IntelligenceConfig.isId(id));
        assertEquals("READY", first.value("status"));
        assertEquals(1, backend.searches);
        assertEquals(1, backend.reviews);
        assertEquals(1, backend.sends);
        assertEquals("ACCEPTED", mail().path("state").asText());
        assertNull(backend.publicQuery.get("feedback"));
        assertNull(backend.publicQuery.get("memory"));

        now.addAndGet(16 * MINUTE);
        engine().tick();
        assertEquals("DELIVERED", mail().path("state").asText());
        assertEquals(1, backend.sends);
        assertTrue(state().number("heartbeat") > first.number("heartbeat"));

        now.addAndGet(6 * HOUR);
        engine().tick();
        assertEquals(1, backend.reviews);
        assertEquals(id, state().snapshot().path("action").path("id").asText());
        assertEquals(1, backend.searches);
    }

    @Test void feedbackIsIdempotentPrivateAndChangesTheNextDecision() throws Exception {
        engine().tick();
        String action = state().snapshot().path("action").path("id").asText();
        String id = IntelligenceConfig.id();
        now.addAndGet(MINUTE);
        ObjectNode submitted = JSON.createObjectNode().put("operationId", id).put("actionId", action)
                .put("text", "第一步已完成，得到 A 和 B 两个可重复的结果。");
        backend.write(c -> {
            IntelligenceServlet.feedback(c, store, store.load(c, true, true), submitted, now.get());
            IntelligenceServlet.feedback(c, store, store.load(c, true, true), submitted, now.get());
            assertEquals(1, store.history(c, "FEEDBACK", 10).size());
            var modified = submitted.deepCopy().put("text", "A different submission");
            assertEquals(409, assertThrows(StorageFailure.class, () ->
                    IntelligenceServlet.feedback(c, store, store.load(c, true, true), modified, now.get())).status);
            return null;
        });

        backend.outcomeId = id;
        engine().tick();
        var next = state();
        assertNotEquals(action, next.snapshot().path("action").path("id").asText());
        assertEquals("completed", next.snapshot().path("assessment").path("reportedOutcome").path("kind").asText());
        assertEquals(id, backend.privatePrompt.path("feedback").path(0).path("id").asText());
        assertEquals(1, backend.searches);
        assertEquals(2, backend.reviews);
        boolean plaintext = backend.read(c -> {
            try (var sql = c.prepareStatement("SELECT payload FROM starhub_intelligence_events WHERE event_id=?")) {
                sql.setString(1, id);
                try (var rows = sql.executeQuery()) {
                    rows.next();
                    return new String(rows.getBytes(1), StandardCharsets.UTF_8).contains(submitted.path("text").asText());
                }
            }
        });
        assertFalse(plaintext);
    }

    @Test void updatedDecisionRulesReevaluatePersistedEvidenceWithoutRepeatingSearch() throws Exception {
        engine().tick();
        var before = state();
        backend.write(c -> {
            var state = store.load(c, true, true);
            state.control().remove("attemptedVersion");
            ((ObjectNode) state.snapshot().path("assessment")).remove("decisionVersion");
            store.save(c, state, true);
            return null;
        });
        now.addAndGet(MINUTE);
        engine().tick();
        var after = state();
        assertEquals(2, backend.reviews);
        assertEquals(1, backend.searches);
        assertEquals(before.snapshot().path("action").path("id"), after.snapshot().path("action").path("id"));
        assertEquals(IntelligenceDecision.VERSION, after.number("attemptedVersion"));
        assertEquals(IntelligenceDecision.VERSION, after.snapshot().path("assessment").path("decisionVersion").asInt());
    }

    @Test void authenticatesOnlyTheConfiguredAccountAndGeneration() {
        HttpSession session = mock(HttpSession.class);
        ObjectNode body = JSON.createObjectNode().put("expectedEmail", "account@example.net");
        when(session.getAttribute("starhub.account.id")).thenReturn(2L);
        when(session.getAttribute("starhub.account.generation")).thenReturn(GENERATION);
        when(session.getAttribute("starhub.account.email")).thenReturn("account@example.net");
        assertEquals(401, assertThrows(StorageFailure.class, () -> IntelligenceServlet.authorize(config, null, body)).status);
        assertEquals(404, assertThrows(StorageFailure.class, () -> IntelligenceServlet.authorize(config, session, body)).status);
        when(session.getAttribute("starhub.account.id")).thenReturn(1L);
        when(session.getAttribute("starhub.account.generation")).thenReturn(IntelligenceConfig.id());
        assertEquals(404, assertThrows(StorageFailure.class, () -> IntelligenceServlet.authorize(config, session, body)).status);
        when(session.getAttribute("starhub.account.generation")).thenReturn(GENERATION);
        assertDoesNotThrow(() -> IntelligenceServlet.authorize(config, session, body));
        assertEquals(409, assertThrows(StorageFailure.class, () -> IntelligenceServlet.authorize(config, session,
                body.deepCopy().put("expectedEmail", "old@example.net"))).status);
    }

    @Test void budgetAndLeasesArePersistedAndUncertainSpendRemainsHeld() throws Exception {
        backend.write(c -> {
            var fresh = store.create(c, true, KEY);
            var ticket = store.claim(c, fresh, now.get());
            assertNull(store.claim(c, store.load(c, true, false), now.get()));
            var allocation = store.reserve(c, ticket, now.get(), IntelligenceModels.SEARCH_RESERVE, true);
            assertEquals("BUDGET", assertThrows(IntelligenceStore.Unready.class, () ->
                    store.reserve(c, ticket, now.get(), IntelligenceModels.SEARCH_RESERVE, true)).code);
            assertEquals("BUDGET", assertThrows(IntelligenceStore.Unready.class, () ->
                    store.reserve(c, ticket, now.get(), 300_000, false)).code);
            store.settle(c, allocation, -1);
            assertEquals(IntelligenceModels.SEARCH_RESERVE, store.load(c, false, false).number("budgetUsed"));
            store.settle(c, allocation, 17_000);
            assertEquals(17_000, store.load(c, false, false).number("budgetUsed"));
            now.addAndGet(11 * MINUTE);
            var replacement = store.claim(c, store.load(c, true, false), now.get());
            assertNotEquals(ticket, replacement);
            assertEquals("STALE", assertThrows(IntelligenceStore.Unready.class, () ->
                    store.requireCycle(c, ticket, now.get())).code);
            return null;
        });
    }

    @Test void mailSurvivesSubmissionUncertaintyUsingTheSameIdAndHonorsQuietHours() throws Exception {
        now.set(Instant.parse("2026-11-24T23:00:00Z").toEpochMilli());
        engine().tick();
        assertEquals("QUEUED", mail().path("state").asText());
        assertEquals(0, backend.sends);
        now.set(Instant.parse("2026-11-25T08:00:00Z").toEpochMilli());
        backend.failSend = true;
        engine().tick();
        assertEquals("RETRY", mail().path("state").asText());
        String operation = backend.sentId;
        now.addAndGet(6 * MINUTE);
        backend.failSend = false;
        engine().tick();
        assertEquals("ACCEPTED", mail().path("state").asText());
        assertEquals(operation, backend.sentId);
        assertEquals(2, backend.sends);
        assertTrue(backend.sentPayload.path("text").asText().contains("完成标准"));
        assertTrue(backend.sentPayload.path("html").asText().contains("&lt;script&gt;"));
        assertFalse(backend.sentPayload.path("html").asText().contains("<script>"));
    }

    @Test void officialSearchRequestIsBoundedAndUsageIsMetered() throws Exception {
        var model = new ModelGateway((uri, headers, bytes) -> {
            assertEquals("https://api.openai.com/v1/responses", uri.toString());
            JsonNode body = JSON.readTree(bytes);
            assertEquals(1, body.path("max_tool_calls").asInt());
            assertEquals(1800, body.path("max_output_tokens").asInt());
            assertEquals("required", body.path("tool_choice").asText());
            assertFalse(body.path("store").asBoolean(true));
            assertEquals(1, body.path("tools").size());
            return new ModelGateway.Reply(200, """
                    {"status":"completed","output":[{"type":"web_search_call","status":"completed"},
                    {"type":"message","content":[{"type":"output_text","text":"Recent news",
                    "annotations":[{"type":"url_citation","url":"https://www.gov.uk/guidance","title":"Guidance"}]}]}],
                    "usage":{"input_tokens":8000,"output_tokens":200}}
                    """.getBytes(StandardCharsets.UTF_8));
        });
        var request = JSON.createObjectNode().put("provider", "openai").put("model", IntelligenceModels.SOL)
                .put("apiKey", "fixture-key").put("effort", "low").put("webSearch", true);
        request.putArray("messages").addObject().put("role", "user").put("content", "Find referenced public information");
        var result = model.intelligence(request, null, 1800);
        assertEquals(28_000, IntelligenceModels.meter(result, IntelligenceModels.SOL, 1800));
        assertEquals(1, IntelligenceInputs.publicScan(result, now.get()).path("sources").size());
        assertThrows(IntelligenceStore.Unready.class, () ->
                IntelligenceInputs.publicScan(result.deepCopy().put("completedSearchCalls", 0), now.get()));
    }

    @Test void qqDispatchUncertaintyUsesInboxReceiptWithoutResending() throws Exception {
        configureQq();
        backend.failSend = true;
        engine().tick();
        assertEquals("VERIFYING", mail().path("state").asText());
        assertEquals(1, backend.sends);
        now.addAndGet(MINUTE);
        engine().tick();
        assertEquals("VERIFYING", mail().path("state").asText());
        assertEquals(1, backend.sends);
        backend.inboxConfirmed = true;
        now.addAndGet(MINUTE);
        engine().tick();
        assertEquals("RECEIVED", mail().path("state").asText());
        assertEquals(1, backend.sends);
    }

    @Test void resendBrandingKeepsPreviouslyQueuedPayloadsAndLaterRetriesStable() throws Exception {
        var legacyMail = config.mail();
        var legacy = IntelligenceMail.incident(config);
        var legacyBody = IntelligenceMail.resendBody(legacy, legacyMail);
        assertFalse(legacyBody.has("attachments"));

        settings.setProperty("intelligence.mail.fromName", "starhub_notify");
        var brandedMail = config.mail();
        assertEquals(legacyMail.transportId, brandedMail.transportId);
        assertEquals(legacyBody, IntelligenceMail.resendBody(legacy, brandedMail));

        var queued = IntelligenceMail.incident(config);
        var request = IntelligenceMail.resendBody(queued, brandedMail);
        assertEquals("starhub_notify <notify@example.net>", request.path("from").asText());
        assertEquals("recipient@example.net", request.path("to").path(0).asText());
        var image = request.path("attachments").path(0);
        assertTrue(request.path("html").asText().contains("cid:" + image.path("content_id").asText()));
        assertFalse(request.path("text").asText().contains("cid:"));
        var bytes = Base64.getDecoder().decode(image.path("content").asText());
        var png = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
        assertEquals(128, png.getWidth());
        assertEquals(128, png.getHeight());

        settings.setProperty("intelligence.mail.fromName", "Updated name");
        assertEquals(request, IntelligenceMail.resendBody(queued, config.mail()));
        settings.setProperty("intelligence.mail.fromName", "bad\r\nBcc: unwanted@example.net");
        assertNull(config.mail());
    }

    @Test void qqRecoversARecordedSubmissionAfterWorkerRestart() throws Exception {
        configureQq();
        now.set(Instant.parse("2026-11-24T23:00:00Z").toEpochMilli());
        engine().tick();
        now.set(Instant.parse("2026-11-25T08:00:00Z").toEpochMilli());
        backend.write(c -> {
            var message = store.dueMail(c, now.get(), true);
            store.setMail(c, message, "DISPATCHING", now.get(), now.get() - MINUTE, 1, null, config.mail().transportId);
            return null;
        });

        backend.inboxConfirmed = true;
        engine().tick();
        assertEquals(0, backend.sends);
        assertEquals("RECEIVED", mail().path("state").asText());
        assertEquals(1, mail().path("attempts").asInt());
    }

    @Test void qqMimeProvidesBothFormatsAndRequiresTheExactInboxIdentity() throws Exception {
        configureQq();
        now.set(Instant.parse("2026-11-24T23:00:00Z").toEpochMilli());
        engine().tick();
        var row = backend.write(c -> store.dueMail(c, now.get() + 10 * HOUR, true));
        var message = IntelligenceQqMail.message(IntelligenceQqMail.session("smtps"), row, config.mail());
        var content = (jakarta.mail.internet.MimeMultipart) message.getContent();
        assertTrue(content.getBodyPart(0).isMimeType("text/plain"));
        assertTrue(content.getBodyPart(1).isMimeType("text/html"));
        String id = message.getMessageID();
        message.saveChanges();
        assertEquals(id, message.getMessageID());
        assertTrue(IntelligenceQqMail.matches(message, row.id(), config.mail().to));
        assertFalse(IntelligenceQqMail.matches(message, IntelligenceConfig.id(), config.mail().to));
        message.setRecipient(jakarta.mail.Message.RecipientType.TO, new jakarta.mail.internet.InternetAddress("second@example.net"));
        assertFalse(IntelligenceQqMail.matches(message, row.id(), config.mail().to));
        settings.setProperty("intelligence.mail.from", "second@example.net");
        assertNull(IntelligenceConfig.read(settings).mail());
    }

    private void configureQq() {
        settings.setProperty("intelligence.mail.provider", "qq");
        settings.setProperty("intelligence.mail.to", "123456789@qq.com");
        settings.setProperty("intelligence.mail.from", "123456789@qq.com");
        settings.setProperty("intelligence.mail.key", "fixtureqqauthabc");
        config = IntelligenceConfig.read(settings);
        store = new IntelligenceStore(config);
        assertTrue(config.mail().qqMailbox);
    }

    @Test void externalContentStaysPlainAndUnknownEvidenceCannotAuthorizeAChange() throws Exception {
        String rendered = IntelligenceInputs.pageText("<main><p>Profile &amp; Research</p><script>alert(1)</script>"
                + "<p>test@public.example.org sk-secretfixturevalue</p></main>");
        assertTrue(rendered.contains("Profile & Research"));
        assertFalse(rendered.contains("alert(1)"));
        assertFalse(rendered.contains("test@public.example.org"));
        assertFalse(IntelligenceInputs.safeUrl("https://127.0.0.1/"));
        assertFalse(IntelligenceInputs.safeUrl("https://source.example.org@other.example.org/"));
        var review = IntelligenceDecision.evaluate(decision("", ""), JSON.createObjectNode(), now.get());
        assertEquals("defer", review.verdict());
        assertNull(review.action());
        assertEquals("none", review.email());
    }

    private IntelligenceWorker engine() { return new IntelligenceWorker(config, backend, now::get, true); }
    private IntelligenceStore.State state() throws Exception { return backend.read(c -> store.load(c, false, true)); }
    private JsonNode mail() throws Exception { return backend.read(c -> store.mailHistory(c).path(0)); }

    private static ObjectNode decision(String feedback, String source) {
        var result = JSON.createObjectNode().put("verdict", "change").put("reason", "出现了可验证的证据，建议做一次短周期验证。")
                .put("confidence", "high").put("nextReviewHours", 6).put("researchNeeded", false).put("email", "action")
                .put("learning", feedback.isBlank() ? "先建立可重复基准。" : "第一步已获实际结果，可进入外部复查。");
        var action = result.putObject("action")
                .put("title", feedback.isBlank() ? "对比两项适用的公开机会 <script>" : "复查已完成工作的独立证据")
                .put("why", "这项比较保留了未来选择，并能很快验证可行性。")
                .put("resources", "45 分钟，公开资料").put("done", feedback.isBlank() ? "记录两个可复查指标" : "获得一项独立复核结论")
                .put("feedback", "报告结果和剩余障碍").put("deadline", "");
        action.putArray("steps").add("分别记录来源与验证结果。");
        action.putArray("sources").add(source);
        result.putArray("observations").addObject().put("scope", "world").put("kind", "fact")
                .put("text", "有权威资料可核查。").putArray("sources").add(source);
        var candidates = result.putArray("candidates");
        candidates.addObject().put("path", "外部验证").put("value", "能检验机会").put("tradeoff", "需要 45 分钟");
        candidates.addObject().put("path", "内部整理").put("value", "积累可复用成果").put("tradeoff", "无法验证外部需求");
        result.putObject("outcome").put("feedbackId", feedback).put("kind", feedback.isBlank() ? "none" : "completed");
        return result;
    }

    private final class Fixture implements IntelligenceWorker.Backend {
        int reviews, searches, sends;
        ObjectNode publicQuery, privatePrompt, sentPayload;
        String outcomeId = "", sentId;
        boolean failSend, inboxConfirmed;

        public <T> T read(AtomicDatabase.Work<T> work) throws Exception {
            try (var c = DriverManager.getConnection(url)) { return work.run(c); }
        }
        public <T> T write(AtomicDatabase.Work<T> work) throws Exception {
            try (var c = DriverManager.getConnection(url)) {
                c.setAutoCommit(false);
                try { T result = work.run(c); c.commit(); return result; }
                catch (Exception error) { c.rollback(); throw error; }
            }
        }
        public ArrayNode portfolio(long at) {
            var pages = JSON.createArrayNode();
            pages.addObject().put("id", "P1").put("title", "Profile").put("url", "https://www.zhongjiehuang.com/")
                    .put("text", "Public project and qualifications").put("at", at);
            return pages;
        }
        public ObjectNode search(IntelligenceStore.Cycle cycle, ObjectNode input) {
            searches++;
            publicQuery = input.deepCopy();
            var found = JSON.createObjectNode().put("text", "Verified news").put("completedSearchCalls", 1);
            found.putObject("details").put("webSearchUsed", true).putArray("webCitations").addObject()
                    .put("url", "https://www.gov.uk/guidance").put("title", "Guidance");
            return found;
        }
        public ObjectNode decide(IntelligenceStore.Cycle cycle, ObjectNode input, String model, String effort) {
            reviews++;
            privatePrompt = input.deepCopy();
            return JSON.createObjectNode().put("text", decision(outcomeId, "W1").toString()).put("estimatedMicros", 1);
        }
        public AtomicFileStorage.BinaryFile file(String id) { throw new AssertionError("No files authorized"); }
        public IntelligenceMail.Result send(IntelligenceStore.Mail mail, IntelligenceConfig.Mail transport) throws Exception {
            sends++;
            sentId = mail.id();
            sentPayload = mail.payload();
            if (failSend) throw new IOException("Synthetic lost submission");
            return new IntelligenceMail.Result("ACCEPTED", "9e5e4eed-b940-4c50-a540-32354f0344de");
        }
        public String delivery(IntelligenceStore.Mail mail, IntelligenceConfig.Mail transport) {
            return transport.qqMailbox ? inboxConfirmed ? "RECEIVED" : mail.state() : "DELIVERED";
        }
        public void stillAuthorized(IntelligenceConfig.Mail transport) {}
    }
}
