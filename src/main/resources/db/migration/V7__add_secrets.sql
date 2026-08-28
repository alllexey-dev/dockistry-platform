CREATE TABLE secrets
(
    id                 VARCHAR(16)                 NOT NULL,
    name               VARCHAR(255)                NOT NULL,
    description        TEXT,
    owner_principal_id UUID                        NOT NULL,
    encrypted_values   JSONB                       NOT NULL,
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
    id         UUID        NOT NULL,
    service_id UUID        NOT NULL,
    secret_id  VARCHAR(16) NOT NULL,
    secret_key VARCHAR(255) NOT NULL,
    CONSTRAINT pk_managed_service_secret_attachments PRIMARY KEY (id),
    CONSTRAINT uc_service_secret_attachment UNIQUE (service_id, secret_id, secret_key),
    CONSTRAINT fk_service_secret_attachments_on_service
        FOREIGN KEY (service_id) REFERENCES services (id) ON DELETE CASCADE,
    CONSTRAINT fk_service_secret_attachments_on_secret
        FOREIGN KEY (secret_id) REFERENCES secrets (id)
);

CREATE INDEX managed_service_secret_attachments_secret_id_idx
    ON managed_service_secret_attachments (secret_id);

CREATE INDEX managed_service_secret_attachments_service_id_idx
    ON managed_service_secret_attachments (service_id);
