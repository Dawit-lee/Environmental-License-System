package com.envpermit.core.project;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, ProjectMember.Key> {
    List<ProjectMember> findByIdProjectIdOrderByIdUserId(long projectId);
    long countByIdProjectIdAndAdminTrue(long projectId);
}
