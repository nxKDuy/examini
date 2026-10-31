package com.datn.auth.service;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;

/** Plain-text UTF-8 email through the shared SMTP settings (SMTP_* env); blank host or sender = not configured. */
@Component @Slf4j
public class Mailer {
    private final ObjectProvider<JavaMailSender> mail;
    private final String host, from;

    public Mailer(ObjectProvider<JavaMailSender> mail, @Value("${spring.mail.host:}") String host, @Value("${auth.mail.from:}") String from) {
        this.mail = mail; this.host = host; this.from = from;
    }

    public boolean configured() { return !host.isBlank() && !from.isBlank() && mail.getIfAvailable() != null; }

    /** @return true when the SMTP server accepted the message */
    public boolean send(String to, String subject, String text) {
        if (!configured()) return false;
        try {
            JavaMailSender sender = mail.getObject();
            MimeMessage m = sender.createMimeMessage();
            var h = new MimeMessageHelper(m, false, StandardCharsets.UTF_8.name());
            h.setFrom(new InternetAddress(from)); h.setTo(to); h.setSubject(subject); h.setText(text, false);
            sender.send(m);
            return true;
        } catch (Exception e) {
            log.warn("Email to {} failed: {}", to.replaceAll("(?<=.).(?=[^@]*@)", "*"), e.toString());
            return false;
        }
    }
}
