package com.starhub;

import java.io.ByteArrayOutputStream;
import java.util.Set;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeploymentConfigTest {
    @Test void exactOriginsRejectWildcardsPathsCredentialsAndExtraEntries() {
        assertEquals(Set.of("https://one.example", "https://two.example:8443"),
                DeploymentConfig.parseOrigins("https://one.example, https://two.example:8443"));
        for (String invalid : new String[]{"", "null", "*", "https://*.example", "https://one.example/",
                "https://one.example/path", "https://user@one.example", "https://one.example?token=secret",
                "https://one.example#fragment", "https://one.example,", "https://a.example,https://b.example,https://c.example"}) {
            assertThrows(IllegalArgumentException.class, () -> DeploymentConfig.parseOrigins(invalid), invalid);
        }
    }

    @Test void httpAndWebsocketUseTheSameExplicitAllowlistAndFailClosed() {
        String previous = System.getProperty("starhub.public.origins");
        try {
            System.setProperty("starhub.public.origins", "https://one.example,https://two.example");
            assertTrue(DeploymentConfig.allowsOrigin("https://one.example"));
            assertTrue(new RealtimeEndpoint.Origin().checkOrigin("https://two.example"));
            assertFalse(DeploymentConfig.allowsOrigin("https://one.example.evil.test"));
            assertFalse(DeploymentConfig.allowsOrigin(null));
            assertEquals("wss://one.example wss://two.example", DeploymentConfig.websocketSources());
            System.setProperty("starhub.public.origins", "https://one.example,*");
            assertFalse(DeploymentConfig.allowsOrigin("https://one.example"));
        } finally { restore("starhub.public.origins", previous); }
    }

    @Test void serverInfoExposesOnlyAnExplicitNumericIdentityAndDoesNotInventOne() throws Exception {
        String previous = System.getProperty("starhub.server.id");
        try {
            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getPathInfo()).thenReturn("/server");
            for (String configured : new String[]{"1", "2", "3", "10", "", "01", "london"}) {
                System.setProperty("starhub.server.id", configured);
                HttpServletResponse response = mock(HttpServletResponse.class);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                when(response.getOutputStream()).thenReturn(new ServletOutputStream() {
                    public boolean isReady() { return true; }
                    public void setWriteListener(WriteListener ignored) { }
                    public void write(int value) { bytes.write(value); }
                });
                new ApiServlet().doGet(request, response);
                var payload = ModelGateway.JSON.readTree(bytes.toByteArray());
                if (configured.equals("1") || configured.equals("2") || configured.equals("3") || configured.equals("10")) {
                    verify(response).setStatus(200);
                    assertEquals(1, payload.size());
                    assertEquals(Integer.parseInt(configured), payload.path("serverId").intValue());
                } else {
                    verify(response).setStatus(503);
                    assertFalse(payload.has("serverId"));
                }
                verify(response).setHeader("Cache-Control", "no-store");
            }
        } finally { restore("starhub.server.id", previous); }
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key); else System.setProperty(key, value);
    }
}
