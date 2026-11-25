package com.starhub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;

import static com.starhub.ModelGateway.JSON;

final class IntelligenceMail {
    private static final String API = "https://api.resend.com/emails";
    private static final String LOGO_ID = "starhub-notify-avatar-v1";
    private static final String HEADER_OPEN = "<div style=\"color:#655490;font-size:13px\">";
    private static final String HEADER_TEXT = "Star Intelligence</div>";
    private static final String HEADER = HEADER_OPEN + HEADER_TEXT;
    private static final String LOGO = "<img src=\"cid:" + LOGO_ID + "\" alt=\"\" width=\"32\" height=\"32\""
            + " style=\"vertical-align:middle;margin-right:8px;border-radius:8px\">";
    static final long RETRY_WINDOW = 12 * IntelligenceStore.HOUR;

    static ObjectNode action(JsonNode recommendation, IntelligenceConfig config) {
        var message = message("现在最值得做什么", recommendation.path("title").asText(), config);
        append(message, "", recommendation.path("why").asText());
        append(message, "怎么做", "");
        int number = 1;
        for (JsonNode step : recommendation.path("steps")) append(message, "", number++ + ". " + step.asText());
        append(message, "时间与资源", recommendation.path("resources").asText());
        if (!recommendation.path("deadline").asText().isBlank()) {
            append(message, "截止日期", recommendation.path("deadline").asText());
        }
        append(message, "完成标准", recommendation.path("done").asText());
        append(message, "完成后反馈", recommendation.path("feedback").asText());
        int count = 0;
        for (JsonNode evidence : recommendation.path("evidence")) {
            String url = evidence.path("url").asText();
            if (!IntelligenceInputs.safeUrl(url) || count++ >= 3) continue;
            String title = evidence.path("title").asText();
            message.put("text", message.path("text").asText() + "\n" + title + " " + url);
            message.put("html", message.path("html").asText() + "<p style=\"margin:6px 0\"><a style=\"color:#53459e\" href=\""
                    + escape(url) + "\">" + escape(title) + "</a></p>");
        }
        return finish(message, config.entry);
    }

    static ObjectNode incident(IntelligenceConfig config) {
        ObjectNode message = message("运行异常", "Star Intelligence 的后台复盘连续失败", config);
        append(message, "接下来", "系统会按计划重试。您可以在 Star Hub 查看最近成功时间和当前错误状态。");
        return finish(message, config.entry);
    }

    private static ObjectNode message(String subject, String title, IntelligenceConfig config) {
        var transport = config.mail();
        var message = JSON.createObjectNode().put("subject", "Star Intelligence · " + subject).put("text", title)
                .put("html", """
                        <!doctype html><html lang="zh-CN"><head><meta charset="utf-8"></head>
                        <body style="margin:0;background:#f5f4fa;color:#231f35;font:16px/1.65 system-ui,sans-serif">
                        <div style="max-width:600px;margin:20px auto;padding:28px;background:#fff;border:1px solid #e9e6f2;border-radius:20px">
                        """ + HEADER + "\n" + """
                        <h1 style="font-size:24px;line-height:1.35;margin:12px 0 20px">
                        """ + escape(title) + "</h1>");
        if (transport != null && !transport.fromName.isEmpty()) {
            message.put("fromName", transport.fromName).put("inlineLogo", LOGO_ID);
        }
        return message;
    }

    private static void append(ObjectNode message, String label, String value) {
        String plain = label.isBlank() ? value : label + (value.isBlank() ? "" : "\n" + value);
        message.put("text", message.path("text").asText() + "\n\n" + plain);
        String html = label.isBlank() ? "" : "<strong style=\"display:block;margin-top:14px\">" + escape(label) + "</strong>";
        if (!value.isBlank()) html += "<p style=\"margin:4px 0 8px\">" + escape(value) + "</p>";
        message.put("html", message.path("html").asText() + html);
    }

    private static ObjectNode finish(ObjectNode message, String entry) {
        message.put("text", message.path("text").asText() + "\n\n打开 Star Hub：" + entry + "\n");
        message.put("html", message.path("html").asText() + "<p style=\"margin:24px 0 0\"><a href=\"" + escape(entry)
                + "\" style=\"display:inline-block;padding:9px 16px;background:#eeeafa;color:#403477;border-radius:12px\">"
                + "打开 Star Hub</a></p></div></body></html>");
        return message;
    }

    static String escape(String value) {
        return RegionAccess.escape(value);
    }

    static long allowedAt(long now, long lastAttempt, boolean urgent) {
        long earliest = Math.max(now, lastAttempt == 0 ? now : lastAttempt + 20 * IntelligenceStore.HOUR);
        ZonedDateTime time = Instant.ofEpochMilli(earliest).atZone(IntelligenceConfig.ZONE);
        int start = urgent ? 7 : 8;
        if (time.getHour() < start) return time.toLocalDate().atTime(start, 0).atZone(IntelligenceConfig.ZONE).toInstant().toEpochMilli();
        if (time.getHour() >= (urgent ? 22 : 21)) {
            return time.toLocalDate().plusDays(1).atTime(start, 0).atZone(IntelligenceConfig.ZONE).toInstant().toEpochMilli();
        }
        return earliest;
    }

    record Result(String state, String provider) {}

    static ObjectNode resendBody(JsonNode payload, IntelligenceConfig.Mail config) throws IOException {
        String name = payload.path("fromName").asText();
        if (!IntelligenceConfig.validFromName(name)) throw new IOException("Invalid saved sender display name");
        String sender = name.isEmpty() ? config.from : name + " <" + config.from + ">";
        String html = payload.path("html").asText();
        var body = JSON.createObjectNode().put("from", sender).put("subject", payload.path("subject").asText())
                .put("text", payload.path("text").asText()).put("html", html);
        body.putArray("to").add(config.to);
        if (LOGO_ID.equals(payload.path("inlineLogo").asText())) {
            try (var logo = IntelligenceMail.class.getResourceAsStream(LOGO_ID + ".png")) {
                if (logo == null) throw new IOException("Star Intelligence email logo is missing");
                body.put("html", html.replace(HEADER, HEADER_OPEN + LOGO + HEADER_TEXT));
                body.putArray("attachments").addObject().put("filename", LOGO_ID + ".png")
                        .put("content_id", LOGO_ID).put("content", Base64.getEncoder().encodeToString(logo.readAllBytes()));
            }
        }
        return body;
    }

    static Result send(IntelligenceStore.Mail mail, IntelligenceConfig.Mail config) throws IOException {
        if (config.qqMailbox) return IntelligenceQqMail.send(mail, config);
        var body = resendBody(mail.payload(), config);
        HttpsURLConnection connection = (HttpsURLConnection) URI.create(API).toURL().openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(15_000);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Authorization", "Bearer " + config.key);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Idempotency-Key", "star-intelligence/" + mail.id());
            ProviderTransportPolicy.writeBody(connection, JSON.writeValueAsBytes(body));
            int status = connection.getResponseCode();
            if (status == 429 || status >= 500 || status == 409) return new Result("RETRY", null);
            if (status < 200 || status >= 300) return new Result("FAILED", null);
            try (var stream = connection.getInputStream()) {
                byte[] bytes = stream.readNBytes(8193);
                if (bytes.length > 8192) return new Result("RETRY", null);
                JsonNode response = JSON.readTree(bytes);
                String id = response == null ? "" : response.path("id").asText();
                return IntelligenceConfig.isId(id) ? new Result("ACCEPTED", id) : new Result("RETRY", null);
            }
        } finally {
            connection.disconnect();
        }
    }

    static String status(IntelligenceStore.Mail mail, IntelligenceConfig.Mail config) throws IOException {
        if (config.qqMailbox) return IntelligenceQqMail.status(mail, config);
        if (!IntelligenceConfig.isId(mail.provider())) return "UNCONFIRMED";
        var reply = ProviderTransportPolicy.get(URI.create(API + "/" + mail.provider()),
                Map.of("Authorization", "Bearer " + config.statusKey, "Accept", "application/json"), 12, 96 * 1024);
        if (reply.status() != 200) return mail.state();
        JsonNode status = JSON.readTree(reply.bytes());
        if (status == null || !mail.provider().equals(status.path("id").asText())
                || status.path("to").size() != 1 || !config.to.equalsIgnoreCase(status.path("to").path(0).asText())) return mail.state();
        return switch (status.path("last_event").asText()) {
            case "delivered", "opened", "clicked" -> "DELIVERED";
            case "bounced", "complained", "suppressed" -> "BOUNCED";
            case "delivery_delayed" -> "DELAYED";
            case "failed", "canceled" -> "FAILED";
            default -> mail.state();
        };
    }
}
