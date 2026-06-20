package io.dockistry.platform.auth.infrastructure

import io.dockistry.platform.email.domain.EmailSendJob
import io.dockistry.platform.email.domain.VerificationEmailJobContext
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class VerificationEmailSendJobRepository(
    private val entityManager: EntityManager
) {
    fun findActiveJobForUser(userId: UUID): EmailSendJob? {
        return entityManager.createNativeQuery(
            """
            SELECT *
            FROM email_send_jobs
            WHERE context_type_key = :contextTypeKey
                AND status IN ('PENDING', 'PROCESSING', 'FAILED')
                AND job_context ->> 'userId' = CAST(:userId AS TEXT)
            ORDER BY created_at
            LIMIT 1
            """.trimIndent(),
            EmailSendJob::class.java
        )
            .setParameter("contextTypeKey", VerificationEmailJobContext.TYPE_KEY)
            .setParameter("userId", userId)
            .resultList
            .firstOrNull() as EmailSendJob?
    }

    fun countActiveJobsForUser(userId: UUID): Long {
        return (
            entityManager.createNativeQuery(
                """
                SELECT COUNT(*)
                FROM email_send_jobs
                WHERE context_type_key = :contextTypeKey
                    AND status IN ('PENDING', 'PROCESSING', 'FAILED')
                    AND job_context ->> 'userId' = CAST(:userId AS TEXT)
                """.trimIndent()
            )
                .setParameter("contextTypeKey", VerificationEmailJobContext.TYPE_KEY)
                .setParameter("userId", userId)
                .singleResult as Number
            ).toLong()
    }
}
