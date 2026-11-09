package com.envpermit.notification;

/** Outgoing mail adapter. Dev uses {@link LogMailer}; tests use an in-memory fake. Real SMTP comes later. */
public interface Mailer {
    void send(String to, String subject, String body);
}
