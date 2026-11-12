package com.starhub;

import com.maxmind.db.Reader;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;

/** Uses the IP supplied by our loopback-only reverse proxy. Unknown lookup fails closed. */
final class GeoCountry {
    private static Reader reader;
    private static Object fileKey;
    private static long modified = -1, size = -1;
    private static String activePath;

    static String forRequest(HttpServletRequest request) {
        return lookup(trustedClientAddress(request.getRemoteAddr(),request.getHeader("X-Real-IP"),
                request.getAttribute("org.apache.tomcat.request.forwarded")));
    }

    /** Tomcat's attribute is container state, never an HTTP header. The installed
     * RemoteIpValve trusts loopback only; the entry overwrites both IP headers.
     * See Tomcat 11.0.27 RemoteIpValve's REQUEST_FORWARDED_ATTRIBUTE contract. */
    static String trustedClientAddress(String remote,String clientIp,Object forwarded) {
        try {
            InetAddress peer=literalAddress(remote),client=literalAddress(clientIp);
            if(peer==null||client==null)return null;
            if(peer.isLoopbackAddress())return client.getHostAddress();
            if(Boolean.TRUE.equals(forwarded)&&peer.equals(client))return peer.getHostAddress();
        }catch(Exception invalid) { /* Unknown or inconsistent provenance fails closed. */ }
        return null;
    }
    private static InetAddress literalAddress(String value)throws java.net.UnknownHostException {
        if(value==null||!value.matches("[0-9a-fA-F:.]+")
                ||(value.indexOf(':')<0&&!value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")))return null;
        return InetAddress.getByName(value);
    }

    static String forRequest(String remote, String clientIp) {
        return lookup(trustedClientAddress(remote,clientIp,Boolean.FALSE));
    }
    static synchronized String lookup(String clientIp) {
        try {
            // Reject DNS names, ports, lists and IPv6 scope identifiers.
            InetAddress ip=literalAddress(clientIp);if(ip==null)return null;
            String db = System.getenv("STARHUB_GEOIP_DB");
            if (db == null || db.isEmpty()) return null;
            Path path = Paths.get(db);
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            if (reader == null || !db.equals(activePath) || attrs.lastModifiedTime().toMillis() != modified
                    || attrs.size() != size || !java.util.Objects.equals(fileKey, attrs.fileKey())) {
                Reader next = new Reader(path.toFile(), Reader.FileMode.MEMORY);
                if (reader != null) reader.close();
                reader = next; activePath = db; modified = attrs.lastModifiedTime().toMillis(); size = attrs.size(); fileKey = attrs.fileKey();
            }
            Map<?, ?> result = reader.get(ip, Map.class);
            Object country = result == null ? null : result.get("country");
            Object code = country instanceof Map ? ((Map<?, ?>) country).get("iso_code") : null;
            return code instanceof String ? (String) code : null;
        } catch (Exception e) { return null; }
    }
}
