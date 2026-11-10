package com.envpermit.review;

/**
 * What a suggestion turns into when approved. Each kind has exactly one {@link SuggestionApplier}.
 * PERMIT_ITEM: auto-derivation (Applier in permit-derivation/02). Later: DERIVATION_RULE (company/project-less), OBLIGATION_REVISION (version diff).
 */
public enum Kind {
    OBLIGATION, PERMIT_ITEM
}
