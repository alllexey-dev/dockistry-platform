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
    id             UUID NOT NULL,
    principal_type VARCHAR(255),
    CONSTRAINT pk_iam_principals PRIMARY KEY (id)
);

CREATE TABLE iam_scopes
(
    id         UUID NOT NULL,
    scope_type VARCHAR(255),
    CONSTRAINT pk_iam_scopes PRIMARY KEY (id)
);

CREATE TABLE permissions
(
    id             UUID         NOT NULL,
    permission_key VARCHAR(255) NOT NULL,
    description    VARCHAR(255) NOT NULL,
    CONSTRAINT pk_permissions PRIMARY KEY (id)
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
    CONSTRAINT pk_roles PRIMARY KEY (id)
);

CREATE TABLE users
(
    id            UUID         NOT NULL,
    username      VARCHAR(255) NOT NULL,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    CONSTRAINT pk_users PRIMARY KEY (id)
);

ALTER TABLE users
    ADD CONSTRAINT uc_users_email UNIQUE (email);

ALTER TABLE users
    ADD CONSTRAINT uc_users_username UNIQUE (username);

ALTER TABLE groups
    ADD CONSTRAINT FK_GROUPS_ON_ID FOREIGN KEY (id) REFERENCES iam_principals (id);

ALTER TABLE refresh_tokens
    ADD CONSTRAINT FK_REFRESH_TOKENS_ON_USER FOREIGN KEY (user_id) REFERENCES users (id);

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
