package com.envpermit.notification;

/** Outgoing mail adapter. {@link SmtpMailer} when SMTP_HOST is set, else {@link LogMailer}; tests use an in-memory fake. */
public interface Mailer {
    void send(String to, String subject, String body);
}
