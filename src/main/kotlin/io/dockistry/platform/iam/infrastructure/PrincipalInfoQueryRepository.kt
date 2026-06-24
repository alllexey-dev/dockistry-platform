package io.dockistry.platform.iam.infrastructure

import io.dockistry.platform.iam.domain.PrincipalType
import io.dockistry.platform.iam.domain.dto.PrincipalInfoDto
import io.dockistry.platform.shared.exception.InternalDataIntegrityException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class PrincipalInfoQueryRepository(
    private val jdbc: NamedParameterJdbcTemplate
) {

    companion object {
        val principalInfoRowMapper = RowMapper { rs, _ ->
            PrincipalInfoDto(
                id = rs.getObject("id", UUID::class.java),
                type = PrincipalType.valueOf(rs.getString("type")),
                name = rs.getString("name")
            )
        }
    }

    fun getInfoById(id: UUID): PrincipalInfoDto? {
        val sql = """
            SELECT
                p.id AS id,
                p.principal_type AS type,
                CASE
                    WHEN p.principal_type = 'USER' THEN u.username
                    WHEN p.principal_type = 'GROUP' THEN g.name
                END AS name
            FROM iam_principals p
            LEFT JOIN users u ON u.id = p.id
            LEFT JOIN groups g ON g.id = p.id
            WHERE p.id = :id
        """.trimIndent()

        val resultList = jdbc.query(sql, mapOf("id" to id), principalInfoRowMapper)

        if (resultList.size > 1) {
            throw InternalDataIntegrityException("Expected 1 row, got ${resultList.size}")
        }

        return resultList.firstOrNull()
    }

    fun getInfoByIds(ids: List<UUID>): Map<UUID, PrincipalInfoDto> {
        if (ids.isEmpty()) {
            return emptyMap()
        }

        val sql = """
            SELECT
                p.id AS id,
                p.principal_type AS type,
                CASE
                    WHEN p.principal_type = 'USER' THEN u.username
                    WHEN p.principal_type = 'GROUP' THEN g.name
                END AS name
            FROM iam_principals p
            LEFT JOIN users u ON u.id = p.id
            LEFT JOIN groups g ON g.id = p.id
            WHERE p.id IN (:ids)
        """.trimIndent()

        val resultList = jdbc.query(sql, mapOf("ids" to ids), principalInfoRowMapper)
        return resultList.associateBy { it.id }
    }
}
