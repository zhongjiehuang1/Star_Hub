package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static com.starhub.ModelGateway.JSON;

/** The account row serializes settings, publication and dispatch permission across JVMs. */
final class IntelligenceStore {
    static final long MINUTE = 60_000, HOUR = 60 * MINUTE;
    private static final long LEASE = 10 * MINUTE;
    private static final int MAX_PAYLOAD = 96 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String OWNER = "user_id=? AND owner_generation=?";
    private final IntelligenceConfig config;

    IntelligenceStore(IntelligenceConfig config) {
        this.config = config;
    }

    record State(ObjectNode control, ObjectNode snapshot) {
        boolean enabled() { return control.path("enabled").asBoolean(); }
        long number(String key) { return control.path(key).asLong(); }
        String value(String key) { return control.path(key).asText(); }
    }
    record Cycle(String id, long revision) {}
    record Event(String id, String kind, long time, ObjectNode payload) {}
    record Mail(String id, String action, String state, long created, long due, long firstAttempt,
                int attempts, String provider, String credentialHash, ObjectNode payload) {}
    record Reservation(String day, long amount) {}

    ObjectNode initial(boolean enabled) {
        return JSON.createObjectNode().put("enabled", enabled).put("memory", false)
                .put("chats", false).put("files", false).put("email", true).put("revision", 1)
                .put("privacyRevision", 1).put("status", enabled ? "WAITING" : "PAUSED");
    }

    boolean ownerActive(Connection c) throws SQLException {
        try (PreparedStatement q = c.prepareStatement("SELECT 1 FROM users WHERE id=? AND generation=? AND active=1")) {
            bind(q, 1);
            try (ResultSet r = q.executeQuery()) { return r.next(); }
        }
    }

    State load(Connection c, boolean lock, boolean snapshot) throws Exception {
        String columns = snapshot ? "control,snapshot" : "control";
        try (PreparedStatement q = c.prepareStatement("SELECT " + columns + " FROM starhub_intelligence_state WHERE "
                + OWNER + (lock ? " FOR UPDATE" : ""))) {
            bind(q, 1);
            try (ResultSet r = q.executeQuery()) {
                if (!r.next()) return null;
                ObjectNode control = object(r.getString(1));
                return new State(control, snapshot ? open(r.getBytes(2), "snapshot") : JSON.createObjectNode());
            }
        }
    }

    State create(Connection c, boolean enabled, String keyId) throws Exception {
        State state = new State(initial(enabled).put("keyId", Objects.toString(keyId, "")), JSON.createObjectNode());
        try (PreparedStatement q = c.prepareStatement(
                "INSERT INTO starhub_intelligence_state(user_id,owner_generation,control) VALUES(?,?,?)")) {
            bind(q, 1);
            q.setString(3, state.control.toString());
            q.executeUpdate();
        }
        return state;
    }

    void save(Connection c, State state, boolean snapshot) throws Exception {
        String fields = snapshot ? "control=?,snapshot=?" : "control=?";
        try (PreparedStatement q = c.prepareStatement("UPDATE starhub_intelligence_state SET " + fields + " WHERE " + OWNER)) {
            q.setString(1, state.control.toString());
            if (snapshot) q.setBytes(2, seal(state.snapshot, "snapshot"));
            bind(q, snapshot ? 3 : 2);
            if (q.executeUpdate() != 1) throw new SQLException("Intelligence state missing");
        }
    }

    String selectedKey(Connection c) throws SQLException {
        try (PreparedStatement q = c.prepareStatement("SELECT id FROM api_keys WHERE user_id=? AND provider='openai' AND selected=1")) {
            q.setLong(1, config.user);
            try (ResultSet r = q.executeQuery()) {
                if (!r.next()) return "";
                String id = r.getString(1);
                if (r.next() || !IntelligenceConfig.isId(id)) throw new SQLException("Invalid Intelligence model key selection");
                return id;
            }
        }
    }

    String secret(Connection c, State state) throws Exception {
        try (PreparedStatement q = c.prepareStatement(
                "SELECT nonce,ciphertext FROM api_keys WHERE user_id=? AND id=? AND provider='openai' AND selected=1")) {
            q.setLong(1, config.user);
            q.setString(2, state.value("keyId"));
            try (ResultSet r = q.executeQuery()) {
                if (!r.next()) throw new Unready("MODEL_KEY");
                return Keyring.decrypt(config.user, "openai", state.value("keyId"), r.getBytes(1), r.getBytes(2), config.vault);
            }
        }
    }

    Cycle claim(Connection c, State state, long now) throws Exception {
        if (state.number("leaseUntil") > now || !reviewDue(state, now)) return null;
        String id = IntelligenceConfig.id();
        state.control.put("lease", id).put("leaseUntil", now + LEASE).put("status", "RUNNING")
                .put("started", now).put("heartbeat", now).put("due", now)
                .put("attemptedVersion", IntelligenceDecision.VERSION);
        save(c, state, false);
        return new Cycle(id, state.number("revision"));
    }

    static boolean reviewDue(State state, long now) {
        return state.number("due") <= now || state.number("lastReview") > 0
                && state.number("attemptedVersion") < IntelligenceDecision.VERSION;
    }

    boolean valid(State state, Cycle cycle, long now) {
        return state != null && state.enabled() && cycle.id.equals(state.value("lease"))
                && cycle.revision == state.number("revision") && state.number("leaseUntil") > now;
    }

    State requireCycle(Connection c, Cycle cycle, long now) throws Exception {
        State state = load(c, true, false);
        if (!ownerActive(c) || !valid(state, cycle, now)) throw new Unready("STALE");
        return state;
    }

    Reservation reserve(Connection c, Cycle cycle, long now, long amount, boolean search) throws Exception {
        State state = requireCycle(c, cycle, now);
        String day = day(now);
        if (!day.equals(state.value("budgetDay"))) {
            state.control.put("budgetDay", day).put("budgetUsed", 0).put("estimated", 0)
                    .put("calls", 0).put("searchCalls", 0);
        }
        if (!state.value("keyId").equals(selectedKey(c))) throw new Unready("MODEL_KEY");
        if (state.number("budgetUsed") > config.dailyBudget - amount || state.number("calls") >= 4
                || search && state.number("searchCalls") >= 1) throw new Unready("BUDGET");
        state.control.put("budgetUsed", state.number("budgetUsed") + amount)
                .put("calls", state.number("calls") + 1);
        if (search) state.control.put("searchCalls", state.number("searchCalls") + 1);
        save(c, state, false);
        return new Reservation(day, amount);
    }

    void settle(Connection c, Reservation reservation, long estimate) throws Exception {
        if (estimate < 0) return;
        State state = load(c, true, false);
        if (state == null || !reservation.day.equals(state.value("budgetDay"))) return;
        long used = state.number("budgetUsed");
        state.control.put("budgetUsed", Math.addExact(used - reservation.amount, estimate))
                .put("estimated", Math.addExact(state.number("estimated"), estimate));
        save(c, state, false);
    }

    boolean dispatchCurrent(Connection c, Mail mail) throws SQLException {
        try (PreparedStatement q = c.prepareStatement("SELECT 1 FROM starhub_intelligence_mail WHERE "
                + OWNER + " AND message_id=? AND state='DISPATCHING' AND attempts=? AND credential_hash=?")) {
            int i = bind(q, 1);
            q.setString(i++, mail.id);
            q.setInt(i++, mail.attempts);
            q.setString(i, mail.credentialHash);
            try (ResultSet r = q.executeQuery()) { return r.next(); }
        }
    }

    static String day(long millis) {
        return Instant.ofEpochMilli(millis).atZone(IntelligenceConfig.ZONE).toLocalDate().toString();
    }

    void event(Connection c, Event event) throws Exception {
        try (PreparedStatement q = c.prepareStatement(
                "INSERT INTO starhub_intelligence_events(user_id,owner_generation,event_id,kind,happened_ms,payload) VALUES(?,?,?,?,?,?)")) {
            int i = bind(q, 1);
            q.setString(i++, event.id);
            q.setString(i++, event.kind);
            q.setLong(i++, event.time);
            q.setBytes(i, seal(event.payload, event.kind + ":" + event.id));
            q.executeUpdate();
        }
    }

    Event event(Connection c, String id) throws Exception {
        try (PreparedStatement q = c.prepareStatement("SELECT event_id,kind,happened_ms,payload FROM starhub_intelligence_events WHERE "
                + OWNER + " AND event_id=?")) {
            q.setString(bind(q, 1), id);
            try (ResultSet r = q.executeQuery()) { return r.next() ? event(r) : null; }
        }
    }

    List<Event> history(Connection c, String kind, int limit) throws Exception {
        List<Event> events = new ArrayList<>();
        try (PreparedStatement q = c.prepareStatement("SELECT event_id,kind,happened_ms,payload FROM starhub_intelligence_events WHERE "
                + OWNER + " AND kind=? ORDER BY happened_ms DESC,event_id DESC LIMIT ?")) {
            int i = bind(q, 1);
            q.setString(i++, kind);
            q.setInt(i, limit);
            try (ResultSet r = q.executeQuery()) { while (r.next()) events.add(event(r)); }
        }
        return events;
    }

    private Event event(ResultSet r) throws Exception {
        String id = r.getString(1), kind = r.getString(2);
        return new Event(id, kind, r.getLong(3), open(r.getBytes(4), kind + ":" + id));
    }

    void queue(Connection c, String action, long now, ObjectNode payload) throws Exception {
        try (PreparedStatement q = c.prepareStatement("SELECT 1 FROM starhub_intelligence_mail WHERE " + OWNER + " AND action_id=?")) {
            q.setString(bind(q, 1), action);
            try (ResultSet r = q.executeQuery()) { if (r.next()) return; }
        }
        try (PreparedStatement q = c.prepareStatement("INSERT INTO starhub_intelligence_mail"
                + "(user_id,owner_generation,message_id,action_id,state,created_ms,due_ms,payload) VALUES(?,?,?,?,'QUEUED',?,?,?)")) {
            String id = IntelligenceConfig.id();
            int i = bind(q, 1);
            q.setString(i++, id);
            q.setString(i++, action);
            q.setLong(i++, now);
            q.setLong(i++, now);
            q.setBytes(i, seal(payload, "mail:" + id));
            q.executeUpdate();
        }
    }

    boolean hasDueMail(Connection c, long now) throws SQLException {
        try (PreparedStatement q = c.prepareStatement("SELECT 1 FROM starhub_intelligence_mail WHERE " + OWNER
                + " AND state IN ('QUEUED','DISPATCHING','RETRY','ACCEPTED','DELAYED','VERIFYING') AND due_ms<=? LIMIT 1")) {
            q.setLong(bind(q, 1), now);
            try (ResultSet r = q.executeQuery()) { return r.next(); }
        }
    }

    Mail dueMail(Connection c, long now, boolean send) throws Exception {
        String states = send ? "('QUEUED','DISPATCHING','RETRY')" : "('ACCEPTED','DELAYED','VERIFYING')";
        try (PreparedStatement q = c.prepareStatement("SELECT message_id,action_id,state,created_ms,due_ms,first_attempt_ms,"
                + "attempts,provider_id,credential_hash,payload FROM starhub_intelligence_mail WHERE " + OWNER
                + " AND state IN " + states + " AND due_ms<=? ORDER BY due_ms,message_id LIMIT 1 FOR UPDATE")) {
            q.setLong(bind(q, 1), now);
            try (ResultSet r = q.executeQuery()) {
                if (!r.next()) return null;
                String id = r.getString(1);
                return new Mail(id, r.getString(2), r.getString(3), r.getLong(4), r.getLong(5), r.getLong(6),
                        r.getInt(7), r.getString(8), r.getString(9), open(r.getBytes(10), "mail:" + id));
            }
        }
    }

    void setMail(Connection c, Mail mail, String state, long due, long first, int attempts,
                 String provider, String credentialHash) throws SQLException {
        try (PreparedStatement q = c.prepareStatement("UPDATE starhub_intelligence_mail SET state=?,due_ms=?,first_attempt_ms=?,"
                + "attempts=?,provider_id=?,credential_hash=? WHERE " + OWNER + " AND message_id=? AND state=? AND attempts=?")) {
            q.setString(1, state);
            q.setLong(2, due);
            q.setLong(3, first);
            q.setInt(4, attempts);
            q.setString(5, provider);
            q.setString(6, credentialHash);
            int i = bind(q, 7);
            q.setString(i++, mail.id);
            q.setString(i++, mail.state);
            q.setInt(i, mail.attempts);
            if (q.executeUpdate() != 1) throw new Unready("STALE");
        }
    }

    void cancelPendingMail(Connection c) throws SQLException {
        try (PreparedStatement q = c.prepareStatement("UPDATE starhub_intelligence_mail SET state=CASE WHEN attempts=0 "
                + "THEN 'CANCELLED' ELSE 'UNKNOWN' END WHERE " + OWNER + " AND state IN ('QUEUED','RETRY','DISPATCHING')")) {
            bind(q, 1);
            q.executeUpdate();
        }
    }

    ArrayNode mailHistory(Connection c) throws SQLException {
        ArrayNode out = JSON.createArrayNode();
        try (PreparedStatement q = c.prepareStatement("SELECT message_id,state,created_ms,first_attempt_ms,attempts,provider_id"
                + " FROM starhub_intelligence_mail WHERE " + OWNER + " ORDER BY created_ms DESC,message_id DESC LIMIT 12")) {
            bind(q, 1);
            try (ResultSet r = q.executeQuery()) {
                while (r.next()) out.addObject().put("id", r.getString(1)).put("state", r.getString(2))
                        .put("created", r.getLong(3)).put("firstAttempt", r.getLong(4))
                        .put("attempts", r.getInt(5)).put("providerId", r.getString(6));
            }
        }
        return out;
    }

    int bind(PreparedStatement q, int first) throws SQLException {
        q.setLong(first++, config.user);
        q.setString(first++, config.generation);
        return first;
    }

    private byte[] seal(ObjectNode value, String purpose) throws Exception {
        byte[] plain = JSON.writeValueAsBytes(value);
        if (plain.length > MAX_PAYLOAD) throw new SQLException("Intelligence record exceeds its storage bound");
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        byte[] cipher = AccountServlet.crypt(true, plain, config.vault, nonce, aad(purpose));
        return ByteBuffer.allocate(nonce.length + cipher.length).put(nonce).put(cipher).array();
    }

    private ObjectNode open(byte[] bytes, String purpose) throws Exception {
        if (bytes == null) return JSON.createObjectNode();
        if (bytes.length < 28 || bytes.length > MAX_PAYLOAD + 28) throw new SQLException("Invalid Intelligence record size");
        byte[] plain = AccountServlet.crypt(false, Arrays.copyOfRange(bytes, 12, bytes.length), config.vault,
                Arrays.copyOf(bytes, 12), aad(purpose));
        return object(new String(plain, StandardCharsets.UTF_8));
    }

    private String aad(String purpose) {
        return "starhub-intelligence-v1:" + config.user + ":" + config.generation + ":" + purpose;
    }

    static ObjectNode object(String value) throws Exception {
        JsonNode parsed = JSON.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readTree(value);
        if (!(parsed instanceof ObjectNode object)) throw new SQLException("Invalid Intelligence record");
        return object;
    }

    static final class Unready extends RuntimeException {
        final String code;
        Unready(String code) { super(code); this.code = code; }
    }
}
