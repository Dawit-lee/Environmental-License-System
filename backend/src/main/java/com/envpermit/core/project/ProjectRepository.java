package com.envpermit.core.project;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    @Query("select p from Project p where p.id in "
            + "(select m.id.projectId from ProjectMember m where m.id.userId = ?1) order by p.id")
    List<Project> findByMember(long userId);

    List<Project> findByCompanyIdOrderById(long companyId);
}
