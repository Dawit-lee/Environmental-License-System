package com.envpermit.core.auth;

import com.envpermit.core.user.User;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

/**
 * Issues HS256 access/refresh JWTs. The "typ" claim keeps one from being used as the other.
 * Revocation is per user: logout and disabling bump users.token_version ("ver" claim), which kills every token of that user.
 * shortcut: no per-device refresh token store or rotation-reuse detection; add a refresh_token table (jti) if single-device logout is needed.
 */
@Service
public class TokenService {

    static final String ACCESS = "access";
    static final String REFRESH = "refresh";

    private final JwtEncoder encoder;
    private final JwtDecoder refreshDecoder;
    private final Clock clock;
    private final Duration accessTtl;
    private final Duration refreshTtl;

    public TokenService(SecretKey key, Clock clock, JdbcTemplate jdbc,
                        @Value("${app.jwt.access-ttl}") Duration accessTtl,
                        @Value("${app.jwt.refresh-ttl}") Duration refreshTtl) {
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        this.refreshDecoder = SecurityConfig.decoder(key, clock, REFRESH, jdbc);
        this.clock = clock;
        this.accessTtl = accessTtl;
        this.refreshTtl = refreshTtl;
    }

    public record Tokens(String accessToken, String refreshToken) {
    }

    public Tokens issue(User user) {
        return new Tokens(encode(user, ACCESS, accessTtl), encode(user, REFRESH, refreshTtl));
    }

    /** @return user id of a valid refresh token, or throws JwtException */
    public long verifyRefresh(String refreshToken) throws JwtException {
        return Long.parseLong(refreshDecoder.decode(refreshToken).getSubject());
    }

    private String encode(User user, String type, Duration ttl) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .claim("typ", type)
                .claim("role", user.getRole().name())
                .claim("ver", user.getTokenVersion())
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
