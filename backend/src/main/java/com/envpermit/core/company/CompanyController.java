package com.envpermit.core.company;

import com.envpermit.core.user.User;
import com.envpermit.core.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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

    public CompanyController(CompanyRepository companies, UserRepository users) {
        this.companies = companies;
        this.users = users;
    }

    record CompanyView(long id, String name) {
        static CompanyView of(Company c) {
            return new CompanyView(c.getId(), c.getName());
        }
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
        if (!"ADMIN".equals(jwt.getClaimAsString("role"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        Company c = myCompany(jwt);
        c.rename(req.name().trim());
        return CompanyView.of(c);
    }

    private Company myCompany(Jwt jwt) {
        return users.findById(Long.parseLong(jwt.getSubject()))
                .map(User::getCompanyId)
                .flatMap(companies::findById)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "소속 회사가 없습니다."));
    }
}
