package com.envpermit.core.company;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompanyRepository extends JpaRepository<Company, Long> {
    Optional<Company> findByInviteCode(String inviteCode);
}
