package com.envpermit.notification;

import java.util.Properties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/** Sends plain-text mail over SMTP with STARTTLS (e.g. Gmail app password, port 587). */
class SmtpMailer implements Mailer {

    private final JavaMailSenderImpl sender;
    private final String from;

    SmtpMailer(String host, int port, String username, String password, String from) {
        sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(port);
        sender.setUsername(username);
        sender.setPassword(password);
        sender.setDefaultEncoding("UTF-8");
        Properties p = sender.getJavaMailProperties();
        p.put("mail.smtp.auth", "true");
        p.put("mail.smtp.starttls.enable", "true");
        p.put("mail.smtp.starttls.required", "true");
        p.put("mail.smtp.connectiontimeout", "10000");
        p.put("mail.smtp.timeout", "10000");
        p.put("mail.smtp.writetimeout", "10000");
        this.from = from.isBlank() ? username : from;
    }

    @Override
    public void send(String to, String subject, String body) {
        SimpleMailMessage m = new SimpleMailMessage();
        m.setFrom(from);
        m.setTo(to);
        m.setSubject(subject);
        m.setText(body);
        sender.send(m);
    }

    @Configuration(proxyBeanMethods = false)
    static class MailConfig {
        @Bean
        Mailer mailer(@Value("${app.mail.host}") String host, @Value("${app.mail.port}") int port,
                      @Value("${app.mail.username}") String username, @Value("${app.mail.password}") String password,
                      @Value("${app.mail.from}") String from) {
            return host.isBlank() ? new LogMailer() : new SmtpMailer(host, port, username, password, from);
        }
    }
}
