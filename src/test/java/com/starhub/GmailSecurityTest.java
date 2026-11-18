package com.starhub;

import org.junit.jupiter.api.Test;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class GmailSecurityTest {
    private static final String GENERATION = "ed8c456d-8f25-4a49-9f0f-ef39dc75323f";
    private static final byte[] VAULT = new byte[32];
    private static Map<String,String> query(URI uri) {
        return Arrays.stream(uri.getRawQuery().split("&")).map(s -> s.split("=",2))
                .collect(Collectors.toMap(s -> s[0], s -> URLDecoder.decode(s[1], StandardCharsets.UTF_8)));
    }

    @Test void authorizationUsesOnlyReadonlyScopeAndBoundPkceState() throws Exception {
        var config = new GmailSecurity.Config("123456-example.apps.googleusercontent.com", "local-secret",
                URI.create("https://starhub.zhongjiehuang.com/api/gmail/callback"));
        var first = GmailSecurity.challenge(1_000); var second = GmailSecurity.challenge(1_000);
        assertNotEquals(first.state(),second.state()); assertNotEquals(first.verifier(),second.verifier());
        String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
                .digest(first.verifier().getBytes(StandardCharsets.US_ASCII)));
        assertEquals(expected,first.challenge());
        URI url = GmailSecurity.authorization(config,first); assertEquals("accounts.google.com",url.getHost());
        var q = query(url); assertEquals(GmailSecurity.READ_ONLY_SCOPE,q.get("scope"));
        assertEquals(first.state(),q.get("state")); assertEquals(first.challenge(),q.get("code_challenge"));
        assertEquals("S256",q.get("code_challenge_method")); assertEquals("offline",q.get("access_type"));
        assertFalse(url.toString().contains(config.clientSecret()));
        assertFalse(config.toString().contains(config.clientSecret())); assertFalse(first.toString().contains(first.verifier()));
    }

    @Test void stateMustMatchAndExpireWithinFiveMinutes() throws Exception {
        var issued=GmailSecurity.challenge(10_000);
        assertTrue(GmailSecurity.validState(issued,issued.state(),10_000));
        assertTrue(GmailSecurity.validState(issued,issued.state(),310_000));
        assertFalse(GmailSecurity.validState(issued,issued.state(),310_001));
        assertFalse(GmailSecurity.validState(issued,issued.state(),9_999));
        assertFalse(GmailSecurity.validState(issued,"wrong",10_000));
        assertFalse(GmailSecurity.validState(issued,null,10_000));
    }

    @Test void credentialIsEncryptedAndBoundToAccountGenerationAndPurpose() throws Exception {
        var sealed=GmailSecurity.seal("sample-refresh-token",VAULT,42,GENERATION,"refresh-token");
        assertEquals("sample-refresh-token",GmailSecurity.open(sealed,VAULT,42,GENERATION,"refresh-token"));
        assertThrows(GeneralSecurityException.class,()->GmailSecurity.open(sealed,VAULT,43,GENERATION,"refresh-token"));
        assertThrows(GeneralSecurityException.class,()->GmailSecurity.open(sealed,VAULT,42,GENERATION,"pkce-verifier"));
        assertThrows(GeneralSecurityException.class,()->GmailSecurity.open(sealed,VAULT,42,
                "0d8c456d-8f25-4a49-9f0f-ef39dc75323f","refresh-token"));
        byte[] modified=sealed.ciphertext(); modified[0]^=1;
        assertThrows(GeneralSecurityException.class,()->GmailSecurity.open(new GmailSecurity.Sealed(sealed.nonce(),modified),VAULT,42,GENERATION,"refresh-token"));
        assertNotEquals(Base64.getEncoder().encodeToString(sealed.ciphertext()),Base64.getEncoder().encodeToString(
                GmailSecurity.seal("sample-refresh-token",VAULT,42,GENERATION,"refresh-token").ciphertext()));
        assertThrows(IllegalArgumentException.class,()->GmailSecurity.seal("line\nbreak",VAULT,42,GENERATION,"refresh-token"));
    }

    @Test void redirectRejectsForeignOriginAndMalformedTargets() {
        assertThrows(IllegalArgumentException.class,()->GmailSecurity.validateRedirect(URI.create("https://attacker.example/api/gmail/callback")));
        assertThrows(IllegalArgumentException.class,()->GmailSecurity.validateRedirect(URI.create("http://starhub.zhongjiehuang.com/api/gmail/callback")));
        assertThrows(IllegalArgumentException.class,()->GmailSecurity.validateRedirect(URI.create("https://starhub.zhongjiehuang.com/api/gmail/callback?next=evil")));
        assertThrows(IllegalArgumentException.class,()->GmailSecurity.validateRedirect(URI.create("https://starhub.zhongjiehuang.com/api/gmail/other")));
    }
}
