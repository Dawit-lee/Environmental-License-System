package com.envpermit.support;

import com.envpermit.notification.Mailer;
import java.util.ArrayList;
import java.util.List;

/** Fake mail adapter: keeps sent mail in memory. */
public class InMemoryMailer implements Mailer {

    public record Mail(String to, String subject, String body) {
    }

    private final List<Mail> sent = new ArrayList<>();

    @Override
    public void send(String to, String subject, String body) {
        sent.add(new Mail(to, subject, body));
    }

    public List<Mail> sent() {
        return sent;
    }

    public void clear() {
        sent.clear();
    }
}
