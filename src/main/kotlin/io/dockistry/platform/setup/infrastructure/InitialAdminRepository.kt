package io.dockistry.platform.setup.infrastructure

import io.dockistry.platform.iam.domain.PrincipalType
import io.dockistry.platform.iam.domain.ScopeType
import io.dockistry.platform.iam.domain.SystemRoleNames
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class InitialAdminRepository(
    private val jdbc: NamedParameterJdbcTemplate
) {
    fun existsAdmin(): Boolean {
        return jdbc.queryForObject(
            """
                SELECT EXISTS(SELECT 1
                  FROM role_bindings rb
                           JOIN roles r ON rb.role_id = r.id
                           JOIN iam_scopes s ON rb.scope_id = s.id
                           JOIN iam_principals p ON rb.subject_principal_id = p.id
                  WHERE r.name = :projectAdminRoleName
                    AND r.is_system = TRUE
                    AND s.scope_type = :globalScopeName
                    AND (
                      (p.principal_type = :principalUserType
                          AND EXISTS(
                              SELECT 1
                              FROM users u
                              WHERE u.email_verified_at IS NOT NULL)
                          )
                          OR
                      (p.principal_type = :principalGroupType
                          AND EXISTS(SELECT 1
                                     FROM group_members gm
                                              JOIN users u ON gm.user_id = u.id
                                     WHERE gm.group_id = p.id
                                       AND u.email_verified_at IS NOT NULL)
                          )
                      ))
            """.trimIndent(),

            mapOf(
                "projectAdminRoleName" to SystemRoleNames.PROJECT_ADMIN,
                "globalScopeName" to ScopeType.GLOBAL.name,
                "principalUserType" to PrincipalType.USER.name,
                "principalGroupType" to PrincipalType.GROUP.name
            ),

            Boolean::class.java
        ) ?: false
    }
}