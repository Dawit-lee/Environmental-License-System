package com.envpermit.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** shortcut: logs mail instead of sending it; replace with an SMTP Mailer when credentials exist. */
@Component
class LogMailer implements Mailer {

    private static final Logger log = LoggerFactory.getLogger(LogMailer.class);

    @Override
    public void send(String to, String subject, String body) {
        log.info("mail to={} subject={}\n{}", to, subject, body);
    }
}
