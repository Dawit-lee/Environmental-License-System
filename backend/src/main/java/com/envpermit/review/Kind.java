package com.envpermit.review;

/**
 * What a suggestion turns into when approved. Each kind has exactly one {@link SuggestionApplier}.
 * Later: PERMIT_ITEM (derivation), DERIVATION_RULE (company/project-less), OBLIGATION_REVISION (version diff).
 */
public enum Kind {
    OBLIGATION
}
