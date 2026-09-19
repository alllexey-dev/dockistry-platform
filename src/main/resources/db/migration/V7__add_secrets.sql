CREATE TABLE secrets
(
    id                 VARCHAR(16)                 NOT NULL,
    name               VARCHAR(255)                NOT NULL,
    description        TEXT,
    owner_principal_id UUID                        NOT NULL,
    version            BIGINT                      NOT NULL,
    created_at         TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at         TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_secrets PRIMARY KEY (id),
    CONSTRAINT ck_secrets_id_format CHECK (id ~ '^sec-[A-Za-z]{12}$'),
    CONSTRAINT uc_secrets_owner_name UNIQUE (owner_principal_id, name),
    CONSTRAINT fk_secrets_on_owner_principal
        FOREIGN KEY (owner_principal_id) REFERENCES iam_principals (id)
);

CREATE INDEX secrets_owner_principal_id_idx
    ON secrets (owner_principal_id);

CREATE TABLE secret_versions
(
    id               VARCHAR(16)                 NOT NULL,
    secret_id        VARCHAR(16)                 NOT NULL,
    encrypted_values JSONB                       NOT NULL,
    created_at       TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_secret_versions PRIMARY KEY (id),
    CONSTRAINT ck_secret_versions_id_format CHECK (id ~ '^ver-[A-Za-z0-9]{12}$'),
    -- Provides the candidate key required by the composite attachment FK below.
    -- The version ID remains globally unique through the primary key.
    CONSTRAINT uc_secret_versions_secret_id_id UNIQUE (secret_id, id),
    CONSTRAINT fk_secret_versions_on_secret
        FOREIGN KEY (secret_id) REFERENCES secrets (id) ON DELETE CASCADE
);

CREATE INDEX secret_versions_secret_id_created_at_idx
    ON secret_versions (secret_id, created_at DESC, id DESC);

CREATE TABLE project_secret_delegations
(
    id         UUID                        NOT NULL,
    secret_id  VARCHAR(16)                 NOT NULL,
    project_id UUID                        NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_project_secret_delegations PRIMARY KEY (id),
    CONSTRAINT uc_project_secret_delegations_secret_project UNIQUE (secret_id, project_id),
    CONSTRAINT fk_project_secret_delegations_on_secret
        FOREIGN KEY (secret_id) REFERENCES secrets (id),
    CONSTRAINT fk_project_secret_delegations_on_project
        FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE
);

CREATE INDEX project_secret_delegations_project_id_idx
    ON project_secret_delegations (project_id);

CREATE TABLE managed_service_secret_attachments
(
    id             UUID        NOT NULL,
    service_id     UUID        NOT NULL,
    secret_id      VARCHAR(16) NOT NULL,
    secret_key     VARCHAR(255) NOT NULL,
    secret_version VARCHAR(16),
    CONSTRAINT pk_managed_service_secret_attachments PRIMARY KEY (id),
    CONSTRAINT uc_service_secret_attachment_reference
        UNIQUE (service_id, secret_id, secret_key, secret_version),
    CONSTRAINT fk_service_secret_attachments_on_service
        FOREIGN KEY (service_id) REFERENCES services (id) ON DELETE CASCADE,
    -- Preserves logical Secret integrity for every attachment, including latest
    -- references whose NULL version skips the composite FK under MATCH SIMPLE.
    CONSTRAINT fk_service_secret_attachments_on_secret
        FOREIGN KEY (secret_id) REFERENCES secrets (id) ON DELETE CASCADE,
    -- For pinned references, also guarantees that the selected version belongs
    -- to the declared Secret rather than merely proving that both IDs exist.
    CONSTRAINT fk_service_secret_attachments_on_secret_version
        FOREIGN KEY (secret_id, secret_version)
            REFERENCES secret_versions (secret_id, id) ON DELETE CASCADE
);

CREATE INDEX managed_service_secret_attachments_secret_id_idx
    ON managed_service_secret_attachments (secret_id);

CREATE INDEX managed_service_secret_attachments_service_id_idx
    ON managed_service_secret_attachments (service_id);

CREATE TABLE service_secret_warnings
(
    id             UUID                        NOT NULL,
    service_id     UUID                        NOT NULL,
    secret_id      VARCHAR(16)                 NOT NULL,
    secret_key     VARCHAR(255)                NOT NULL,
    secret_version VARCHAR(16),
    warning_type   VARCHAR(32)                 NOT NULL,
    created_at     TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    resolved_at    TIMESTAMP WITHOUT TIME ZONE,
    CONSTRAINT pk_service_secret_warnings PRIMARY KEY (id),
    CONSTRAINT ck_service_secret_warnings_type
        CHECK (warning_type IN ('OUTDATED', 'DELETED')),
    CONSTRAINT fk_service_secret_warnings_on_service
        FOREIGN KEY (service_id) REFERENCES services (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX ux_service_secret_warnings_active_target
    ON service_secret_warnings
        (service_id, warning_type, secret_id, secret_key, secret_version)
    WHERE resolved_at IS NULL;

CREATE INDEX service_secret_warnings_service_id_idx
    ON service_secret_warnings (service_id);
