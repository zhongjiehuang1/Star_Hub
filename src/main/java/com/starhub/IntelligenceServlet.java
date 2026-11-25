package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import static com.starhub.ModelGateway.JSON;

@WebServlet("/api/intelligence")
public final class IntelligenceServlet extends HttpServlet {
    private static final Set<String> ACTIONS = Set.of("available", "status", "settings", "feedback", "restorePolicy");

    @Override protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            if (!"web".equals(request.getHeader("X-Starhub-Client")) || !DeploymentConfig.allowsOrigin(request.getHeader("Origin"))
                    || request.getContentType() == null || !request.getContentType().split(";", 2)[0].strip()
                    .toLowerCase(Locale.ROOT).equals("application/json")) {
                throw problem(403, "ORIGIN", "请求来源无效。");
            }
            byte[] bytes = request.getInputStream().readNBytes(8193);
            if (bytes.length > 8192) throw problem(413, "SIZE", "反馈超过长度限制。");
            ObjectNode input;
            try { input = IntelligenceStore.object(new String(bytes, StandardCharsets.UTF_8)); }
            catch (Exception invalid) { throw problem(400, "INPUT", "请求格式无效。"); }
            String action = input.path("action").asText();
            if (!ACTIONS.contains(action)) throw problem(400, "INPUT", "操作无效。");
            var config = IntelligenceConfig.read(IntelligenceConfig.deploymentProperties());
            HttpSession session = request.getSession(false);
            authorize(config, session, input);
            FileStorage files = IntelligenceRuntime.storage();
            var store = new IntelligenceStore(config);
            boolean mutation = !Set.of("available", "status").contains(action);

            ObjectNode result = IntelligenceRuntime.transaction(files, mutation, c -> {
                authorize(config, session, input);
                if (!AccountServlet.activeSession(session) || AccountServlet.idleEnabled(files.config)
                        && !AccountServlet.idleEntry(c, session)) throw problem(401, "SESSION", "请重新登录原账户。");
                if (action.equals("available")) return JSON.createObjectNode().put("available", true);
                if (mutation && !config.sameOwner(IntelligenceConfig.read(IntelligenceConfig.deploymentProperties()))) {
                    throw problem(404, "UNAVAILABLE", "此账户无法使用 Star Intelligence。");
                }
                long now = System.currentTimeMillis();
                var state = store.load(c, mutation, true);
                switch (action) {
                    case "settings" -> settings(c, store, state, input, now);
                    case "feedback" -> feedback(c, store, state, input, now);
                    case "restorePolicy" -> restore(c, store, state, input, now);
                    default -> { }
                }
                return status(c, store, config, now);
            });
            authorize(config, session, input);
            send(response, 200, result);
        } catch (StorageFailure failure) {
            send(response, failure.status, JSON.createObjectNode().put("code", failure.code).put("error", failure.getMessage()));
        } catch (IllegalStateException replaced) {
            send(response, 503, JSON.createObjectNode().put("code", "UNAVAILABLE").put("error", "Star Intelligence 暂时无法读取。"));
        } catch (Exception unavailable) {
            send(response, 503, JSON.createObjectNode().put("code", "UNAVAILABLE").put("error", "Star Intelligence 暂时无法读取。"));
        } finally {
            AtomicDatabase.clearConfirmation();
        }
    }

    static void authorize(IntelligenceConfig config, HttpSession session, JsonNode input) {
        if (session == null) throw problem(401, "SESSION", "请先登录。");
        try {
            if (config == null || !Objects.equals(session.getAttribute("starhub.account.id"), config.user)
                    || !Objects.equals(session.getAttribute("starhub.account.generation"), config.generation)) {
                throw problem(404, "UNAVAILABLE", "此账户无法使用 Star Intelligence。");
            }
            if (!Objects.equals(session.getAttribute("starhub.account.email"), input.path("expectedEmail").asText())) {
                throw problem(409, "ACCOUNT_CHANGED", "账户已更改，请刷新后重试。");
            }
        } catch (IllegalStateException expired) {
            throw problem(401, "SESSION", "请重新登录原账户。");
        }
    }

    private static void settings(Connection c, IntelligenceStore store, IntelligenceStore.State state,
                                 ObjectNode input, long now) throws Exception {
        for (String field : List.of("enabled", "memory", "chats", "files", "email")) {
            if (!input.path(field).isBoolean()) throw problem(400, "INPUT", "授权选项无效。");
        }
        expectRevision(state, input);
        boolean enabled = input.path("enabled").asBoolean();
        String keyId = enabled ? store.selectedKey(c) : "";
        if (enabled && keyId.isBlank()) throw problem(409, "MODEL_KEY", "请在账户设置中保存并选择 OpenAI API 密钥。");
        if (state == null) state = store.create(c, enabled, keyId);
        boolean narrower = false;
        for (String field : List.of("memory", "chats", "files")) {
            narrower |= !input.path(field).asBoolean() && state.control().path(field).asBoolean();
        }
        if (narrower) {
            state.control().put("privacyRevision", state.number("privacyRevision") + 1);
            state.snapshot().remove(List.of("assessment", "action", "usedMemory", "inputHash"));
        }
        if (!enabled || narrower || !input.path("email").asBoolean()) store.cancelPendingMail(c);
        for (String field : List.of("enabled", "memory", "chats", "files", "email")) {
            state.control().put(field, input.path(field).asBoolean());
        }
        state.control().put("keyId", keyId).put("status", enabled ? "WAITING" : "PAUSED");
        revise(state, now);
        store.save(c, state, narrower);
        store.event(c, new IntelligenceStore.Event(IntelligenceConfig.id(), "SETTINGS", now,
                JSON.createObjectNode().put("enabled", enabled).put("memory", input.path("memory").asBoolean())
                        .put("chats", input.path("chats").asBoolean()).put("files", input.path("files").asBoolean())
                        .put("email", input.path("email").asBoolean()).put("revision", state.number("revision"))));
    }

    static void feedback(Connection c, IntelligenceStore store, IntelligenceStore.State state,
                         ObjectNode input, long now) throws Exception {
        if (state == null) throw problem(409, "DISABLED", "请先启用 Star Intelligence。");
        String id = input.path("operationId").asText();
        String actionId = input.path("actionId").asText();
        String text = input.path("text").asText().strip();
        if (!IntelligenceConfig.isId(id) || !actionId.isEmpty() && !IntelligenceConfig.isId(actionId)
                || !input.path("text").isTextual() || text.isBlank() || text.length() > 2000) {
            throw problem(400, "INPUT", "请输入不超过 2,000 字的反馈。");
        }
        var previous = store.event(c, id);
        if (previous != null) {
            if ("FEEDBACK".equals(previous.kind()) && actionId.equals(previous.payload().path("actionId").asText())
                    && text.equals(previous.payload().path("text").asText())) return;
            throw problem(409, "OPERATION_CONFLICT", "这次提交的编号已用于其他反馈。");
        }
        if (!actionId.equals(state.snapshot().path("action").path("id").asText())) {
            throw problem(409, "ACTION_CHANGED", "当前行动已经更新。请重新查看后提交。");
        }
        if (state.number("lastFeedback") + 10_000 > now) throw problem(429, "FEEDBACK_RATE", "请稍后再提交下一条反馈。");
        var body = JSON.createObjectNode().put("actionId", actionId).put("text", text);
        store.event(c, new IntelligenceStore.Event(id, "FEEDBACK", now, body));
        state.control().put("lastFeedback", now).put("status", state.enabled() ? "WAITING" : "PAUSED");
        store.cancelPendingMail(c);
        revise(state, now);
        store.save(c, state, false);
    }

    private static void restore(Connection c, IntelligenceStore store, IntelligenceStore.State state,
                                ObjectNode input, long now) throws Exception {
        expectRevision(state, input);
        String id = input.path("policyId").asText();
        if (state == null || !IntelligenceConfig.isId(id)) throw problem(400, "INPUT", "策略版本无效。");
        var target = store.event(c, id);
        if (target == null || !"POLICY".equals(target.kind())
                || target.payload().path("privacyRevision").asLong() != state.number("privacyRevision")) {
            throw problem(409, "POLICY_SCOPE", "这个策略版本的来源授权已改变。");
        }
        String version = IntelligenceConfig.id();
        ObjectNode assessment = state.snapshot().withObject("/assessment");
        assessment.put("learning", target.payload().path("learning").asText()).put("policyVersion", version);
        if (target.payload().path("usedMemory").asBoolean()) state.snapshot().put("usedMemory", true);
        store.event(c, new IntelligenceStore.Event(version, "POLICY", now, target.payload().deepCopy().put("restoredFrom", id)));
        state.snapshot().remove("inputHash");
        revise(state, now);
        store.save(c, state, true);
    }

    private static void expectRevision(IntelligenceStore.State state, JsonNode input) {
        long current = state == null ? 0 : state.number("revision");
        JsonNode expected = input.path("revision");
        if (!expected.isIntegralNumber() || !expected.canConvertToLong() || expected.asLong() != current) {
            throw problem(409, "SETTINGS_CHANGED", "设置已更新，请重新加载后再保存。");
        }
    }

    private static void revise(IntelligenceStore.State state, long now) {
        state.control().put("revision", state.number("revision") + 1).put("due", now)
                .remove(List.of("lease", "leaseUntil", "error"));
    }

    private static ObjectNode status(Connection c, IntelligenceStore store, IntelligenceConfig config, long now) throws Exception {
        var state = store.load(c, false, true);
        if (state == null) state = new IntelligenceStore.State(store.initial(false).put("revision", 0), JSON.createObjectNode());
        ObjectNode settings = JSON.createObjectNode().put("revision", state.number("revision"));
        for (String field : List.of("enabled", "memory", "chats", "files", "email")) {
            settings.put(field, state.control().path(field).asBoolean());
        }
        var out = JSON.createObjectNode().put("at", now);
        out.set("settings", settings);
        out.set("action", state.snapshot().path("action"));
        out.set("assessment", state.snapshot().path("assessment"));
        ArrayNode evidence = out.putArray("evidence");
        for (String field : List.of("portfolio", "worldResearch")) {
            Iterable<JsonNode> entries = field.equals("portfolio") ? state.snapshot().path(field)
                    : state.snapshot().path(field).path("sources");
            for (JsonNode item : entries) evidence.addObject().put("id", item.path("id").asText())
                    .put("title", item.path("title").asText()).put("url", item.path("url").asText())
                    .put("at", item.path("at").asLong());
        }
        boolean currentDay = IntelligenceStore.day(now).equals(state.value("budgetDay"));
        String selected = store.selectedKey(c);
        out.putObject("runtime").put("status", state.value("status")).put("error", state.value("error"))
                .put("heartbeat", state.number("heartbeat")).put("lastReview", state.number("lastReview"))
                .put("due", state.enabled() ? state.number("due") : 0).put("failures", state.number("failures"))
                .put("profileState", state.value("profileState")).put("searchState", state.value("searchState"))
                .put("profileAt", state.number("profileAt")).put("searchAt", state.number("searchAt"))
                .put("modelAvailable", !selected.isBlank() && (!state.enabled() || selected.equals(state.value("keyId"))))
                .put("mailConfigured", config.mail() != null || state.control().path("mailConfigured").asBoolean())
                .put("budgetMicros", config.dailyBudget).put("estimatedMicros", currentDay ? state.number("estimated") : 0)
                .put("usedMicros", currentDay ? state.number("budgetUsed") : 0)
                .put("calls", currentDay ? state.number("calls") : 0).put("searchCalls", currentDay ? state.number("searchCalls") : 0)
                .put("lastFeedback", state.number("lastFeedback"));
        out.set("mail", store.mailHistory(c));
        ArrayNode reviews = out.putArray("history");
        for (var item : store.history(c, "REVIEW", 12)) {
            reviews.addObject().put("id", item.id()).put("at", item.time())
                    .put("title", item.payload().path("action").path("title").asText())
                    .set("assessment", item.payload().path("assessment"));
        }
        ArrayNode reports = out.putArray("feedback");
        for (var item : store.history(c, "FEEDBACK", 6)) {
            reports.addObject().put("at", item.time()).put("text", item.payload().path("text").asText());
        }
        ArrayNode policies = out.putArray("policies");
        for (var item : store.history(c, "POLICY", 8)) {
            if (item.payload().path("privacyRevision").asLong() == state.number("privacyRevision")) {
                policies.addObject().put("id", item.id()).put("at", item.time()).put("learning", item.payload().path("learning").asText());
            }
        }
        return out;
    }

    private static StorageFailure problem(int status, String code, String text) {
        return new StorageFailure(status, code, text);
    }

    private static void send(HttpServletResponse response, int status, ObjectNode value) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        JSON.writeValue(response.getOutputStream(), value);
    }
}
