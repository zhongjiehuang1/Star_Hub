package com.starhub;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Security primitives for the optional Gmail integration; never logs secrets. */
final class GmailSecurity {
    static final String READ_ONLY_SCOPE = "https://www.googleapis.com/auth/gmail.readonly";
    static final String AUTHORIZE_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    static final String REVOKE_URL = "https://oauth2.googleapis.com/revoke";
    static final String CALLBACK_PATH = "/api/gmail/callback";
    static final long STATE_LIFETIME_MILLIS = 5 * 60 * 1000L;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL64 = Base64.getUrlEncoder().withoutPadding();
    private static final byte[] KEY_CONTEXT = "starhub-gmail-v1\\0".getBytes(StandardCharsets.UTF_8);

    record Config(String clientId, String clientSecret, URI redirectUri) {
        Config {
            if (clientId == null || clientId.length() > 255 ||
                    !clientId.matches("[A-Za-z0-9_-]+\\.apps\\.googleusercontent\\.com"))
                throw new IllegalArgumentException("Gmail OAuth client ID is invalid");
            if (clientSecret == null || clientSecret.isBlank() || clientSecret.length() > 1024 ||
                    clientSecret.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("Gmail OAuth client secret is invalid");
            validateRedirect(redirectUri);
        }
        @Override public String toString() { return "GmailOAuthConfig[redacted]"; }
    }

    record Challenge(String state, String verifier, String challenge, long createdMillis) {
        @Override public String toString() { return "GmailOAuthChallenge[redacted]"; }
    }
    record Sealed(byte[] nonce, byte[] ciphertext) {
        Sealed {
            if (nonce == null || nonce.length != 12 || ciphertext == null || ciphertext.length < 16 || ciphertext.length > 8192)
                throw new IllegalArgumentException("Invalid encrypted Gmail credential");
            nonce = nonce.clone(); ciphertext = ciphertext.clone();
        }
        @Override public byte[] nonce() { return nonce.clone(); }
        @Override public byte[] ciphertext() { return ciphertext.clone(); }
    }

    static void validateRedirect(URI uri) {
        if (uri == null || !"https".equals(uri.getScheme()) || uri.getHost() == null ||
                uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null ||
                !CALLBACK_PATH.equals(uri.getRawPath()) || uri.getPort() < -1 || uri.getPort() > 65535)
            throw new IllegalArgumentException("Invalid Gmail OAuth redirect URI");
        String origin = "https://" + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
        if (!DeploymentConfig.allowedOrigins().contains(origin))
            throw new IllegalArgumentException("Gmail OAuth redirect origin is not configured");
    }

    static Challenge challenge(long now) throws GeneralSecurityException {
        byte[] stateBytes = new byte[32], verifierBytes = new byte[64];
        RANDOM.nextBytes(stateBytes); RANDOM.nextBytes(verifierBytes);
        String state = URL64.encodeToString(stateBytes), verifier = URL64.encodeToString(verifierBytes);
        Arrays.fill(stateBytes, (byte)0); Arrays.fill(verifierBytes, (byte)0);
        return new Challenge(state, verifier, URL64.encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII))), now);
    }

    static boolean validState(Challenge issued, String state, long now) {
        if (issued == null || issued.state() == null || state == null || !state.matches("[A-Za-z0-9_-]{43}") || now < issued.createdMillis() ||
                now - issued.createdMillis() > STATE_LIFETIME_MILLIS) return false;
        return MessageDigest.isEqual(issued.state().getBytes(StandardCharsets.US_ASCII),
                state.getBytes(StandardCharsets.US_ASCII));
    }

    static URI authorization(Config config, Challenge issued) {
        if (issued == null || !issued.state().matches("[A-Za-z0-9_-]{43}") ||
                !issued.challenge().matches("[A-Za-z0-9_-]{43}")) throw new IllegalArgumentException("Invalid OAuth challenge");
        Map<String,String> query = new LinkedHashMap<>();
        query.put("client_id", config.clientId()); query.put("redirect_uri", config.redirectUri().toString());
        query.put("response_type", "code"); query.put("scope", READ_ONLY_SCOPE); query.put("state", issued.state());
        query.put("code_challenge", issued.challenge()); query.put("code_challenge_method", "S256");
        query.put("access_type", "offline"); query.put("prompt", "consent");
        return URI.create(AUTHORIZE_URL + "?" + form(query));
    }

    static String form(Map<String,String> values) {
        StringBuilder out = new StringBuilder();
        values.forEach((k,v) -> { if (out.length() > 0) out.append('&');
            out.append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(v, StandardCharsets.UTF_8)); });
        return out.toString();
    }

    private static byte[] key(byte[] vault) throws GeneralSecurityException {
        if (vault == null || !Set.of(16,24,32).contains(vault.length)) throw new GeneralSecurityException("Gmail vault is not configured");
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(vault, "HmacSHA256"));
        return mac.doFinal(KEY_CONTEXT);
    }

    private static String aad(long userId, String generation, String purpose) {
        if (userId < 1 || generation == null || !generation.matches("[0-9a-fA-F-]{36}") ||
                !Set.of("refresh-token", "pkce-verifier").contains(purpose))
            throw new IllegalArgumentException("Invalid Gmail credential owner");
        return "starhub-gmail-v1:" + userId + ":" + generation + ":" + purpose;
    }

    static Sealed seal(String value, byte[] vault, long userId, String generation, String purpose) throws GeneralSecurityException {
        if (value == null || value.isEmpty() || value.length() > 4096 || value.chars().anyMatch(c -> c < 33 || c > 126))
            throw new IllegalArgumentException("Invalid Gmail credential value");
        byte[] nonce = new byte[12], derived = key(vault), plain = value.getBytes(StandardCharsets.UTF_8);
        RANDOM.nextBytes(nonce);
        try { return new Sealed(nonce, AccountServlet.crypt(true, plain, derived, nonce, aad(userId,generation,purpose))); }
        finally { Arrays.fill(derived, (byte)0); Arrays.fill(plain, (byte)0); }
    }

    static String open(Sealed value, byte[] vault, long userId, String generation, String purpose) throws GeneralSecurityException {
        byte[] derived = key(vault), plain = null;
        try {
            plain = AccountServlet.crypt(false,value.ciphertext(),derived,value.nonce(),aad(userId,generation,purpose));
            if (plain.length == 0 || plain.length > 4096) throw new GeneralSecurityException("Invalid Gmail credential");
            return new String(plain, StandardCharsets.UTF_8);
        } finally { Arrays.fill(derived,(byte)0); if (plain != null) Arrays.fill(plain,(byte)0); }
    }

    private GmailSecurity() { }
}
