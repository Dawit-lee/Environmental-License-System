package com.envpermit.core.project;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;

/** Project membership + approval flags. Creator gets admin and both flags; others start with none. */
@Entity
public class ProjectMember {

    @Embeddable
    public record Key(Long projectId, Long userId) {
    }

    @EmbeddedId
    private Key id;

    @Column(name = "is_admin")
    private boolean admin;
    private boolean canSelfApprove;
    private boolean canBulkApprove;

    protected ProjectMember() {
    }

    static ProjectMember creator(long projectId, long userId) {
        ProjectMember m = new ProjectMember();
        m.id = new Key(projectId, userId);
        m.admin = m.canSelfApprove = m.canBulkApprove = true;
        return m;
    }

    static ProjectMember regular(long projectId, long userId) {
        ProjectMember m = new ProjectMember();
        m.id = new Key(projectId, userId);
        return m;
    }

    void update(Boolean admin, Boolean canSelfApprove, Boolean canBulkApprove) {
        if (admin != null) this.admin = admin;
        if (canSelfApprove != null) this.canSelfApprove = canSelfApprove;
        if (canBulkApprove != null) this.canBulkApprove = canBulkApprove;
    }

    public long getProjectId() { return id.projectId(); }
    public long getUserId() { return id.userId(); }
    public boolean isAdmin() { return admin; }
    public boolean canSelfApprove() { return canSelfApprove; }
    public boolean canBulkApprove() { return canBulkApprove; }
}
