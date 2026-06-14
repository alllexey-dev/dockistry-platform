CREATE INDEX idx_group_members_user_group
    ON group_members (user_id, group_id);

CREATE INDEX idx_role_bindings_subject_scope_role
    ON role_bindings (subject_principal_id, scope_id, role_id);
