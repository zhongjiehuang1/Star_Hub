package com.starhub;

import java.time.Instant;
import java.util.Base64;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class DesktopDiagnosticsTest {
    @Test
    void constrainsIssuerAndRejectsExpiredOrForeignCredentials() throws Exception {
        var config = new Properties();
        config.setProperty("vault.key", Base64.getEncoder().encodeToString(new byte[32]));
        assertFalse(DesktopDiagnosticsServlet.enabled(config, "owner@example.test"));
        config.setProperty("desktop.diagnostics.email", "owner@example.test");
        assertTrue(DesktopDiagnosticsServlet.enabled(config, "Owner@example.test"));
        assertFalse(DesktopDiagnosticsServlet.enabled(config, "other@example.test"));
        var now = Instant.parse("2026-11-25T23:00:00Z");
        String token = DesktopDiagnosticsServlet.issue(config, 7, "generation", "digest", now);
        assertEquals(7, DesktopDiagnosticsServlet.decode(config, token, now).path("user").asLong());
        assertNull(DesktopDiagnosticsServlet.decode(config, token, now.plusSeconds(604800)));
        config.setProperty("vault.key", Base64.getEncoder().encodeToString(new byte[16]));
        assertNull(DesktopDiagnosticsServlet.decode(config, token, now));
    }

    @Test
    void acceptsDiagnosticFieldsAndRejectsPromptAndPathContent() {
        var input = ModelGateway.JSON.createObjectNode().put("operation", "pdf_recycle")
            .put("state", "failed").put("code", "0x80070005").put("windowsBuild", 19045)
            .put("id", "00112233445566778899aabbccddeeff").put("version", "0.2.14").put("elapsedMs", 150);
        assertEquals(input, DesktopDiagnosticsServlet.event(input));
        input.put("prompt", "test prompt");
        assertNull(DesktopDiagnosticsServlet.event(input));
        input.remove("prompt");
        input.put("code", "C:\\Users\\Name\\Desktop\\file.pdf");
        assertNull(DesktopDiagnosticsServlet.event(input));
    }
}
