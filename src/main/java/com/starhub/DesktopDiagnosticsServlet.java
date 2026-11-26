package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/** Write-only diagnostic events from the Windows app linked to the configured account. */
public final class DesktopDiagnosticsServlet extends HttpServlet {
    static final String PATH = "/api/desktop-diagnostics";
    private static final String PURPOSE = "starhub/desktop-diagnostics/v1";
    private static final long LIFETIME = 7 * 24 * 60 * 60;
    private static final Set<String> STATES = Set.of("started", "completed", "partial", "failed", "cancelled", "disabled");

    static boolean enabled(Properties config, String email) {
        String owner = config.getProperty("desktop.diagnostics.email", "").strip();
        return !owner.isEmpty() && owner.equalsIgnoreCase(email);
    }

    static String issue(Properties config, long user, String generation, String password, Instant now) throws Exception {
        return DesktopScope.issue(config, user, generation, password, now, PURPOSE, LIFETIME);
    }

    static JsonNode decode(Properties config, String token, Instant now) {
        return DesktopScope.decode(config, token, now, PURPOSE, LIFETIME);
    }

    static ObjectNode event(JsonNode input) {
        if (input == null || !input.isObject() || input.size() != 7) return null;
        for (String field : Set.of("operation", "state", "id", "version", "code"))
            if (!input.path(field).isTextual()) return null;
        if (!input.path("windowsBuild").isInt() || !input.path("elapsedMs").isInt()) return null;
        String operation = input.path("operation").asText(), state = input.path("state").asText();
        String id = input.path("id").asText(), version = input.path("version").asText(), code = input.path("code").asText();
        int build = input.path("windowsBuild").asInt(-1), elapsed = input.path("elapsedMs").asInt(-1);
        if (!operation.matches("[a-z][a-z0-9_]{0,47}") || !STATES.contains(state) || !id.matches("[0-9a-f]{32}") ||
            !version.matches("[0-9]{1,5}(\\.[0-9]{1,5}){2,3}") ||
            !code.matches("(?:|unhandled|computer_mode_off|access_denied|missing_saved_pdf|not_recycled|0x[0-9A-F]{8})") ||
            build < 1 || build > 99999 || elapsed < 0 || elapsed > 3_600_000) return null;
        return ModelGateway.JSON.createObjectNode().put("operation", operation).put("state", state).put("id", id)
            .put("version", version).put("code", code).put("windowsBuild", build).put("elapsedMs", elapsed);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ") || !"desktop".equals(request.getHeader("X-Starhub-Client")) ||
            request.getHeader("Origin") != null) { response.setStatus(401); return; }
        String contentType = request.getContentType();
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            response.setStatus(415); return;
        }
        try {
            var config = AccountServlet.config();
            Instant now = Instant.now();
            var grant = decode(config, authorization.substring(7), now);
            if (grant == null || !active(config, grant)) { response.setStatus(401); return; }
            byte[] body = request.getInputStream().readNBytes(2049);
            if (body.length > 2048) { response.setStatus(413); return; }
            ObjectNode entry;
            try { entry = event(ModelGateway.JSON.readTree(body)); }
            catch (IOException invalid) { entry = null; }
            if (entry == null) { response.setStatus(400); return; }
            entry.put("at", now.toString()).put("device", grant.path("device").asText());
            getServletContext().log("STARHUB_DESKTOP_DIAGNOSTIC " + entry);
            response.setStatus(204);
        } catch (Exception unavailable) { response.setStatus(503); }
    }

    private static boolean active(Properties config, JsonNode grant) throws Exception {
        if (config.getProperty("desktop.diagnostics.email", "").isBlank()) return false;
        try (var db = AccountServlet.db(config);
             var query = db.prepareStatement("SELECT email, generation, password_hash FROM users WHERE id=? AND active=1")) {
            query.setLong(1, grant.path("user").asLong());
            try (var row = query.executeQuery()) {
                return row.next() && enabled(config, row.getString(1)) && grant.path("generation").asText().equals(row.getString(2)) &&
                    DesktopConnection.constantEquals(grant.path("password").asText(), DesktopConnection.digest(row.getBytes(3)));
            }
        }
    }
}
