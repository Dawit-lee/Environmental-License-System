package com.envpermit.core.project;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.math.BigDecimal;
import java.time.LocalDate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
public class Project {

    public enum FacilityType { INCINERATION, LANDFILL, BIOGAS, SEWAGE, WASTEWATER }

    public enum Status { IN_PROGRESS, COMPLETED, ON_HOLD }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long companyId;
    private String name;

    @Enumerated(EnumType.STRING)
    private FacilityType facilityType;

    private String location;
    private BigDecimal capacity;
    private String capacityUnit;
    private LocalDate startDate;
    private LocalDate expectedEndDate;

    @Enumerated(EnumType.STRING)
    private Status status;

    private Long teamId;
    private Long pmUserId;
    private Long emUserId;

    /** Derivation input, keyed by the field dictionary (com.envpermit.derivation.Fields). */
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode attributes;

    protected Project() {
    }

    public Project(Long companyId) {
        this.companyId = companyId;
        this.attributes = JsonNodeFactory.instance.objectNode();
    }

    void apply(ProjectController.ProjectRequest r) {
        name = r.name().trim();
        facilityType = r.facilityType();
        location = r.location().trim();
        capacity = r.capacity();
        capacityUnit = r.capacityUnit();
        startDate = r.startDate();
        expectedEndDate = r.expectedEndDate();
        status = r.status() == null ? Status.IN_PROGRESS : r.status();
        teamId = r.teamId();
        pmUserId = r.pmUserId();
        emUserId = r.emUserId();
    }

    public Long getId() { return id; }
    public Long getCompanyId() { return companyId; }
    public String getName() { return name; }
    public FacilityType getFacilityType() { return facilityType; }
    public String getLocation() { return location; }
    public BigDecimal getCapacity() { return capacity; }
    public String getCapacityUnit() { return capacityUnit; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getExpectedEndDate() { return expectedEndDate; }
    public Status getStatus() { return status; }
    public JsonNode getAttributes() { return attributes; }
    public Long getTeamId() { return teamId; }
    public Long getPmUserId() { return pmUserId; }
    public Long getEmUserId() { return emUserId; }

    public void setAttributes(JsonNode attributes) {
        this.attributes = attributes;
    }
}
