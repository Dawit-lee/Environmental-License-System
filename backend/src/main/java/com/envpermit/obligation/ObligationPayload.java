package com.envpermit.obligation;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/** OBLIGATION suggestion payload (design §3.3), snake_case like the AI output. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ObligationPayload(
        @Size(max = 200) String citationRaw,
        @Size(max = 100) String citationKey,
        boolean citationSynthetic,
        @NotBlank String requirementText,
        @NotBlank @Size(max = 200) String summary,
        @NotNull Type type,
        @NotNull Category category,
        @Size(max = 200) String responsibleParty,
        @NotNull Frequency frequency,
        @Size(max = 200) String frequencyText,
        @Size(max = 200) String periodText,
        List<@NotBlank String> legalBasis,
        @Positive Integer pageNo,
        String sourceQuote,
        QuoteCheck quoteCheck) {

    public enum Type { MEASURE_SURVEY, REPORT_SUBMIT, FACILITY_OPERATION, RECORD_KEEP, OTHER }

    public enum QuoteCheck { VERIFIED, MISMATCH, UNVERIFIABLE }

    /** 환경영향평가 평가항목 (design §3.5). The 분야 is derived from the code, not stored. */
    public enum Category {
        FLORA_FAUNA, NATURAL_ASSET,
        WEATHER, AIR_QUALITY, ODOR, GHG,
        WATER_QUALITY, HYDROLOGY, MARINE,
        LAND_USE, SOIL, TOPOGRAPHY,
        RESOURCE_CIRCULATION, NOISE_VIBRATION, RECREATION_LANDSCAPE, PUBLIC_HEALTH, RADIO_INTERFERENCE, SUNLIGHT,
        POPULATION, HOUSING, INDUSTRY,
        OTHER
    }
}
