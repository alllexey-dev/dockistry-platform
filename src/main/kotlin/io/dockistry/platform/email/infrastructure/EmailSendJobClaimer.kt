package io.dockistry.platform.email.infrastructure

import io.dockistry.platform.shared.exception.InternalDataIntegrityException
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.util.UUID

@Repository
class EmailSendJobClaimer(
    private val jdbc: NamedParameterJdbcTemplate
) {
    fun claimDueJobs(
        now: Instant, lockedUntil: Instant, limit: Int
    ): List<UUID> {
        return jdbc.queryForList(
            """
            WITH picked AS (
                SELECT id
                FROM email_send_jobs
                WHERE (status IN ('PENDING', 'FAILED')
                  AND (next_attempt_at IS NULL OR next_attempt_at <= :now)) OR
                  (status = 'PROCESSING' AND locked_until <= :now)
                ORDER BY created_at
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
            )
            UPDATE email_send_jobs j
            SET status = 'PROCESSING',
                locked_at = :now,
                locked_until = :lockedUntil
            FROM picked
            WHERE j.id = picked.id
            RETURNING j.id
            """.trimIndent(),

            MapSqlParameterSource()
                .addValue("now", Timestamp.from(now), Types.TIMESTAMP)
                .addValue("lockedUntil", Timestamp.from(lockedUntil), Types.TIMESTAMP)
                .addValue("limit", limit, Types.INTEGER),

            UUID::class.java
        )
            .map {
                requireNotNull(it) {
                    throw InternalDataIntegrityException("Claimed email send job id must not be null")
                }
            }
    }
}
