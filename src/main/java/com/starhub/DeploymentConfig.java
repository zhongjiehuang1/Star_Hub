package com.starhub;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/** Public deployment identity and exact browser origins; never exposes private account configuration. */
final class DeploymentConfig {
    private DeploymentConfig() { }

    private static String setting(String property, String environment, String privateKey) {
        String value = System.getProperty(property);
        if (value == null) value = System.getenv(environment);
        if (value == null) {
            try { value = AccountServlet.config().getProperty(privateKey); }
            catch (java.io.IOException unavailable) { return null; }
        }
        return value;
    }

    static int serverId() {
        String id = setting("starhub.server.id", "STARHUB_SERVER_ID", "server.id");
        if (id==null || !id.matches("[1-9][0-9]*")) throw new IllegalStateException("Server identity is not configured.");
        try{return Integer.parseInt(id);}catch(NumberFormatException invalid){throw new IllegalStateException("Server identity is not configured.",invalid);}
    }

    static Set<String> allowedOrigins() {
        String configured = setting("starhub.public.origins", "STARHUB_PUBLIC_ORIGINS", "public.origins");
        if (configured == null) configured = setting("starhub.public.origin", "STARHUB_PUBLIC_ORIGIN", "public.origin");
        if (configured == null) configured = "https://starhub.zhongjiehuang.com";
        try { return parseOrigins(configured); }
        catch (IllegalArgumentException invalid) { return Collections.emptySet(); }
    }

    static boolean allowsOrigin(String origin) {
        return origin != null && allowedOrigins().contains(origin);
    }

    static Set<String> parseOrigins(String configured) {
        Set<String> origins = new LinkedHashSet<>();
        for (String value : configured.split(",", -1)) {
            String origin = value.trim();
            URI uri = URI.create(origin);
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || !"".equals(uri.getRawPath())
                    || uri.getPort() < -1 || uri.getPort() > 65535 || origin.contains("*")) {
                throw new IllegalArgumentException("Configure exact HTTP origins without paths.");
            }
            origins.add(origin);
        }
        if (origins.isEmpty() || origins.size() > 2) throw new IllegalArgumentException("Configure one or two origins.");
        return Collections.unmodifiableSet(origins);
    }

    static String websocketSources() {
        return allowedOrigins().stream().map(origin -> origin.startsWith("https:")
                ? "wss:" + origin.substring(6) : "ws:" + origin.substring(5)).collect(Collectors.joining(" "));
    }
}
