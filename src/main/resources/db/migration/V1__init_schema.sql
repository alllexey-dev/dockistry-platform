CREATE TABLE group_members
(
    group_id UUID NOT NULL,
    user_id  UUID NOT NULL,
    CONSTRAINT pk_group_members PRIMARY KEY (group_id, user_id)
);

CREATE TABLE groups
(
    id   UUID         NOT NULL,
    name VARCHAR(255) NOT NULL,
    CONSTRAINT pk_groups PRIMARY KEY (id)
);

CREATE TABLE iam_principals
(
    id             UUID         NOT NULL,
    principal_type VARCHAR(255) NOT NULL,
    CONSTRAINT pk_iam_principals PRIMARY KEY (id)
);

CREATE TABLE iam_scopes
(
    id         UUID         NOT NULL,
    scope_type VARCHAR(255) NOT NULL,
    CONSTRAINT pk_iam_scopes PRIMARY KEY (id)
);

CREATE UNIQUE INDEX iam_scopes_global_unique_idx
    ON iam_scopes (scope_type)
    WHERE scope_type = 'GLOBAL';

CREATE TABLE permissions
(
    id             UUID         NOT NULL,
    permission_key VARCHAR(255) NOT NULL,
    description    VARCHAR(255) NOT NULL,
    CONSTRAINT pk_permissions PRIMARY KEY (id)
);

CREATE TABLE projects
(
    id                 UUID                        NOT NULL,
    name               VARCHAR(255)                NOT NULL,
    slug               VARCHAR(255)                NOT NULL,
    description        VARCHAR(255),
    owner_principal_id UUID                        NOT NULL,
    status             VARCHAR(255)                NOT NULL,
    created_at         TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    scope_id           UUID                        NOT NULL,
    CONSTRAINT pk_projects PRIMARY KEY (id)
);

CREATE TABLE refresh_tokens
(
    id         UUID                        NOT NULL,
    issued_at  TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITHOUT TIME ZONE,
    version    BIGINT,
    user_id    UUID                        NOT NULL,
    CONSTRAINT pk_refresh_tokens PRIMARY KEY (id)
);

CREATE TABLE role_bindings
(
    id                   UUID NOT NULL,
    subject_principal_id UUID NOT NULL,
    scope_id             UUID NOT NULL,
    role_id              UUID NOT NULL,
    CONSTRAINT pk_role_bindings PRIMARY KEY (id)
);

CREATE TABLE role_permissions
(
    permission_id UUID NOT NULL,
    role_id       UUID NOT NULL,
    CONSTRAINT pk_role_permissions PRIMARY KEY (permission_id, role_id)
);

CREATE TABLE roles
(
    id          UUID         NOT NULL,
    name        VARCHAR(255) NOT NULL,
    description VARCHAR(255) NOT NULL,
    is_system   BOOLEAN      NOT NULL,
    project_id  UUID,
    CONSTRAINT pk_roles PRIMARY KEY (id)
);

CREATE TABLE users
(
    id                UUID         NOT NULL,
    username          VARCHAR(255) NOT NULL,
    email             VARCHAR(255) NOT NULL,
    password_hash     VARCHAR(255) NOT NULL,
    email_verified_at TIMESTAMP WITHOUT TIME ZONE,
    CONSTRAINT pk_users PRIMARY KEY (id)
);

CREATE TABLE email_verification_challenges
(
    id                UUID                        NOT NULL,
    user_id           UUID                        NOT NULL,
    link_code_hash    VARCHAR(255)                NOT NULL,
    number_code_hash  VARCHAR(255)                NOT NULL,
    link_expires_at   TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    number_expires_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    consumed_at       TIMESTAMP WITHOUT TIME ZONE,
    superseded_at     TIMESTAMP WITHOUT TIME ZONE,
    created_at        TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    reason            VARCHAR(255)                NOT NULL,
    CONSTRAINT pk_email_verification_challenges PRIMARY KEY (id)
);

CREATE TABLE email_send_jobs
(
    id               UUID                        NOT NULL,
    context_type_key VARCHAR(255)                NOT NULL,
    job_context      JSONB                       NOT NULL,
    status           VARCHAR(255)                NOT NULL,
    attempts_count   INTEGER                     NOT NULL,
    created_at       TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    next_attempt_at  TIMESTAMP WITHOUT TIME ZONE,
    sent_at          TIMESTAMP WITHOUT TIME ZONE,
    locked_at        TIMESTAMP WITHOUT TIME ZONE,
    locked_until     TIMESTAMP WITHOUT TIME ZONE,
    last_error       TEXT,
    CONSTRAINT pk_email_send_jobs PRIMARY KEY (id)
);

CREATE TABLE email_verification_throttles
(
    id            UUID                        NOT NULL,
    user_id       UUID                        NOT NULL,
    blocked_until TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    created_at    TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_email_verification_throttles PRIMARY KEY (id)
);

CREATE TABLE system_settings
(
    key   text PRIMARY KEY,
    value text NOT NULL
);

ALTER TABLE permissions
    ADD CONSTRAINT uc_permissions_permission_key UNIQUE (permission_key);

ALTER TABLE projects
    ADD CONSTRAINT uc_projects_name UNIQUE (name);

ALTER TABLE projects
    ADD CONSTRAINT uc_projects_slug UNIQUE (slug);

ALTER TABLE users
    ADD CONSTRAINT uc_users_email UNIQUE (email);

ALTER TABLE users
    ADD CONSTRAINT uc_users_username UNIQUE (username);

ALTER TABLE groups
    ADD CONSTRAINT FK_GROUPS_ON_ID FOREIGN KEY (id) REFERENCES iam_principals (id);

ALTER TABLE projects
    ADD CONSTRAINT FK_PROJECTS_ON_OWNERPRINCIPAL FOREIGN KEY (owner_principal_id) REFERENCES iam_principals (id);

ALTER TABLE projects
    ADD CONSTRAINT uc_projects_scope UNIQUE (scope_id);

ALTER TABLE projects
    ADD CONSTRAINT FK_PROJECTS_ON_SCOPE FOREIGN KEY (scope_id) REFERENCES iam_scopes (id);

ALTER TABLE refresh_tokens
    ADD CONSTRAINT FK_REFRESH_TOKENS_ON_USER FOREIGN KEY (user_id) REFERENCES users (id);

ALTER TABLE roles
    ADD CONSTRAINT FK_ROLES_ON_PROJECT FOREIGN KEY (project_id) REFERENCES projects (id);

ALTER TABLE role_bindings
    ADD CONSTRAINT FK_ROLE_BINDINGS_ON_ROLE FOREIGN KEY (role_id) REFERENCES roles (id);

ALTER TABLE role_bindings
    ADD CONSTRAINT FK_ROLE_BINDINGS_ON_SCOPE FOREIGN KEY (scope_id) REFERENCES iam_scopes (id);

ALTER TABLE role_bindings
    ADD CONSTRAINT FK_ROLE_BINDINGS_ON_SUBJECT_PRINCIPAL FOREIGN KEY (subject_principal_id) REFERENCES iam_principals (id);

ALTER TABLE users
    ADD CONSTRAINT FK_USERS_ON_ID FOREIGN KEY (id) REFERENCES iam_principals (id);

ALTER TABLE group_members
    ADD CONSTRAINT fk_group_members_on_group FOREIGN KEY (group_id) REFERENCES groups (id);

ALTER TABLE group_members
    ADD CONSTRAINT fk_group_members_on_user FOREIGN KEY (user_id) REFERENCES users (id);

ALTER TABLE role_permissions
    ADD CONSTRAINT fk_role_permissions_on_permission FOREIGN KEY (permission_id) REFERENCES permissions (id);

ALTER TABLE role_permissions
    ADD CONSTRAINT fk_role_permissions_on_role FOREIGN KEY (role_id) REFERENCES roles (id);

ALTER TABLE email_verification_challenges
    ADD CONSTRAINT FK_EMAIL_VERIFICATION_CHALLENGES_ON_USER FOREIGN KEY (user_id) REFERENCES users (id);

ALTER TABLE email_verification_throttles
    ADD CONSTRAINT FK_EMAIL_VERIFICATION_THROTTLES_ON_USER FOREIGN KEY (user_id) REFERENCES users (id);
