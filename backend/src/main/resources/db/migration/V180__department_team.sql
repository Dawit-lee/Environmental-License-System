-- Company org chart: department > team. A user and a project each belong to at most one team.
CREATE TABLE department (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    company_id BIGINT       NOT NULL REFERENCES company (id) ON DELETE CASCADE,
    name       VARCHAR(100) NOT NULL,
    UNIQUE (company_id, name)
);

CREATE TABLE team (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    department_id BIGINT       NOT NULL REFERENCES department (id) ON DELETE CASCADE,
    name          VARCHAR(100) NOT NULL,
    UNIQUE (department_id, name)
);

-- deleting a team only unassigns its users and projects
ALTER TABLE users ADD COLUMN team_id BIGINT REFERENCES team (id) ON DELETE SET NULL;

ALTER TABLE project
    ADD COLUMN team_id    BIGINT REFERENCES team (id) ON DELETE SET NULL,
    ADD COLUMN pm_user_id BIGINT REFERENCES users (id) ON DELETE SET NULL,
    ADD COLUMN em_user_id BIGINT REFERENCES users (id) ON DELETE SET NULL;
