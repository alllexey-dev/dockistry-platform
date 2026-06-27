package io.dockistry.platform.projects.infrastructure

import io.dockistry.platform.iam.domain.SystemPermissionNames
import io.dockistry.platform.projects.domain.dto.PermissionDto
import io.dockistry.platform.projects.domain.dto.RoleBindingDto
import io.dockistry.platform.projects.domain.dto.RoleDto
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class ProjectMemberRepository(
    private val jdbc: NamedParameterJdbcTemplate
) {
    fun listByProject(projectId: UUID): Map<UUID, List<RoleBindingDto>> {
        val sql = """
            SELECT
                rb.subject_principal_id AS principal_id,
                rb.id AS role_binding_id,
                rb.role_id AS role_id
            FROM role_bindings rb
            JOIN projects p ON p.scope_id = rb.scope_id
            JOIN role_permissions rp ON rp.role_id = rb.role_id
            WHERE p.id = :projectId
                AND rp.permission_id = (
                    SELECT pm.id
                    FROM permissions pm
                    WHERE pm.permission_key = :projectReadPermissionKey
                )
            ORDER BY rb.subject_principal_id, rb.id
        """.trimIndent()

        val params = mapOf(
            "projectId" to projectId,
            "projectReadPermissionKey" to SystemPermissionNames.PROJECT_READ
        )

        return jdbc.query(sql, params) { rs, _ ->
            rs.getObject("principal_id", UUID::class.java) to RoleBindingDto(
                roleBindingId = rs.getObject("role_binding_id", UUID::class.java),
                roleId = rs.getObject("role_id", UUID::class.java)
            )
        }.groupBy(
            keySelector = { it.first },
            valueTransform = { it.second }
        )
    }

    fun listRolesByProject(projectId: UUID): List<RoleDto> {
        val sql = """
            SELECT
                r.id AS role_id,
                r.name AS role_name,
                pm.permission_key AS permission_key,
                pm.description AS permission_description
            FROM roles r
            JOIN role_permissions rp ON rp.role_id = r.id
            JOIN permissions pm ON pm.id = rp.permission_id
            WHERE (r.is_system = :isSystemRole OR r.project_id = :projectId)
                AND EXISTS (
                    SELECT 1
                    FROM role_permissions read_rp
                    JOIN permissions read_pm ON read_pm.id = read_rp.permission_id
                    WHERE read_rp.role_id = r.id
                        AND read_pm.permission_key = :projectReadPermissionKey
                )
            ORDER BY r.name, pm.permission_key
        """.trimIndent()

        val params = mapOf(
            "projectId" to projectId,
            "isSystemRole" to true,
            "projectReadPermissionKey" to SystemPermissionNames.PROJECT_READ
        )

        val roleNames = linkedMapOf<UUID, String>()
        val rolePermissions = linkedMapOf<UUID, MutableList<PermissionDto>>()

        jdbc.query(sql, params) { rs ->
            val roleId = rs.getObject("role_id", UUID::class.java)
            roleNames.putIfAbsent(roleId, rs.getString("role_name"))
            rolePermissions.getOrPut(roleId) { mutableListOf() } += PermissionDto(
                key = rs.getString("permission_key"),
                description = rs.getString("permission_description")
            )
        }

        return roleNames.map { (roleId, roleName) ->
            RoleDto(
                id = roleId,
                roleName = roleName,
                permissions = rolePermissions[roleId].orEmpty()
            )
        }
    }

    fun isAvailableInProject(projectId: UUID, roleId: UUID): Boolean {
        val sql = """
            SELECT EXISTS(
                SELECT 1
                FROM roles r
                WHERE r.id = :roleId
                    AND (r.is_system = :isSystemRole OR r.project_id = :projectId)
                    AND EXISTS (
                        SELECT 1
                        FROM role_permissions rp
                        JOIN permissions pm ON pm.id = rp.permission_id
                        WHERE rp.role_id = r.id
                            AND pm.permission_key = :projectReadPermissionKey
                    )
            )
        """.trimIndent()

        val params = mapOf(
            "projectId" to projectId,
            "roleId" to roleId,
            "isSystemRole" to true,
            "projectReadPermissionKey" to SystemPermissionNames.PROJECT_READ
        )

        return jdbc.queryForObject(sql, params, Boolean::class.java) ?: false
    }

    fun isMember(projectId: UUID, principalId: UUID): Boolean {
        val sql = """
            SELECT EXISTS(
                SELECT 1
                FROM role_bindings rb
                JOIN projects p ON p.scope_id = rb.scope_id
                JOIN role_permissions rp ON rp.role_id = rb.role_id
                JOIN permissions pm ON pm.id = rp.permission_id
                WHERE p.id = :projectId
                    AND rb.subject_principal_id = :principalId
                    AND pm.permission_key = :projectReadPermissionKey
            )
        """.trimIndent()

        val params = mapOf(
            "projectId" to projectId,
            "principalId" to principalId,
            "projectReadPermissionKey" to SystemPermissionNames.PROJECT_READ
        )

        return jdbc.queryForObject(sql, params, Boolean::class.java) ?: false
    }
}
