package com.envpermit.schedule.google;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Google OAuth client and the refresh-token key, from env vars only. Any of the three missing = feature off
 * (API 404, UI hides). A key that is set but not base64 of 32 bytes fails startup, like JWT_SECRET.
 */
@Component
public class GoogleSettings {

    static final String SCOPE = "https://www.googleapis.com/auth/calendar.app.created";
    private static final SecureRandom RANDOM = new SecureRandom();

    final String clientId;
    final String clientSecret;
    final String redirectUri;
    private final SecretKey key;

    GoogleSettings(@Value("${app.google.client-id:}") String clientId,
                   @Value("${app.google.client-secret:}") String clientSecret,
                   @Value("${app.google.token-enc-key:}") String encKey,
                   @Value("${app.base-url}") String baseUrl) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = baseUrl + "/api/me/google-calendar/callback";
        if (encKey.isBlank()) {
            this.key = null;
        } else {
            byte[] bytes = Base64.getDecoder().decode(encKey.trim());
            if (bytes.length != 32) {
                throw new IllegalStateException("GOOGLE_TOKEN_ENC_KEY must be base64 of 32 bytes (openssl rand -base64 32)");
            }
            this.key = new SecretKeySpec(bytes, "AES");
        }
    }

    public boolean enabled() {
        return !clientId.isBlank() && !clientSecret.isBlank() && key != null;
    }

    /** AES-256-GCM; stored as base64(12-byte IV ‖ ciphertext+tag). */
    String encrypt(String plain) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(12 + ct.length).put(iv).put(ct).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    String decrypt(String stored) {
        try {
            byte[] all = Base64.getDecoder().decode(stored);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("저장된 Google 토큰을 풀 수 없습니다(키 변경?). 다시 연결해 주세요.", e);
        }
    }
}
