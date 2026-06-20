package io.dockistry.platform.email.infrastructure

import io.dockistry.platform.email.domain.EmailSendJob
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface EmailSendJobRepository : JpaRepository<EmailSendJob, UUID> {
    @Query(
        value = """
            SELECT *
            FROM email_send_jobs
            WHERE context_type_key = 'email_verification'
                AND status IN ('PENDING', 'PROCESSING', 'FAILED')
                AND job_context ->> 'userId' = CAST(:userId AS TEXT)
            ORDER BY created_at
            LIMIT 1
        """,
        nativeQuery = true
    )
    fun findActiveEmailVerificationJobForUser(userId: UUID): EmailSendJob?

    @Query(
        value = """
            SELECT COUNT(*)
            FROM email_send_jobs
            WHERE context_type_key = 'email_verification'
                AND status IN ('PENDING', 'PROCESSING', 'FAILED')
                AND job_context ->> 'userId' = CAST(:userId AS TEXT)
        """,
        nativeQuery = true
    )
    fun countActiveEmailVerificationJobsForUser(userId: UUID): Long
}
