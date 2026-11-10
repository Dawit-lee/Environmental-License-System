package com.envpermit.core.signup;

import static com.envpermit.core.project.ProjectAccess.userId;

import com.envpermit.core.auth.AuthController.LoginResponse;
import com.envpermit.core.auth.TokenService;
import com.envpermit.core.company.Company;
import com.envpermit.core.company.CompanyRepository;
import com.envpermit.core.signup.JoinRequest.Status;
import com.envpermit.core.user.User;
import com.envpermit.core.user.UserRepository;
import com.envpermit.notification.Mailer;
import com.envpermit.notification.Notifier;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Signup: a new company (its first user becomes the company admin) or a join request by invite code,
 * which a company admin approves (user created) or rejects. Applicants get the result by mail.
 */
@RestController
class SignupController {

    private static final Logger log = LoggerFactory.getLogger(SignupController.class);
    private static final Duration RATE_WINDOW = Duration.ofMinutes(10);
    private static final int RATE_MAX = 3; // per invite code + IP within the window

    private final CompanyRepository companies;
    private final UserRepository users;
    private final JoinRequestRepository requests;
    private final PasswordEncoder encoder;
    private final TokenService tokens;
    private final Mailer mailer;
    private final Notifier notifier;
    private final Clock clock;
    private final String baseUrl;

    SignupController(CompanyRepository companies, UserRepository users, JoinRequestRepository requests,
                     PasswordEncoder encoder, TokenService tokens, Mailer mailer, Notifier notifier, Clock clock,
                     @Value("${app.base-url}") String baseUrl) {
        this.companies = companies;
        this.users = users;
        this.requests = requests;
        this.encoder = encoder;
        this.tokens = tokens;
        this.mailer = mailer;
        this.notifier = notifier;
        this.clock = clock;
        this.baseUrl = baseUrl;
    }

    record CompanySignup(@NotBlank @Size(max = 100) String companyName, @NotBlank @Size(max = 50) String name,
                         @NotBlank @Email @Size(max = 255) String email, @NotBlank @Size(min = 8, max = 100) String password) {
    }

    record Join(@NotBlank String code, @NotBlank @Size(max = 50) String name,
                @NotBlank @Email @Size(max = 255) String email, @NotBlank @Size(min = 8, max = 100) String password) {
    }

    record Invite(String companyName) {
    }

    record Message(String message) {
    }

    record Reject(@Size(max = 500) String reason) {
    }

    record JoinRequestView(long id, String name, String email, Instant createdAt) {
    }

    @PostMapping("/api/auth/signup")
    @Transactional
    LoginResponse signupCompany(@Valid @RequestBody CompanySignup req) {
        String email = normalize(req.email());
        requireFreeEmail(email);
        Company company = companies.save(new Company(req.companyName().trim()));
        User admin = users.save(new User(email, encoder.encode(req.password()), req.name().trim(), User.Role.ADMIN,
                company.getId()));
        return new LoginResponse(tokens.issue(admin), admin);
    }

    @GetMapping("/api/auth/invite/{code}")
    Invite invite(@PathVariable String code) {
        return new Invite(company(code).getName());
    }

    @PostMapping("/api/auth/join")
    @Transactional
    ResponseEntity<Message> join(@Valid @RequestBody Join req, HttpServletRequest http) {
        Instant now = clock.instant();
        Company company = company(req.code());
        // shortcut: remote address only; behind a proxy every applicant shares one IP. Read X-Forwarded-For once deployed behind one.
        String ip = http.getRemoteAddr();
        if (requests.countByCompanyIdAndIpAndCreatedAtAfter(company.getId(), ip, now.minus(RATE_WINDOW)) >= RATE_MAX) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "신청이 너무 잦습니다. 잠시 후 다시 시도해 주세요.");
        }
        String email = normalize(req.email());
        requireFreeEmail(email);
        requests.expireCreatedBefore(now.minus(JoinRequest.TTL));
        if (requests.findFirstByEmailOrderByIdDesc(email).filter(r -> r.isOpen(now)).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 가입 신청 중인 이메일입니다.");
        }
        String name = req.name().trim();
        requests.save(new JoinRequest(company.getId(), email, name, encoder.encode(req.password()), ip, now));

        String who = "%s(%s)".formatted(name, email);
        for (User admin : users.findByCompanyIdAndRole(company.getId(), User.Role.ADMIN)) {
            notifier.notify(admin.getId(), who + " 님이 가입을 신청했습니다.", "/join-requests");
            send(admin.getEmail(), "[인허가 관리] 가입 요청: " + who, """
                    %s 님이 %s에 가입을 신청했습니다.
                    승인하거나 거절해 주세요 (14일 안에 처리하지 않으면 만료됩니다):
                    %s/join-requests
                    """.formatted(who, company.getName(), baseUrl));
        }
        return ResponseEntity.accepted().body(new Message("가입 신청을 보냈습니다. 회사 관리자가 승인하면 로그인할 수 있습니다."));
    }

    /** Open requests of the caller's company. Company admins only. */
    @GetMapping("/api/join-requests")
    List<JoinRequestView> list(@AuthenticationPrincipal Jwt jwt) {
        User admin = admin(jwt);
        return requests.findByCompanyIdAndStatusAndCreatedAtAfterOrderByIdAsc(admin.getCompanyId(), Status.PENDING,
                        clock.instant().minus(JoinRequest.TTL)).stream()
                .map(r -> new JoinRequestView(r.getId(), r.getName(), r.getEmail(), r.getCreatedAt()))
                .toList();
    }

    @PostMapping("/api/join-requests/{id}/approve")
    @Transactional
    ResponseEntity<Void> approve(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        JoinRequest r = open(jwt, id);
        requireFreeEmail(r.getEmail());
        users.save(new User(r.getEmail(), r.getPasswordHash(), r.getName(), User.Role.USER, r.getCompanyId()));
        r.decide(Status.APPROVED, null, userId(jwt), clock.instant());
        send(r.getEmail(), "[인허가 관리] 가입이 승인되었습니다", """
                %s 님, 가입이 승인되었습니다. 신청한 이메일과 비밀번호로 로그인하세요.
                %s/login
                """.formatted(r.getName(), baseUrl));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/join-requests/{id}/reject")
    @Transactional
    ResponseEntity<Void> reject(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                @Valid @RequestBody(required = false) Reject req) {
        JoinRequest r = open(jwt, id);
        String reason = req == null || req.reason() == null || req.reason().isBlank() ? null : req.reason().trim();
        r.decide(Status.REJECTED, reason, userId(jwt), clock.instant());
        send(r.getEmail(), "[인허가 관리] 가입 신청이 거절되었습니다", "%s 님의 가입 신청이 거절되었습니다.%s\n"
                .formatted(r.getName(), reason == null ? "" : "\n사유: " + reason));
        return ResponseEntity.noContent().build();
    }

    private JoinRequest open(Jwt jwt, long id) {
        User admin = admin(jwt);
        JoinRequest r = requests.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "가입 요청이 없습니다."));
        if (!r.getCompanyId().equals(admin.getCompanyId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "이 회사의 관리자만 처리할 수 있습니다.");
        }
        if (!r.isOpen(clock.instant())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 처리되었거나 만료된 요청입니다.");
        }
        return r;
    }

    private User admin(Jwt jwt) {
        return users.findById(userId(jwt))
                .filter(u -> u.getRole() == User.Role.ADMIN && u.getCompanyId() != null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "회사 관리자만 처리할 수 있습니다."));
    }

    private Company company(String code) {
        return companies.findByInviteCode(code.trim().toUpperCase())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "유효하지 않은 초대 코드입니다."));
    }

    private void requireFreeEmail(String email) {
        if (users.findByEmail(email).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 가입된 이메일입니다.");
        }
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase();
    }

    /** A mail failure must not undo the signup step; the in-app notice / screen still shows it. */
    private void send(String to, String subject, String body) {
        try {
            mailer.send(to, subject, body);
        } catch (RuntimeException e) {
            log.warn("mail to {} failed: {}", to, subject, e);
        }
    }
}
