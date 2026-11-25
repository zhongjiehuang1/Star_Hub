package com.starhub;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DesktopConnectionTest {
    @Test
    void browserApprovalIsBoundToTheDesktopAndExpires() throws Exception {
        var config = new Properties();
        config.setProperty("vault.key", Base64.getEncoder().encodeToString(new byte[32]));
        var now = Instant.parse("2026-11-25T12:00:00Z");
        String verifier = "a".repeat(43);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        String code = DesktopConnection.issue(config, 1, "generation", new byte[32], null, challenge, "gpt-6.2-astra", now);
        var approval = DesktopConnection.decode(config, code, verifier, now.plusSeconds(10));
        assertNotNull(approval);
        assertEquals("gpt-6.2-astra", approval.path("model").asText());
        assertFalse(approval.has("apiKey"));
        assertNull(DesktopConnection.decode(config, code, "b".repeat(43), now));
        assertNull(DesktopConnection.decode(config, (code.startsWith("A") ? "B" : "A") + code.substring(1), verifier, now));
        assertNull(DesktopConnection.decode(config, code, verifier, now.plusSeconds(121)));
    }

    @Test
    void desktopUsesTheCatalogDefaultWhenTheBrowserLastSelectedAnImageModel() {
        assertEquals("gpt-6.2-sol", DesktopConnection.chatModel("gpt-image-2.6-flare"));
    }
}
