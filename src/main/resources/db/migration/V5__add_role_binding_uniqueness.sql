CREATE UNIQUE INDEX role_bindings_subject_scope_role_unique_idx
    ON role_bindings (subject_principal_id, scope_id, role_id);
