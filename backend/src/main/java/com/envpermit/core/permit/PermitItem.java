package com.envpermit.core.permit;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.LocalDate;

/** One permit (인허가 항목) of a project. Fields mirror frontend/src/types PermitItem. */
@Entity
public class PermitItem {

    public enum Category { LOCATION, FACILITY, CONSTRUCTION, ELECTRICAL, ENVIRONMENT }

    /** 미신청 / 진행중 / 취득완료 / 만료임박 / 만료 */
    public enum Status { NOT_APPLIED, IN_PROGRESS, COMPLETED, NEAR_EXPIRY, EXPIRED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long projectId;
    private String name;

    @Enumerated(EnumType.STRING)
    private Category category;

    private String regulation;
    private String regulationUrl;
    private String authority;
    private String authorityUrl;
    private String reason;
    private String approvalDate;
    private LocalDate applicationDate;
    private LocalDate acquisitionDate;
    private LocalDate expiryDate;

    @Enumerated(EnumType.STRING)
    private Status status;

    private String nextAction;
    private String nextActionUrl;

    protected PermitItem() {
    }

    PermitItem(Long projectId) {
        this.projectId = projectId;
    }

    void apply(PermitController.PermitRequest r) {
        name = r.name().trim();
        category = r.category();
        regulation = r.regulation();
        regulationUrl = r.regulationUrl();
        authority = r.authority();
        authorityUrl = r.authorityUrl();
        reason = r.reason();
        approvalDate = r.approvalDate();
        applicationDate = r.applicationDate();
        acquisitionDate = r.acquisitionDate();
        expiryDate = r.expiryDate();
        status = r.status();
        nextAction = r.nextAction();
        nextActionUrl = r.nextActionUrl();
    }

    public Long getId() { return id; }
    public Long getProjectId() { return projectId; }
    public String getName() { return name; }
    public Category getCategory() { return category; }
    public String getRegulation() { return regulation; }
    public String getRegulationUrl() { return regulationUrl; }
    public String getAuthority() { return authority; }
    public String getAuthorityUrl() { return authorityUrl; }
    public String getReason() { return reason; }
    public String getApprovalDate() { return approvalDate; }
    public LocalDate getApplicationDate() { return applicationDate; }
    public LocalDate getAcquisitionDate() { return acquisitionDate; }
    public LocalDate getExpiryDate() { return expiryDate; }
    public Status getStatus() { return status; }
    public String getNextAction() { return nextAction; }
    public String getNextActionUrl() { return nextActionUrl; }
}
