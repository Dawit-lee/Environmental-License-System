-- Daily amendment batch (law-monitoring/02). One row per new 현행 version of a watched law.
CREATE TABLE law_amendment (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    law_catalog_id BIGINT      NOT NULL REFERENCES law_catalog (id),
    mst            BIGINT      NOT NULL UNIQUE, -- 법령일련번호; a re-run never stores a version twice
    promulgated_on DATE,
    enforced_on    DATE,
    revision_type  VARCHAR(20) NOT NULL, -- 제개정구분명 as given (일부개정 / 타법개정 / 일괄개정 / 전부개정 / ...)
    reason         TEXT,                 -- 제개정이유
    old_new_raw    JSONB,                -- oldAndNew body, OC-masked; null = fetch failed, retried at the end of each run
    detected_on    DATE        NOT NULL  -- the regDt it was found on
);
CREATE INDEX law_amendment_law_idx ON law_amendment (law_catalog_id);

CREATE TABLE law_amendment_article (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    amendment_id        BIGINT      NOT NULL REFERENCES law_amendment (id) ON DELETE CASCADE,
    article_no          VARCHAR(20) NOT NULL, -- 제10조 / 제10조의2
    jo_code             CHAR(6)     NOT NULL, -- 001000 / 001002, compare links via ArticleNo.joCode
    change_reason       VARCHAR(20),          -- 조문제개정유형
    article_enforced_on DATE
);
CREATE INDEX law_amendment_article_amendment_idx ON law_amendment_article (amendment_id);

CREATE TABLE batch_cursor (
    job_name             VARCHAR(50) PRIMARY KEY,
    last_success_date    DATE        NOT NULL,
    consecutive_failures INT         NOT NULL DEFAULT 0
);
