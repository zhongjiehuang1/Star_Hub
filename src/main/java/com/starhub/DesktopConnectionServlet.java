package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

public final class DesktopConnectionServlet extends HttpServlet {
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!"desktop".equals(request.getHeader("X-Starhub-Client")) || request.getHeader("Origin") != null ||
            request.getContentType() == null || !request.getContentType().toLowerCase(Locale.ROOT).startsWith("application/json")) {
            error(response, 403, "INVALID_CLIENT");
            return;
        }
        boolean chats = "chat".equals(request.getHeader("X-Starhub-Scope"));
        int limit = chats ? 4_000_000 : 4096;
        byte[] body = request.getInputStream().readNBytes(limit + 1);
        if (body.length > limit) { error(response, 413, "INVALID_REQUEST"); return; }
        JsonNode input;
        try { input = ModelGateway.JSON.readTree(body); }
        catch (IOException invalid) { error(response, 400, "INVALID_REQUEST"); return; }
        if (input == null || !input.isObject()) { error(response, 400, "INVALID_REQUEST"); return; }
        ObjectNode profile;
        try {
            var config = AccountServlet.config();
            String authorization = request.getHeader("Authorization");
            boolean memory = authorization != null;
            if (chats && (authorization == null || !authorization.startsWith("Bearer "))) { error(response,401,"CONNECTION_EXPIRED"); return; }
            JsonNode grant = chats ? DesktopScope.decode(config,authorization.substring(7),Instant.now(),DesktopChats.PURPOSE,DesktopChats.LIFETIME) : memory ? DesktopMemory.decode(config, authorization, Instant.now())
                    : DesktopConnection.decode(config, input.path("code").asText(), input.path("verifier").asText(), Instant.now());
            if (grant == null) { error(response, 401, "CONNECTION_EXPIRED"); return; }
            var storage = FileStorage.get();
            storage.ensureReady();
            AtomicDatabase.Work<ObjectNode> work = conn -> {
                if (chats) {
                    if (storage.atomic != null) {
                        ObjectNode mutation = ((ObjectNode) input).deepCopy();
                        mutation.put("action", input.path("deleted").asBoolean() ? "deleteChat" : "saveChat");
                        storage.atomic.transientCapacity(conn, WorkspaceMutationBudget.estimate(
                            conn, grant.path("user").asLong(), "/api/workspace", mutation));
                    }
                    return DesktopChats.save(conn, grant, input);
                }
                if (!memory) return DesktopConnection.redeem(conn, config, grant);
                if (storage.atomic != null && !"read".equals(input.path("action").asText()))
                    storage.atomic.transientCapacity(conn, WorkspaceMutationBudget.MEMORY_BYTES);
                return DesktopMemory.apply(conn, config, grant, input);
            };
            if (storage.replicated && storage.atomic != null) {
                profile = storage.atomic.database.run(UUID.randomUUID().toString(), work);
            } else {
                try (var connection = AccountServlet.db(config)) {
                    connection.setAutoCommit(false);
                    profile = work.run(connection);
                    connection.commit();
                }
                storage.ensureReady();
            }
        } catch (StorageFailure failure) {
            error(response, failure.status, failure.code);
            return;
        } catch (Exception unavailable) {
            error(response, 503, "CLUSTER_UNAVAILABLE");
            return;
        }
        if (profile == null) { error(response, 401, "CONNECTION_EXPIRED"); return; }
        write(response, 200, profile);
    }

    private static void error(HttpServletResponse response, int status, String code) throws IOException {
        write(response, status, ModelGateway.JSON.createObjectNode().put("code", code));
    }

    private static void write(HttpServletResponse response, int status, ObjectNode body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        ModelGateway.JSON.writeValue(response.getOutputStream(), body);
    }
}
