package com.envpermit.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SmtpMailerTest {

    @ParameterizedTest
    @CsvSource({
            "dev@envpermit.local, true",
            "other@ENVPERMIT.LOCAL, true",
            "a@foo.test, true",
            "a@example.com, true",
            "a@mail.example.com, true",
            "a@host.invalid, true",
            "a@localhost, true",
            "someone+tag@gmail.com, false",
            "kim@company.co.kr, false",
            "x@localhost.co.kr, false",
    })
    void reservedDomainsAreNotSent(String to, boolean expected) {
        assertThat(SmtpMailer.undeliverable(to)).isEqualTo(expected);
    }
}
