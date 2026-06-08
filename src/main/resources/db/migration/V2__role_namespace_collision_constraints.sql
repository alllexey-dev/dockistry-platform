ALTER TABLE roles
    ADD CONSTRAINT chk_roles_owner
        CHECK (
            (is_system = TRUE AND project_id IS NULL)
                OR
            (is_system = FALSE AND project_id IS NOT NULL)
            );

CREATE UNIQUE INDEX roles_system_unique_idx
    ON roles (name)
    WHERE is_system = TRUE;


CREATE UNIQUE INDEX roles_non_system_unique_in_project_idx
    ON roles (project_id, name)
    WHERE is_system = FALSE;
