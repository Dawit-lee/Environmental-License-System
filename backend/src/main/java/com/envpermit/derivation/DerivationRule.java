package com.envpermit.derivation;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.util.List;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One immutable rule version (design §3.1). Written only by migrations here; the rule Applier comes later (D4). */
@Entity
@Immutable
public class DerivationRule {

    @Id
    private Long id;

    private String ruleKey;
    private int version;
    private String status;
    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> facilityTypes;
    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> projectKinds;
    private String permitName;
    private String field;
    private String category;
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode condition;
    private String criterionText;
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode lawRefs;
    private String legalBasisText;
    private String approvalStage;
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode requiredBy;
    private String note;

    protected DerivationRule() {
    }

    /** Empty list = every facility type / project kind. */
    boolean appliesTo(String facilityType, String projectKind) {
        return (facilityTypes.isEmpty() || facilityTypes.contains(facilityType))
                && (projectKinds.isEmpty() || projectKinds.contains(projectKind));
    }

    public Long getId() { return id; }
    public String getRuleKey() { return ruleKey; }
    public int getVersion() { return version; }
    public List<String> getFacilityTypes() { return facilityTypes; }
    public List<String> getProjectKinds() { return projectKinds; }
    public String getPermitName() { return permitName; }
    public String getField() { return field; }
    public String getCategory() { return category; }
    public JsonNode getCondition() { return condition; }
    public String getCriterionText() { return criterionText; }
    public JsonNode getLawRefs() { return lawRefs; }
    public String getLegalBasisText() { return legalBasisText; }
    public String getApprovalStage() { return approvalStage; }
    public JsonNode getRequiredBy() { return requiredBy; }
    public String getNote() { return note; }
}
