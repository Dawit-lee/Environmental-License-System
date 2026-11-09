CREATE TABLE law_catalog (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    law_id              VARCHAR(10)  NOT NULL UNIQUE, -- 법제처 법령ID, stable across amendments
    name                VARCHAR(200) NOT NULL,
    kind                VARCHAR(10)  NOT NULL CHECK (kind IN ('LAW', 'DECREE', 'RULE', 'ORDINANCE')),
    short_name          VARCHAR(100),
    parent_id           BIGINT REFERENCES law_catalog (id), -- 시행령·시행규칙 → 법률, by name convention
    last_mst            BIGINT,
    last_promulgated_on DATE,
    last_enforced_on    DATE,
    repealed            BOOLEAN      NOT NULL DEFAULT FALSE,
    source              VARCHAR(12)  NOT NULL CHECK (source IN ('SEED', 'USER_SEARCH'))
);
CREATE INDEX law_catalog_parent_idx ON law_catalog (parent_id);

CREATE TABLE permit_law_link (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    permit_item_id BIGINT      NOT NULL REFERENCES permit_item (id) ON DELETE CASCADE,
    law_catalog_id BIGINT      NOT NULL REFERENCES law_catalog (id),
    article_no     VARCHAR(20), -- normalized: 제29조 / 제29조의2 / 별표3
    source         VARCHAR(12) NOT NULL CHECK (source IN ('MANUAL', 'AI_EXTRACTED', 'DERIVED')),
    created_at     TIMESTAMPTZ NOT NULL,
    UNIQUE NULLS NOT DISTINCT (permit_item_id, law_catalog_id, article_no)
);
CREATE INDEX permit_law_link_law_idx ON permit_law_link (law_catalog_id);
