package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Properties;

/** A short-lived browser approval bound to a verifier held by the desktop process. */
final class DesktopConnection {
    static final String PATH = "/api/desktop-connection";
    private static final String PURPOSE = "starhub/desktop-connection/v1";
    private static final int LIFETIME_SECONDS = 120;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODE = Base64.getUrlEncoder().withoutPadding();

    private DesktopConnection() {}

    static boolean token(String value) { return value.matches("[A-Za-z0-9_-]{43}"); }

    static String chatModel(String requested) {
        if (validModel(requested)) return requested;
        String preferred = Catalog.DATA.path("featured").path("preferredDefault").path("openai").path("general").asText();
        if (preferred.startsWith("openai|")) preferred = preferred.substring(7);
        if (validModel(preferred)) return preferred;
        throw new IllegalStateException("The desktop chat model is not configured.");
    }

    private static boolean validModel(String id) {
        if (!id.matches("[A-Za-z0-9_.:-]{1,160}")) return false;
        try {
            JsonNode model = Catalog.find(Catalog.OPEN_PROVIDER, id);
            return Catalog.contains(model.path("categories"), "chat") &&
                !model.path("batch").asBoolean() && !"external".equals(model.path("execution").asText());
        } catch (ModelGateway.Problem missing) { return false; }
    }

    static String issue(Properties config, long user, String generation, byte[] passwordHash, String idleSession,
                        String challenge, String model, Instant now) throws Exception {
        if (!token(challenge)) throw new IllegalArgumentException("Invalid desktop challenge.");
        ObjectNode grant = ModelGateway.JSON.createObjectNode().put("user", user).put("generation", generation)
            .put("password", digest(passwordHash)).put("idle", idleSession).put("challenge", challenge)
            .put("model", chatModel(model)).put("expires", now.getEpochSecond() + LIFETIME_SECONDS);
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        byte[] encrypted = AccountServlet.crypt(true, ModelGateway.JSON.writeValueAsBytes(grant), vault(config), nonce, PURPOSE);
        byte[] code = Arrays.copyOf(nonce, nonce.length + encrypted.length);
        System.arraycopy(encrypted, 0, code, nonce.length, encrypted.length);
        return ENCODE.encodeToString(code);
    }

    static JsonNode decode(Properties config, String code, String verifier, Instant now) {
        if (!token(verifier) || !code.matches("[A-Za-z0-9_-]{40,2048}")) return null;
        try {
            byte[] envelope = Base64.getUrlDecoder().decode(code);
            byte[] clear = AccountServlet.crypt(false, Arrays.copyOfRange(envelope, 12, envelope.length),
                vault(config), Arrays.copyOf(envelope, 12), PURPOSE);
            JsonNode grant = ModelGateway.JSON.readTree(clear);
            long remaining = grant.path("expires").asLong() - now.getEpochSecond();
            if (remaining <= 0 || remaining > LIFETIME_SECONDS || grant.path("user").asLong() <= 0 ||
                !constantEquals(grant.path("challenge").asText(), digest(verifier.getBytes(StandardCharsets.US_ASCII)))) return null;
            return grant;
        } catch (GeneralSecurityException | java.io.IOException | IllegalArgumentException invalid) { return null; }
    }

    static ObjectNode redeem(Connection db, Properties config, JsonNode grant) throws Exception {
        long user = grant.path("user").asLong();
        String generation = grant.path("generation").asText(), email, displayName;
        try (var query = db.prepareStatement("SELECT email, generation, password_hash, display_name FROM users WHERE id=? AND active=1")) {
            query.setLong(1, user);
            try (var row = query.executeQuery()) {
                if (!row.next() || !generation.equals(row.getString(2)) ||
                    !constantEquals(grant.path("password").asText(), digest(row.getBytes(3)))) return null;
                email = row.getString(1);
                displayName = row.getString(4);
            }
        }
        if (AccountServlet.idleEnabled(config) && AccountIdleSessions.remaining(db, grant.path("idle").asText(), user, generation) <= 0)
            return null;
        ObjectNode profile = ModelGateway.JSON.createObjectNode().put("email", email).put("model", chatModel(grant.path("model").asText()));
        if (displayName != null && !displayName.isBlank()) profile.put("displayName", displayName.strip());
        try (var query = db.prepareStatement("SELECT id,nonce,ciphertext FROM api_keys WHERE user_id=? AND provider=? AND selected=TRUE")) {
            query.setLong(1, user);
            query.setString(2, Catalog.OPEN_PROVIDER);
            try (var row = query.executeQuery()) {
                if (row.next()) profile.put("apiKey", Keyring.decrypt(user, Catalog.OPEN_PROVIDER, row.getString(1),
                    row.getBytes(2), row.getBytes(3), vault(config)));
            }
        }
        return profile;
    }

    private static byte[] vault(Properties config) {
        return Base64.getDecoder().decode(config.getProperty("vault.key"));
    }

    private static String digest(byte[] bytes) throws GeneralSecurityException {
        return ENCODE.encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static boolean constantEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
    }
}
