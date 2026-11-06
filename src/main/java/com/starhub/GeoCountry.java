package com.starhub;

import com.maxmind.db.Reader;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;

/** Uses the IP supplied by our loopback-only reverse proxy. Unknown lookup fails closed. */
final class GeoCountry {
    private static Reader reader;
    private static Object fileKey;
    private static long modified = -1, size = -1;
    private static String activePath;

    static String forRequest(String remote, String clientIp) {
        try {
            if (remote == null || !InetAddress.getByName(remote).isLoopbackAddress()) return null;
            return lookup(clientIp);
        } catch (Exception e) { return null; }
    }
    static synchronized String lookup(String clientIp) {
        try {
            // Reject DNS names, ports, lists and IPv6 scope identifiers.
            if (clientIp == null || !clientIp.matches("[0-9a-fA-F:.]+") || clientIp.indexOf('%') >= 0) return null;
            InetAddress ip = InetAddress.getByName(clientIp);
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
