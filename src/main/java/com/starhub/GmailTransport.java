package com.starhub;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/** Fixed-host, no-redirect transport for Gmail OAuth and read-only requests. */
interface GmailTransport {
    int MAX_RESPONSE_BYTES = 1_048_576;
    record Reply(int status, byte[] body) {
        public Reply { if(body==null || body.length>MAX_RESPONSE_BYTES)throw new IllegalArgumentException("Invalid Gmail response"); body=body.clone(); }
        @Override public byte[] body(){return body.clone();}
        @Override public String toString(){return "GmailReply[status="+status+", body=redacted]";}
    }
    Reply request(String method, URI target, String bearer, String form) throws IOException;

    /** No caller-controlled host, redirect, method or sensitive error logging. */
    final class Https implements GmailTransport {
        @Override public Reply request(String method, URI target, String bearer, String form) throws IOException {
            boolean oauth="POST".equals(method) && bearer==null && form!=null &&
                    (GmailSecurity.TOKEN_URL.equals(target.toString()) || GmailSecurity.REVOKE_URL.equals(target.toString()));
            boolean gmail="GET".equals(method) && form==null && validBearer(bearer) && allowedGmail(target);
            if(!oauth && !gmail)throw new IOException("Gmail request is not permitted");
            if(form!=null && form.getBytes(StandardCharsets.UTF_8).length>16_384)throw new IOException("Gmail request exceeds limit");
            try { ProviderTransportPolicy.require(); } catch(StorageFailure unavailable) { throw new IOException("Gmail transport unavailable"); }
            HttpURLConnection connection=(HttpURLConnection)target.toURL().openConnection();
            try {
                connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(15_000);connection.setReadTimeout(20_000);
                connection.setRequestMethod(method);connection.setRequestProperty("Accept","application/json");
                if(gmail)connection.setRequestProperty("Authorization","Bearer "+bearer);
                if(oauth){byte[] bytes=form.getBytes(StandardCharsets.UTF_8);connection.setRequestProperty("Content-Type","application/x-www-form-urlencoded");try{ProviderTransportPolicy.writeBody(connection,bytes);}finally{java.util.Arrays.fill(bytes,(byte)0);}}
                int status=connection.getResponseCode();
                if(status>=300 && status<400)throw new IOException("Gmail redirect rejected");
                InputStream input=status>=400?connection.getErrorStream():connection.getInputStream();
                if(input==null)return new Reply(status,new byte[0]);
                try(input){byte[] bytes=input.readNBytes(MAX_RESPONSE_BYTES+1);if(bytes.length>MAX_RESPONSE_BYTES)throw new IOException("Gmail response exceeds limit");return new Reply(status,bytes);}
            } finally { connection.disconnect(); }
        }
        private static boolean validBearer(String value){return value!=null&&value.length()<=4096&&!value.isEmpty()&&value.chars().allMatch(c->c>=33&&c<=126);}
        private static boolean allowedGmail(URI uri){
            if(uri==null||!"https".equals(uri.getScheme())||!"gmail.googleapis.com".equals(uri.getHost())||uri.getPort()!=-1||uri.getRawUserInfo()!=null||uri.getRawFragment()!=null||uri.toString().length()>8192)return false;
            String path=uri.getRawPath();return path.equals("/gmail/v1/users/me/messages")||path.matches("/gmail/v1/users/me/messages/[A-Za-z0-9_-]{1,100}");
        }
    }
}
