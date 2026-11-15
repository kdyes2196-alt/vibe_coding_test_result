package com.testreport;

import jakarta.activation.DataHandler;
import jakarta.activation.FileDataSource;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimeUtility;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/** SMTP 로 메일을 보낸다. Gmail, Office 365, 사내 SMTP 모두 설정만 바꿔 사용한다. */
final class MailSender {

    private final AppConfig config;

    MailSender(AppConfig config) {
        this.config = config;
    }

    void send(List<MailingList.Recipient> recipients, String subject, String html, Path attachment)
            throws MessagingException, UnsupportedEncodingException {
        MimeMessage msg = newMessage(subject);
        for (MailingList.Recipient r : recipients) {
            Message.RecipientType type = switch (r.type()) {
                case TO -> Message.RecipientType.TO;
                case CC -> Message.RecipientType.CC;
                case BCC -> Message.RecipientType.BCC;
            };
            msg.addRecipient(type, r.name().isBlank()
                    ? new InternetAddress(r.email())
                    : new InternetAddress(r.email(), r.name(), "UTF-8"));
        }

        MimeBodyPart body = new MimeBodyPart();
        body.setContent(html, "text/html; charset=UTF-8");
        MimeMultipart multipart = new MimeMultipart();
        multipart.addBodyPart(body);
        if (attachment != null) {
            MimeBodyPart file = new MimeBodyPart();
            file.setDataHandler(new DataHandler(new FileDataSource(attachment.toFile())));
            // 한글 파일명이 Outlook/Gmail 에서 깨지지 않도록 인코딩
            file.setFileName(MimeUtility.encodeText(attachment.getFileName().toString(), "UTF-8", "B"));
            multipart.addBodyPart(file);
        }
        msg.setContent(multipart);
        Transport.send(msg);
    }

    /** 오류 알림 등 단순 텍스트 메일. */
    void sendText(String to, String subject, String text) throws MessagingException {
        MimeMessage msg = newMessage(subject);
        msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
        msg.setText(text, StandardCharsets.UTF_8.name());
        Transport.send(msg);
    }

    private MimeMessage newMessage(String subject) throws MessagingException {
        MimeMessage msg = new MimeMessage(session());
        String from = config.get("mail.from", config.get("mail.username", null));
        if (from == null) {
            throw new IllegalStateException("mail.from 또는 mail.username 설정이 필요합니다.");
        }
        try {
            msg.setFrom(new InternetAddress(from, config.get("mail.fromName", ""), "UTF-8"));
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
        msg.setSubject(subject, "UTF-8");
        msg.setSentDate(new Date());
        return msg;
    }

    private Session session() {
        Properties p = new Properties();
        p.put("mail.smtp.host", config.require("mail.smtp.host"));
        p.put("mail.smtp.port", config.get("mail.smtp.port", "587"));
        p.put("mail.smtp.connectiontimeout", "15000");
        p.put("mail.smtp.timeout", "30000");
        switch (config.get("mail.smtp.security", "STARTTLS").toUpperCase(Locale.ROOT)) {
            case "SSL" -> p.put("mail.smtp.ssl.enable", "true");
            case "NONE" -> { }
            default -> {
                p.put("mail.smtp.starttls.enable", "true");
                p.put("mail.smtp.starttls.required", "true");
            }
        }
        boolean auth = config.getBool("mail.smtp.auth", true);
        p.put("mail.smtp.auth", String.valueOf(auth));
        if (!auth) {
            return Session.getInstance(p);
        }
        String user = config.require("mail.username");
        String password = config.mailPassword();
        return Session.getInstance(p, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(user, password);
            }
        });
    }
}
