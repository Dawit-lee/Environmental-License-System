package com.envpermit.core.auth;

import com.envpermit.core.signup.JoinRequest;
import com.envpermit.core.signup.JoinRequestRepository;
import com.envpermit.core.user.User;
import com.envpermit.core.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

    private static final ResponseEntity<Object> BAD_LOGIN = error("이메일 또는 비밀번호가 올바르지 않습니다.");
    private static final ResponseEntity<Object> BAD_REFRESH = error("다시 로그인해 주세요.");

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokens;
    private final JoinRequestRepository joinRequests;
    private final Clock clock;

    public AuthController(UserRepository users, PasswordEncoder passwordEncoder, TokenService tokens,
                          JoinRequestRepository joinRequests, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.joinRequests = joinRequests;
        this.clock = clock;
    }

    record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    record RefreshRequest(@NotBlank String refreshToken) {
    }

    public record LoginResponse(String accessToken, String refreshToken, Me user) {
        public LoginResponse(TokenService.Tokens t, User u) {
            this(t.accessToken(), t.refreshToken(), Me.of(u));
        }
    }

    public record Me(long id, String email, String name, String role) {
        static Me of(User u) {
            return new Me(u.getId(), u.getEmail(), u.getName(), u.getRole().name());
        }
    }

    @PostMapping("/api/auth/login")
    ResponseEntity<Object> login(@Valid @RequestBody LoginRequest req) {
        String email = req.email().trim().toLowerCase();
        Optional<User> user = users.findByEmail(email)
                .filter(u -> passwordEncoder.matches(req.password(), u.getPasswordHash()));
        return user.<ResponseEntity<Object>>map(u -> ResponseEntity.ok(new LoginResponse(tokens.issue(u), u)))
                .orElseGet(() -> joinRequestLogin(email, req.password()));
    }

    /** An applicant with the right password learns why they can't log in yet; anyone else gets the generic error. */
    private ResponseEntity<Object> joinRequestLogin(String email, String password) {
        return joinRequests.findFirstByEmailOrderByIdDesc(email)
                .filter(r -> passwordEncoder.matches(password, r.getPasswordHash()))
                .map(r -> r.isOpen(clock.instant()) ? error("가입 승인 대기 중입니다. 회사 관리자가 승인하면 로그인할 수 있습니다.")
                        : r.getStatus() == JoinRequest.Status.REJECTED ? error("가입 신청이 거절되었습니다.")
                        : BAD_LOGIN)
                .orElse(BAD_LOGIN);
    }

    @PostMapping("/api/auth/refresh")
    ResponseEntity<Object> refresh(@Valid @RequestBody RefreshRequest req) {
        try {
            Optional<User> user = users.findById(tokens.verifyRefresh(req.refreshToken()));
            return user.<ResponseEntity<Object>>map(u -> ResponseEntity.ok(new LoginResponse(tokens.issue(u), u)))
                    .orElse(BAD_REFRESH);
        } catch (JwtException e) {
            return BAD_REFRESH;
        }
    }

    @GetMapping("/api/me")
    ResponseEntity<Me> me(@AuthenticationPrincipal Jwt jwt) {
        return users.findById(Long.parseLong(jwt.getSubject()))
                .map(u -> ResponseEntity.ok(Me.of(u)))
                .orElse(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
    }

    private static ResponseEntity<Object> error(String message) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("message", message));
    }
}
