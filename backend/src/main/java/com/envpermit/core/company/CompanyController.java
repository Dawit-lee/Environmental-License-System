package com.envpermit.core.company;

import com.envpermit.core.user.User;
import com.envpermit.core.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The caller's own company. Users never see other companies.
 * shortcut: no create/delete endpoint; companies are made at onboarding (seed/SQL) until signup exists.
 */
@RestController
public class CompanyController {

    private final CompanyRepository companies;
    private final UserRepository users;

    private final Clock clock;

    public CompanyController(CompanyRepository companies, UserRepository users, Clock clock) {
        this.companies = companies;
        this.users = users;
        this.clock = clock;
    }

    record CompanyView(long id, String name, boolean aiExtractionEnabled, Long aiExtractionChangedBy,
                       Instant aiExtractionChangedAt) {
        static CompanyView of(Company c) {
            return new CompanyView(c.getId(), c.getName(), c.isAiExtractionEnabled(), c.getAiExtractionChangedBy(),
                    c.getAiExtractionChangedAt());
        }
    }

    record AiExtractionRequest(@NotNull Boolean enabled) {
    }

    record CompanyRequest(@NotBlank @Size(max = 100) String name) {
    }

    @GetMapping("/api/company")
    CompanyView mine(@AuthenticationPrincipal Jwt jwt) {
        return CompanyView.of(myCompany(jwt));
    }

    @PutMapping("/api/company")
    @Transactional
    CompanyView rename(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CompanyRequest req) {
        requireMaster(jwt);
        Company c = myCompany(jwt);
        c.rename(req.name().trim());
        return CompanyView.of(c);
    }

    /** Documents go to an external AI (Anthropic) only while this is on. Master only; changer and time are kept. */
    @PutMapping("/api/company/ai-extraction")
    @Transactional
    CompanyView setAiExtraction(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AiExtractionRequest req) {
        requireMaster(jwt);
        Company c = myCompany(jwt);
        c.setAiExtraction(req.enabled(), Long.parseLong(jwt.getSubject()), clock.instant());
        return CompanyView.of(c);
    }

    private static void requireMaster(Jwt jwt) {
        if (!"ADMIN".equals(jwt.getClaimAsString("role"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "회사 마스터만 변경할 수 있습니다.");
        }
    }

    private Company myCompany(Jwt jwt) {
        return users.findById(Long.parseLong(jwt.getSubject()))
                .map(User::getCompanyId)
                .flatMap(companies::findById)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "소속 회사가 없습니다."));
    }
}
