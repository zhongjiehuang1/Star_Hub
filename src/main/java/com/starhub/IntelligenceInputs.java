package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;

import static com.starhub.ModelGateway.JSON;

final class IntelligenceInputs {
    private static final String PORTFOLIO = "https://www.zhongjiehuang.com";
    private static final List<String> PAGES = List.of("/", "/about/", "/research/", "/projects/", "/education/");
    private static final Pattern MAIN = Pattern.compile("(?is)<main(?:\\s[^>]*)?>(.*?)</main\\s*>");
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)\\b[\\w.+-]+@[\\w.-]+\\.[a-z]{2,}\\b|\\b(?:sk-[\\w-]{10,}|re_[\\w-]{12,}|AKIA[A-Z0-9]{16})\\b"
            + "|-----BEGIN [A-Z ]+PRIVATE KEY-----[\\s\\S]*?(?:-----END [A-Z ]+PRIVATE KEY-----|\\z)"
            + "|\\b(?:password|api[ _-]?key|secret|access[ _-]?token|密码|密钥)\\s*[:=]\\s*\\S+");
    private final IntelligenceConfig config;
    private final IntelligenceStore store;

    IntelligenceInputs(IntelligenceConfig config, IntelligenceStore store) {
        this.config = config;
        this.store = store;
    }

    static ArrayNode portfolio(long now) throws IOException {
        ArrayNode docs = JSON.createArrayNode();
        IOException failure = null;
        for (int index = 0; index < PAGES.size(); index++) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Intelligence interrupted");
            URI uri = URI.create(PORTFOLIO + PAGES.get(index));
            try {
                var reply = ProviderTransportPolicy.get(uri, java.util.Map.of("Accept", "text/html"), 8, 384 * 1024);
                if (reply.status() != 200 || !reply.contentType().toLowerCase(Locale.ROOT).startsWith("text/html")) continue;
                String content = pageText(new String(reply.bytes(), StandardCharsets.UTF_8));
                if (!content.isBlank()) docs.addObject().put("id", "P" + (index + 1)).put("url", uri.toString())
                        .put("title", switch (index) {
                            case 1 -> "About";
                            case 2 -> "Research";
                            case 3 -> "Projects";
                            case 4 -> "Education";
                            default -> "Public profile";
                        }).put("text", content).put("at", now);
            } catch (IOException unavailable) {
                failure = unavailable;
            }
        }
        if (docs.isEmpty()) throw new IOException("Portfolio unavailable", failure);
        return docs;
    }

    static String pageText(String html) throws IOException {
        var main = MAIN.matcher(html);
        if (!main.find()) return "";
        StringBuilder plain = new StringBuilder();
        new ParserDelegator().parse(new StringReader(main.group(1)), new HTMLEditorKit.ParserCallback() {
            private int hidden;
            @Override public void handleStartTag(HTML.Tag tag, MutableAttributeSet attrs, int position) {
                if (tag == HTML.Tag.SCRIPT || tag == HTML.Tag.STYLE) hidden++;
            }
            @Override public void handleEndTag(HTML.Tag tag, int position) {
                if ((tag == HTML.Tag.SCRIPT || tag == HTML.Tag.STYLE) && hidden > 0) hidden--;
            }
            @Override public void handleText(char[] text, int position) {
                if (hidden == 0 && plain.length() < 6000) plain.append(text).append(' ');
            }
        }, true);
        return bounded(plain.toString(), 1300);
    }

    static ObjectNode publicScan(JsonNode response, long now) {
        if (!response.path("details").path("webSearchUsed").asBoolean() || response.path("completedSearchCalls").asInt() < 1) {
            throw new IntelligenceStore.Unready("SEARCH_UNCONFIRMED");
        }
        ObjectNode scan = JSON.createObjectNode().put("at", now).put("text", bounded(response.path("text").asText(), 2800));
        ArrayNode sources = scan.putArray("sources");
        Set<String> seen = new HashSet<>();
        for (JsonNode citation : response.path("details").path("webCitations")) {
            String url = citation.path("url").asText();
            if (safeUrl(url) && seen.add(url)) {
                String title = bounded(citation.path("title").asText(), 160);
                String id = "W" + (sources.size() + 1);
                sources.addObject().put("id", id).put("url", url)
                        .put("title", title.isBlank() ? URI.create(url).getHost() : title).put("at", now);
                if (sources.size() == 8) break;
            }
        }
        if (sources.isEmpty() || scan.path("text").asText().isBlank()) throw new IntelligenceStore.Unready("SEARCH_UNCONFIRMED");
        return scan;
    }

    ObjectNode privateInputs(Connection c, IntelligenceStore.State state) throws Exception {
        ObjectNode input = JSON.createObjectNode();
        ArrayNode memory = input.putArray("memory");
        if (state.control().path("memory").asBoolean()) {
            ObjectNode account = AccountMemories.promptSnapshot(c, config.user, config.generation, config.vault);
            if (account.path("enabled").asBoolean()) input.put("memoryRevision", account.path("revision").asLong());
            for (JsonNode item : account.path("memories")) {
                String id = "M" + (memory.size() + 1);
                memory.addObject().put("id", id).put("kind", item.path("kind").asText())
                        .put("text", bounded(item.path("text").asText(), 250));
                if (memory.size() >= 7) break;
            }
        }
        ArrayNode chats = input.putArray("chats");
        if (state.control().path("chats").asBoolean()) recentChatText(c, chats);
        ArrayNode feedback = input.putArray("feedback");
        for (var item : store.history(c, "FEEDBACK", 6)) {
            feedback.addObject().put("id", item.id()).put("at", item.time())
                    .put("actionId", item.payload().path("actionId").asText())
                    .put("text", bounded(item.payload().path("text").asText(), 750));
        }
        return input;
    }

    private void recentChatText(Connection c, ArrayNode output) throws Exception {
        // Only recent user messages. Drafts, responses, attachments, and large conversations are excluded.
        try (PreparedStatement q = c.prepareStatement("SELECT payload,updated_at FROM conversations"
                + " WHERE user_id=? AND OCTET_LENGTH(payload)<=65536 ORDER BY updated_at DESC,chat_id LIMIT 4")) {
            q.setLong(1, config.user);
            try (ResultSet r = q.executeQuery()) {
                while (r.next()) {
                    JsonNode messages;
                    try {
                        messages = JSON.readTree(r.getString(1)).path("messages");
                    } catch (IOException legacy) {
                        continue;
                    }
                    if (!messages.isArray()) continue;
                    for (int i = messages.size() - 1, added = 0; i >= 0 && added < 2; i--) {
                        JsonNode message = messages.get(i);
                        if (!"user".equals(message.path("role").asText())) continue;
                        String text = bounded(message.path("content").asText(), 480);
                        if (text.isBlank()) continue;
                        String id = "C" + (output.size() + 1);
                        output.addObject().put("id", id).put("text", text)
                                .put("at", r.getTimestamp(2).getTime());
                        added++;
                    }
                }
            }
        }
    }

    List<String> fileIds(Connection c) throws Exception {
        List<String> ids = new ArrayList<>();
        try (PreparedStatement q = c.prepareStatement("SELECT f.id FROM starhub_library_folders d"
                + " JOIN starhub_library_placements p ON p.folder_id=d.id AND p.user_id=d.user_id AND p.owner_generation=d.owner_generation"
                + " JOIN library_files f ON f.id=p.file_id AND f.user_id=p.user_id AND f.owner_generation=p.owner_generation"
                + " WHERE d.user_id=? AND d.owner_generation=? AND d.name='Star Intelligence'"
                + " AND f.deleted_at IS NULL AND f.mime='text/plain' AND f.size_bytes<=8192"
                + " ORDER BY f.created_at DESC,f.id LIMIT 3")) {
            store.bind(q, 1);
            try (ResultSet r = q.executeQuery()) { while (r.next()) ids.add(r.getString(1)); }
        }
        return ids;
    }

    static void addFile(ArrayNode files, AtomicFileStorage.BinaryFile file) {
        if (!"text/plain".equals(file.mime()) || file.bytes().length > 8192) return;
        String snippet = bounded(new String(file.bytes(), StandardCharsets.UTF_8), 700);
        String id = "D" + (files.size() + 1);
        if (!snippet.isBlank()) files.addObject().put("id", id)
                .put("name", bounded(file.name(), 100)).put("text", snippet);
    }

    static boolean safeUrl(String url) {
        if (url.length() > 650) return false;
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme()) && host != null && uri.getUserInfo() == null
                    && uri.getPort() == -1 && host.contains(".") && !host.endsWith(".") && !host.contains(":")
                    && !host.matches("[0-9.]+") && !host.toLowerCase(Locale.ROOT)
                            .matches(".*\\.(?:localhost|local|internal|test|invalid|example|onion)");
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    static String bounded(String text, int max) {
        String value = SENSITIVE.matcher(text).replaceAll("[redacted]").replaceAll("[\\p{Cc}\\p{Cf}\\s]+", " ").strip();
        int count = Math.min(value.codePointCount(0, value.length()), max);
        return value.substring(0, value.offsetByCodePoints(0, count));
    }

    static String time(long millis) {
        return Instant.ofEpochMilli(millis).atZone(IntelligenceConfig.ZONE).toOffsetDateTime().toString();
    }
}
