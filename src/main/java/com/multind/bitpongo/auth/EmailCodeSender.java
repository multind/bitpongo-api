package com.multind.bitpongo.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

/** Dedicated SMTP sender; never log recipients, codes, credentials or provider exception messages. */
@Component
public class EmailCodeSender {
    private final JavaMailSenderImpl sender;
    private final boolean enabled;
    private final String from;
    public EmailCodeSender(@Value("${ACCOUNT_EMAIL_ENABLED:false}") boolean enabled,
            @Value("${ACCOUNT_SMTP_HOST:}") String host, @Value("${ACCOUNT_SMTP_PORT:587}") int port,
            @Value("${ACCOUNT_SMTP_USERNAME:}") String username, @Value("${ACCOUNT_SMTP_PASSWORD:}") String password,
            @Value("${ACCOUNT_EMAIL_FROM:}") String from,
            @Value("${ACCOUNT_SMTP_SSL:false}") boolean ssl) {
        this.enabled=enabled && !host.isBlank() && !from.isBlank(); this.from=from;
        sender=new JavaMailSenderImpl(); sender.setHost(host); sender.setPort(port);
        sender.setUsername(username); sender.setPassword(password); sender.setDefaultEncoding("UTF-8");
        var properties=sender.getJavaMailProperties();
        properties.setProperty("mail.smtp.auth",Boolean.toString(!username.isBlank()));
        properties.setProperty("mail.smtp.ssl.enable",Boolean.toString(ssl));
        properties.setProperty("mail.smtp.starttls.enable",Boolean.toString(!ssl));
        properties.setProperty("mail.smtp.starttls.required",Boolean.toString(!ssl));
        properties.setProperty("mail.smtp.connectiontimeout","5000");
        properties.setProperty("mail.smtp.timeout","5000");
        properties.setProperty("mail.smtp.writetimeout","5000");
    }
    public boolean available() { return enabled; }
    public void send(String email,String code,String purpose) {
        if(!available()) throw new IllegalStateException("邮件服务未配置");
        var message=new SimpleMailMessage();
        message.setFrom(from); message.setTo(email); message.setSubject("Bitpongo verification code");
        String action=switch(purpose) {
            case "REGISTER" -> "create a Bitpongo account";
            case "REMINDER_EMAIL" -> "verify reminder email";
            case "EMAIL" -> "change email";
            default -> "reset password";
        };
        message.setText("Your Bitpongo verification code is: "+code+"\n\nPurpose: "+action+
            "\nThis code expires in 10 minutes. Never share it. If you did not request it, ignore this message.");
        sender.send(message);
    }
    public void sendNotification(String email,String subject,String body) {
        if(!available()) throw new IllegalStateException("邮件服务未配置");
        var message=new SimpleMailMessage();
        message.setFrom(from); message.setTo(email); message.setSubject(subject(subject));
        message.setText(body == null ? "" : body);
        sender.send(message);
    }
    private static String subject(String value) {
        if (value == null) return "";
        String cleaned = value.replaceAll("[\\r\\n]+", " ").trim();
        return cleaned.length() <= 120 ? cleaned : cleaned.substring(0, 120);
    }
}
