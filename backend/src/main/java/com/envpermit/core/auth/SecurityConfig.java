package com.envpermit.core.auth;

import com.envpermit.core.ApiErrors;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        // same {code, message} body as core.ApiErrors; the bearer handlers keep the WWW-Authenticate header
        BearerTokenAuthenticationEntryPoint bearer = new BearerTokenAuthenticationEntryPoint();
        AuthenticationEntryPoint unauthorized = (req, res, ex) -> {
            bearer.commence(req, res, ex);
            ApiErrors.write(res, HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        };
        BearerTokenAccessDeniedHandler denied = new BearerTokenAccessDeniedHandler();
        AccessDeniedHandler forbidden = (req, res, ex) -> {
            denied.handle(req, res, ex);
            ApiErrors.write(res, HttpStatus.FORBIDDEN, "권한이 없습니다.");
        };
        return http
                .csrf(c -> c.disable()) // stateless bearer tokens, no cookies
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/auth/**", "/error").permitAll()
                        // partner share links and personal .ics: the token is the credential, and GET only
                        .requestMatchers(HttpMethod.GET, "/share/**", "/api/me/calendar.ics").permitAll()
                        // Google redirects the browser here without our token; the one-time state names the user
                        .requestMatchers("/api/me/google-calendar/callback").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden))
                .oauth2ResourceServer(o -> o.jwt(j -> {}).authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden))
                .build();
    }

    @Bean
    SecretKey jwtKey(@Value("${app.jwt.secret}") String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("JWT_SECRET must be at least 32 bytes");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    /** Validates access tokens on protected endpoints. */
    @Bean
    JwtDecoder jwtDecoder(SecretKey key, Clock clock) {
        return decoder(key, clock, TokenService.ACCESS);
    }

    static NimbusJwtDecoder decoder(SecretKey key, Clock clock, String type) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        JwtTimestampValidator timestamps = new JwtTimestampValidator();
        timestamps.setClock(clock);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                timestamps, new JwtClaimValidator<String>("typ", type::equals)));
        return decoder;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
