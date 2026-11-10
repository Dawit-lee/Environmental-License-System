package com.envpermit.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Logs mail instead of sending it; used while SMTP_HOST is unset (local dev). */
class LogMailer implements Mailer {

    private static final Logger log = LoggerFactory.getLogger(LogMailer.class);

    @Override
    public void send(String to, String subject, String body) {
        log.info("mail to={} subject={}\n{}", to, subject, body);
    }
}
