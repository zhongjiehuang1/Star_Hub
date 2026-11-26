package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Properties;
import java.util.UUID;

final class DesktopScope {
    private static final SecureRandom RANDOM = new SecureRandom();
    static String issue(Properties config, long user, String generation, String password, Instant now,
                        String purpose, long lifetime) throws Exception {
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        ObjectNode grant = ModelGateway.JSON.createObjectNode().put("user", user).put("generation", generation)
            .put("password", password).put("device", UUID.randomUUID().toString()).put("expires", now.getEpochSecond() + lifetime);
        byte[] encrypted = AccountServlet.crypt(true, ModelGateway.JSON.writeValueAsBytes(grant), DesktopConnection.vault(config),
            nonce, purpose);
        byte[] envelope = Arrays.copyOf(nonce, nonce.length + encrypted.length);
        System.arraycopy(encrypted, 0, envelope, nonce.length, encrypted.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(envelope);
    }

    static JsonNode decode(Properties config, String token, Instant now, String purpose, long lifetime) {
        if (!token.matches("[A-Za-z0-9_-]{40,1024}")) return null;
        try {
            byte[] envelope = Base64.getUrlDecoder().decode(token);
            var grant = ModelGateway.JSON.readTree(AccountServlet.crypt(false, Arrays.copyOfRange(envelope, 12, envelope.length),
                DesktopConnection.vault(config), Arrays.copyOf(envelope, 12), purpose));
            long remaining = grant.path("expires").asLong() - now.getEpochSecond();
            return remaining > 0 && remaining <= lifetime && grant.path("user").asLong() > 0 ? grant : null;
        } catch (GeneralSecurityException | IOException | IllegalArgumentException invalid) { return null; }
    }

}
