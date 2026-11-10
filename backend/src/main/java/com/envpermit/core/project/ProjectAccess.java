package com.envpermit.core.project;

import com.envpermit.core.user.User;
import com.envpermit.core.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Every project-scoped route goes through here. Not a member (incl. another company's user) → 403;
 * plain reads use {@link #viewer}, which also lets in the rest of the company.
 * A missing project is also 403, so ids of other companies' projects are not revealed.
 */
@Component
public class ProjectAccess {

    private final ProjectMemberRepository members;
    private final ProjectRepository projects;
    private final UserRepository users;

    public ProjectAccess(ProjectMemberRepository members, ProjectRepository projects, UserRepository users) {
        this.members = members;
        this.projects = projects;
        this.users = users;
    }

    public static long userId(Jwt jwt) {
        return Long.parseLong(jwt.getSubject());
    }

    public ProjectMember member(long projectId, Jwt jwt) {
        return member(projectId, userId(jwt));
    }

    public ProjectMember member(long projectId, long userId) {
        return members.findById(new ProjectMember.Key(projectId, userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
    }

    /** Read-only access: members, or anyone in the project's company (company-wide project list). */
    public void viewer(long projectId, Jwt jwt) {
        if (members.existsById(new ProjectMember.Key(projectId, userId(jwt)))) {
            return;
        }
        Long company = users.findById(userId(jwt)).map(User::getCompanyId).orElse(null);
        if (company == null || projects.findById(projectId).filter(p -> company.equals(p.getCompanyId())).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    public ProjectMember admin(long projectId, Jwt jwt) {
        ProjectMember m = member(projectId, jwt);
        if (!m.isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "프로젝트 어드민만 할 수 있습니다.");
        }
        return m;
    }
}
