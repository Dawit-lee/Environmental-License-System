CREATE TABLE company (
    id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(100) NOT NULL
);

-- shortcut: nullable until signup/onboarding exists; a user without a company cannot create projects.
ALTER TABLE users ADD COLUMN company_id BIGINT REFERENCES company (id);

CREATE TABLE project (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id        BIGINT       NOT NULL REFERENCES company (id),
    name              VARCHAR(200) NOT NULL,
    facility_type     VARCHAR(20)  NOT NULL CHECK (facility_type IN ('INCINERATION', 'LANDFILL', 'BIOGAS', 'SEWAGE', 'WASTEWATER')),
    location          VARCHAR(300) NOT NULL,
    capacity          NUMERIC(12, 2),
    capacity_unit     VARCHAR(20),
    start_date        DATE,
    expected_end_date DATE,
    status            VARCHAR(20)  NOT NULL CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'ON_HOLD'))
);
CREATE INDEX project_company_idx ON project (company_id);

CREATE TABLE project_member (
    project_id       BIGINT  NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    user_id          BIGINT  NOT NULL REFERENCES users (id),
    is_admin         BOOLEAN NOT NULL DEFAULT FALSE,
    can_self_approve BOOLEAN NOT NULL DEFAULT FALSE,
    can_bulk_approve BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (project_id, user_id)
);
CREATE INDEX project_member_user_idx ON project_member (user_id);

CREATE TABLE permit_item (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id       BIGINT       NOT NULL REFERENCES project (id) ON DELETE CASCADE,
    name             VARCHAR(200) NOT NULL,
    category         VARCHAR(20)  NOT NULL CHECK (category IN ('LOCATION', 'FACILITY', 'CONSTRUCTION', 'ELECTRICAL', 'ENVIRONMENT')),
    regulation       VARCHAR(300),
    regulation_url   VARCHAR(500),
    authority        VARCHAR(100),
    authority_url    VARCHAR(500),
    reason           TEXT,
    approval_date    VARCHAR(100), -- free text timing, e.g. '착공 30일 전'
    application_date DATE,
    acquisition_date DATE,
    expiry_date      DATE,
    status           VARCHAR(20)  NOT NULL CHECK (status IN ('NOT_APPLIED', 'IN_PROGRESS', 'COMPLETED', 'NEAR_EXPIRY', 'EXPIRED')),
    next_action      TEXT,
    next_action_url  VARCHAR(500)
);
CREATE INDEX permit_item_project_idx ON permit_item (project_id);

CREATE TABLE permit_status_history (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    permit_item_id BIGINT      NOT NULL REFERENCES permit_item (id) ON DELETE CASCADE,
    from_status    VARCHAR(20), -- null on creation
    to_status      VARCHAR(20) NOT NULL,
    changed_by     BIGINT      NOT NULL REFERENCES users (id),
    changed_at     TIMESTAMPTZ NOT NULL
);
CREATE INDEX permit_status_history_item_idx ON permit_status_history (permit_item_id);
