-- Files attached to a permit item. Bytes live in object storage under storage_key.
CREATE TABLE document (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    permit_item_id BIGINT       NOT NULL REFERENCES permit_item (id) ON DELETE CASCADE,
    file_name      VARCHAR(255) NOT NULL,
    content_type   VARCHAR(100) NOT NULL,
    size_bytes     BIGINT       NOT NULL,
    sha256         VARCHAR(64)  NOT NULL, -- hex; finds re-uploads of the same file
    storage_key    VARCHAR(300) NOT NULL UNIQUE,
    uploaded_by    BIGINT       NOT NULL REFERENCES users (id),
    uploaded_at    TIMESTAMPTZ  NOT NULL
);
CREATE INDEX document_permit_item_idx ON document (permit_item_id);
CREATE INDEX document_sha256_idx ON document (sha256);
