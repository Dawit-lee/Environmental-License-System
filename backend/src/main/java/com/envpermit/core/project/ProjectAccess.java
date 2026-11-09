package com.envpermit.core.project;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Every project-scoped route goes through here. Not a member (incl. another company's user) → 403.
 * A missing project is also 403, so ids of other companies' projects are not revealed.
 */
@Component
public class ProjectAccess {

    private final ProjectMemberRepository members;

    public ProjectAccess(ProjectMemberRepository members) {
        this.members = members;
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

    public ProjectMember admin(long projectId, Jwt jwt) {
        ProjectMember m = member(projectId, jwt);
        if (!m.isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "프로젝트 어드민만 할 수 있습니다.");
        }
        return m;
    }
}
