package io.dockistry.platform.testsupport.concurrency

import jakarta.persistence.EntityManager
import java.time.Clock
import java.util.UUID
import kotlin.test.assertEquals

class ManagedServiceConcurrencyFixtures(
    private val entityManager: EntityManager,
    private val concurrency: ConcurrencyTestSupport,
    private val clock: Clock
) {
    /**
     * Replaces a captured deployment while beginStop holds the service row lock.
     * A regular begin cannot run in that state, so this adversarial fixture promotes
     * an existing terminal deployment with deployment-row CAS updates.
     */
    fun replaceActiveDeployment(
        capturedDeploymentId: UUID,
        replacementDeploymentId: UUID
    ) {
        concurrency.inNewTransaction {
            val updatedAt = clock.instant()
            val canceledRows = entityManager.createNativeQuery("""
                UPDATE service_deployments
                SET status = 'CANCELED',
                    cleanup_error_message = NULL,
                    last_cleanup_attempt_at = :updatedAt,
                    updated_at = :updatedAt
                WHERE id = :deploymentId
                  AND status = 'IN_PROCESS'
            """.trimIndent())
                .setParameter("deploymentId", capturedDeploymentId)
                .setParameter("updatedAt", updatedAt)
                .executeUpdate()
            assertEquals(1, canceledRows)

            val activatedRows = entityManager.createNativeQuery("""
                UPDATE service_deployments
                SET status = 'IN_PROCESS',
                    error_message = NULL,
                    updated_at = :updatedAt
                WHERE id = :deploymentId
                  AND status = 'FAILED'
            """.trimIndent())
                .setParameter("deploymentId", replacementDeploymentId)
                .setParameter("updatedAt", updatedAt)
                .executeUpdate()
            assertEquals(1, activatedRows)
        }
    }
}
