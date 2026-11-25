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
        byte[] body = request.getInputStream().readNBytes(4097);
        if (body.length > 4096) { error(response, 413, "INVALID_REQUEST"); return; }
        JsonNode input;
        try { input = ModelGateway.JSON.readTree(body); }
        catch (IOException invalid) { error(response, 400, "INVALID_REQUEST"); return; }
        if (input == null || !input.isObject()) { error(response, 400, "INVALID_REQUEST"); return; }
        ObjectNode profile;
        try {
            var config = AccountServlet.config();
            var grant = DesktopConnection.decode(config, input.path("code").asText(), input.path("verifier").asText(), Instant.now());
            if (grant == null) { error(response, 401, "CONNECTION_EXPIRED"); return; }
            var storage = FileStorage.get();
            storage.ensureReady();
            if (storage.replicated && storage.atomic != null) {
                profile = storage.atomic.database.run(UUID.randomUUID().toString(), conn ->
                    DesktopConnection.redeem(conn, config, grant));
            } else {
                try (var connection = AccountServlet.db(config)) {
                    profile = DesktopConnection.redeem(connection, config, grant);
                }
                storage.ensureReady();
            }
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
