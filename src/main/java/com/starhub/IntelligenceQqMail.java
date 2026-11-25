package com.starhub;

import jakarta.mail.Address;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.search.HeaderTerm;
import jakarta.mail.search.MessageIDTerm;
import jakarta.mail.search.OrTerm;
import org.eclipse.angus.mail.imap.IMAPStore;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.Properties;

final class IntelligenceQqMail {
    private static final String SMTP_HOST = "smtp.qq.com", IMAP_HOST = "imap.qq.com";
    private static final String CORRELATION = "X-Star-Intelligence-Id";
    private static final String UTF8 = StandardCharsets.UTF_8.name();

    static IntelligenceMail.Result send(IntelligenceStore.Mail mail, IntelligenceConfig.Mail account) {
        try {
            var session = session("smtps");
            MimeMessage message = message(session, mail, account);
            var smtp = session.getTransport("smtps");
            try {
                smtp.connect(SMTP_HOST, 465, account.from, account.key);
            } catch (AuthenticationFailedException rejected) {
                close(smtp);
                return new IntelligenceMail.Result("AUTH_FAILED", null);
            } catch (MessagingException unavailable) {
                close(smtp);
                return new IntelligenceMail.Result("RETRY", null);
            }
            try {
                smtp.sendMessage(message, message.getAllRecipients());
                return new IntelligenceMail.Result("ACCEPTED", null);
            } catch (SMTPSendFailedException rejected) {
                int code = rejected.getReturnCode();
                return new IntelligenceMail.Result(code >= 500 && code <= 599 ? "FAILED" : "VERIFYING", null);
            } catch (MessagingException uncertain) {
                return new IntelligenceMail.Result("VERIFYING", null);
            } finally {
                close(smtp);
            }
        } catch (MessagingException invalidMessage) {
            return new IntelligenceMail.Result("FAILED", null);
        }
    }

    /** Use the QQ inbox itself as the receipt. Search on the server and inspect exact matching headers. */
    static String status(IntelligenceStore.Mail mail, IntelligenceConfig.Mail account) {
        try {
            var session = session("imaps");
            var store = (IMAPStore) session.getStore("imaps");
            try {
                store.connect(IMAP_HOST, 993, account.to, account.key);
                if (store.hasCapability("ID")) store.id(Map.of("name", "Star Intelligence"));
                var inbox = store.getFolder("INBOX");
                inbox.open(Folder.READ_ONLY);
                try {
                    var query = new OrTerm(new MessageIDTerm(messageId(mail.id())), new HeaderTerm(CORRELATION, mail.id()));
                    int checked = 0;
                    for (Message message : inbox.search(query)) {
                        if (matches(message, mail.id(), account.to)) return "RECEIVED";
                        if (++checked >= 8) break;
                    }
                } finally {
                    try { inbox.close(false); } catch (MessagingException ignored) {}
                }
            } finally {
                close(store);
            }
        } catch (MessagingException unavailable) {
            // A failed lookup says nothing about inbox placement. The worker polls on a bounded schedule.
        }
        return mail.state();
    }

    static Session session(String protocol) {
        String prefix = "mail." + protocol + ".";
        Properties properties = new Properties();
        properties.setProperty(prefix + "ssl.enable", "true");
        properties.setProperty(prefix + "ssl.checkserveridentity", "true");
        properties.setProperty(prefix + "connectiontimeout", "7000");
        properties.setProperty(prefix + "timeout", "10000");
        properties.setProperty(prefix + "writetimeout", "10000");
        properties.setProperty(prefix + "auth", "true");
        properties.setProperty(prefix + "throwsearchexception", "true");
        properties.setProperty(prefix + "referralexception", "true");
        properties.setProperty("mail.debug", "false");
        return Session.getInstance(properties);
    }

    static MimeMessage message(Session session, IntelligenceStore.Mail mail, IntelligenceConfig.Mail account) throws MessagingException {
        MimeMessage message = new MimeMessage(session) {
            @Override protected void updateMessageID() throws MessagingException {
                setHeader("Message-ID", messageId(mail.id()));
            }
        };
        message.setFrom(new InternetAddress(account.from, true));
        message.setRecipient(Message.RecipientType.TO, new InternetAddress(account.to, true));
        message.setSentDate(new Date(mail.created()));
        message.setSubject(mail.payload().path("subject").asText(), UTF8);
        message.setHeader(CORRELATION, mail.id());

        var multipart = new MimeMultipart("alternative");
        var plain = new MimeBodyPart();
        plain.setText(mail.payload().path("text").asText(), UTF8);
        multipart.addBodyPart(plain);
        var html = new MimeBodyPart();
        html.setText(mail.payload().path("html").asText(), UTF8, "html");
        multipart.addBodyPart(html);
        message.setContent(multipart);
        message.saveChanges();
        return message;
    }

    static boolean matches(Message message, String correlation, String address) throws MessagingException {
        if (!hasHeader(message, "Message-ID", messageId(correlation)) && !hasHeader(message, CORRELATION, correlation)) return false;
        return exactAddress(message.getFrom(), address) && exactAddress(message.getRecipients(Message.RecipientType.TO), address);
    }

    private static boolean hasHeader(Message message, String header, String value) throws MessagingException {
        String[] values = message.getHeader(header);
        return values != null && values.length == 1 && value.equals(values[0].strip());
    }

    private static boolean exactAddress(Address[] values, String address) {
        return values != null && values.length == 1 && values[0] instanceof InternetAddress mailbox
                && address.equalsIgnoreCase(mailbox.getAddress());
    }

    private static String messageId(String id) {
        if (!IntelligenceConfig.isId(id)) throw new IllegalArgumentException("Invalid message correlation ID");
        return "<star-intelligence." + id + "@starhub.zhongjiehuang.com>";
    }

    private static void close(jakarta.mail.Service service) {
        try { service.close(); } catch (MessagingException ignored) {}
    }
}
