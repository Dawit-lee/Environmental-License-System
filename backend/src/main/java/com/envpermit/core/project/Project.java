package com.envpermit.core.project;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.math.BigDecimal;
import java.time.LocalDate;

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

    protected Project() {
    }

    public Project(Long companyId) {
        this.companyId = companyId;
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
}
