package com.starhub;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import javax.servlet.http.HttpSession;

/** Central access policy shared by HTTP requests and WebSocket handshakes. */
final class RegionAccess {
    static final String VERIFIED = RegionAccess.class.getName() + ".verified";
    static final String RETURN_TO = RegionAccess.class.getName() + ".return";
    static final String TOKEN = RegionAccess.class.getName() + ".token";
    private static final SecureRandom RANDOM = new SecureRandom();

    static boolean requiresVerification(String country) {
        if (country == null) return true;
        switch (country.trim().toUpperCase(Locale.ROOT)) {
            case "CN": case "HK": case "MO": return true;
            default: return !country.trim().matches("(?i)[a-z]{2}") || country.trim().equalsIgnoreCase("XX");
        }
    }
    static boolean allowed(String country, HttpSession session) {
        return !requiresVerification(country) || (session != null && Boolean.TRUE.equals(session.getAttribute(VERIFIED)));
    }
    static boolean matches(String left, String right) {
        return left != null && right != null && !right.isEmpty() && MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }
    static String token(HttpSession session) {
        String token = (String) session.getAttribute(TOKEN);
        if (token == null) {
            byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            session.setAttribute(TOKEN, token);
        }
        return token;
    }
    static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
