CREATE TABLE services
(
    id                   UUID                        NOT NULL,
    project_id           UUID                        NOT NULL,
    name                 VARCHAR(40)                 NOT NULL,
    image_repository     VARCHAR(255)                NOT NULL,
    image_tag            VARCHAR(128)                NOT NULL,
    command              JSONB,
    args                 JSONB,
    env                  JSONB                       NOT NULL,
    endpoints            JSONB                       NOT NULL,
    volumes              JSONB                       NOT NULL,
    restart_policy       VARCHAR(255)                NOT NULL,
    restart_max_retries  INTEGER,
    memory_limit_bytes   BIGINT,
    cpu_limit_nano_cpus  BIGINT,
    desired_state        VARCHAR(255)                NOT NULL,
    lifecycle_status     VARCHAR(255)                NOT NULL,
    deletion_remove_volumes          BOOLEAN,
    deletion_requested_at            TIMESTAMP WITHOUT TIME ZONE,
    deletion_last_cleanup_attempt_at TIMESTAMP WITHOUT TIME ZONE,
    deletion_cleanup_error_message   TEXT,
    version              BIGINT                      NOT NULL,
    created_at           TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at           TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_services PRIMARY KEY (id),
    CONSTRAINT uc_services_project_name UNIQUE (project_id, name),
    CONSTRAINT ck_services_deletion_context CHECK (
        (
            lifecycle_status = 'DELETING'
            AND deletion_remove_volumes IS NOT NULL
            AND deletion_requested_at IS NOT NULL
        )
        OR
        (
            lifecycle_status <> 'DELETING'
            AND deletion_remove_volumes IS NULL
            AND deletion_requested_at IS NULL
            AND deletion_last_cleanup_attempt_at IS NULL
            AND deletion_cleanup_error_message IS NULL
        )
    )
);

CREATE INDEX services_project_id_idx
    ON services (project_id);

ALTER TABLE services
    ADD CONSTRAINT FK_SERVICES_ON_PROJECT FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE RESTRICT;

CREATE TABLE service_deployments
(
    id                UUID                        NOT NULL,
    service_id        UUID                        NOT NULL,
    container_id      VARCHAR(255),
    image_repository  VARCHAR(255)                NOT NULL,
    image_tag         VARCHAR(128)                NOT NULL,
    config_snapshot   JSONB                       NOT NULL,
    service_version   BIGINT                      NOT NULL,
    status            VARCHAR(255)                NOT NULL,
    error_message     TEXT,
    created_at        TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at        TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_service_deployments PRIMARY KEY (id)
);

CREATE INDEX service_deployments_service_id_idx
    ON service_deployments (service_id);

ALTER TABLE service_deployments
    ADD CONSTRAINT FK_SERVICE_DEPLOYMENTS_ON_SERVICE
        FOREIGN KEY (service_id) REFERENCES services (id) ON DELETE CASCADE;

CREATE UNIQUE INDEX uc_service_deployments_active
    ON service_deployments (service_id)
    WHERE status IN ('IN_PROCESS', 'CANCELING');
