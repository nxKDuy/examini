package com.datn.notification.delivery;
import jakarta.mail.internet.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.*;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;

/** Plain-text UTF-8 email through the configured SMTP server (SMTP_* env); blank host/from means not configured. */
@Component
public class EmailSender {
    private final ObjectProvider<JavaMailSender> mail;private final String host,from;
    public EmailSender(ObjectProvider<JavaMailSender> mail,@Value("${spring.mail.host:}") String host,@Value("${notification.mail.from:}") String from){
        this.mail=mail;this.host=host;this.from=from;
    }
    public boolean configured(){return !host.isBlank() && !from.isBlank() && mail.getIfAvailable()!=null;}
    /** Throws on any SMTP failure; acceptance by the SMTP server is the strongest guarantee available (no inbox proof). */
    public void send(String to,String subject,String text)throws Exception{send(to,subject,text,null);}
    /** With {@code ics} non-null the exam is attached as an iCalendar file (mail apps offer "Add to calendar"). */
    public void send(String to,String subject,String text,String ics)throws Exception{
        JavaMailSender sender=mail.getObject();MimeMessage m=sender.createMimeMessage();
        var h=new MimeMessageHelper(m,ics!=null,StandardCharsets.UTF_8.name());
        h.setFrom(new InternetAddress(from));h.setTo(to);h.setSubject(subject);h.setText(text,false);
        if(ics!=null)h.addAttachment("lich-thi.ics",new org.springframework.core.io.ByteArrayResource(ics.getBytes(StandardCharsets.UTF_8)),"text/calendar; charset=UTF-8; method=PUBLISH");
        sender.send(m);
    }
}
