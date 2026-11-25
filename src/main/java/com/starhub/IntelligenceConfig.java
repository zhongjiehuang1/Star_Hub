package com.starhub;

import java.net.URI;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.ZoneId;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

final class IntelligenceConfig {
    static final ZoneId ZONE = ZoneId.of("Europe/London");
    private final Properties properties;
    final long user;
    final String generation;
    final boolean worker;
    final long dailyBudget;
    final String entry;
    final byte[] vault;

    static Properties deploymentProperties() throws IOException {
        Properties base = AccountServlet.config();
        Path account = Path.of(System.getProperty("starhub.account.config", "/var/lib/starhub/account-config.properties"));
        Path feature = account.resolveSibling("starhub-intelligence.properties");
        if (!Files.exists(feature, LinkOption.NOFOLLOW_LINKS)) return base;
        if (!Files.isRegularFile(feature, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid Intelligence settings file");
        var permissions = Files.getPosixFilePermissions(feature, LinkOption.NOFOLLOW_LINKS);
        if (permissions.stream().anyMatch(permission -> permission.name().startsWith("OTHERS_")
                || permission == PosixFilePermission.GROUP_WRITE)) throw new IOException("Intelligence settings file is not private");
        Properties scoped = new Properties();
        try (var source = Files.newInputStream(feature, LinkOption.NOFOLLOW_LINKS)) { scoped.load(source); }
        for (String name : scoped.stringPropertyNames()) {
            if (!name.startsWith("intelligence.")) throw new IOException("Invalid Intelligence setting");
            base.setProperty(name, scoped.getProperty(name));
        }
        return base;
    }

    static IntelligenceConfig read(Properties properties) {
        if (properties.getProperty("intelligence.owner.id", "").isBlank()) return null;
        return new IntelligenceConfig(properties);
    }

    private IntelligenceConfig(Properties properties) {
        this.properties = properties;
        user = Long.parseLong(properties.getProperty("intelligence.owner.id"));
        generation = properties.getProperty("intelligence.owner.generation", "");
        worker = "true".equals(properties.getProperty("intelligence.worker"));
        dailyBudget = Long.parseLong(properties.getProperty("intelligence.budget.dailyMicros", "4000000"));
        vault = Base64.getDecoder().decode(properties.getProperty("vault.key", ""));
        String origin = properties.getProperty("intelligence.publicOrigin", "https://starhub.zhongjiehuang.com");
        if (user < 1 || !isId(generation) || dailyBudget < 0 || dailyBudget > 20_000_000
                || !Set.of(16, 24, 32).contains(vault.length) || !DeploymentConfig.parseOrigins(origin).equals(Set.of(origin))
                || !"https".equals(URI.create(origin).getScheme())) {
            throw new IllegalArgumentException("Invalid Star Intelligence configuration");
        }
        entry = origin + "/?intelligence=1";
    }

    boolean sameOwner(IntelligenceConfig config) {
        return config != null && user == config.user && generation.equals(config.generation);
    }

    static final class Mail {
        final String to, from, fromName, key, statusKey, transportId;
        final boolean qqMailbox;

        private Mail(String to, String from, String fromName, String key, String statusKey, boolean qqMailbox) {
            this.to = to;
            this.from = from;
            this.fromName = fromName;
            this.key = key;
            this.statusKey = statusKey;
            this.qqMailbox = qqMailbox;
            String binding = (qqMailbox ? "qq\n" : "") + from + "\n" + to + "\n" + key;
            transportId = HexFormat.of().formatHex(FileStorage.hash(
                    binding.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
    }

    Mail mail() {
        String to = properties.getProperty("intelligence.mail.to", "").strip();
        String from = properties.getProperty("intelligence.mail.from", "").strip();
        String key = properties.getProperty("intelligence.mail.key", "").strip();
        if (!address(to) || !address(from)) return null;
        return switch (properties.getProperty("intelligence.mail.provider", "resend")) {
            case "qq" -> to.matches("[0-9]{5,15}@qq\\.com") && to.equalsIgnoreCase(from) && key.matches("[A-Za-z0-9]{16}")
                    ? new Mail(to, to, "", key, "", true) : null;
            case "resend" -> {
                String name = properties.getProperty("intelligence.mail.fromName", "").strip();
                String status = properties.getProperty("intelligence.mail.statusKey", key).strip();
                yield credential(key) && credential(status) && validFromName(name)
                        ? new Mail(to, from, name, key, status, false) : null;
            }
            default -> null;
        };
    }

    private static boolean address(String value) {
        return value.length() <= 254 && value.matches("[A-Za-z0-9_+.-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+");
    }

    static boolean validFromName(String value) {
        return value.isEmpty() || value.matches("[A-Za-z0-9][A-Za-z0-9 _-]{0,63}");
    }

    private static boolean credential(String value) {
        return value.matches("re_[A-Za-z0-9_-]{8,252}");
    }

    static String id() {
        return UUID.randomUUID().toString();
    }

    static boolean isId(String value) {
        return value != null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
}
