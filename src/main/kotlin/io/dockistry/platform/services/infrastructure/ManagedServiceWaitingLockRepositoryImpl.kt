package io.dockistry.platform.services.infrastructure

import io.dockistry.platform.services.domain.ManagedService
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Repository
import java.time.Duration
import java.util.UUID

@Repository
class ManagedServiceWaitingLockRepositoryImpl(
    private val entityManager: EntityManager
) : ManagedServiceWaitingLockRepository {
    override fun findByIdForUpdate(
        serviceId: UUID,
        lockTimeout: Duration
    ): ManagedService? {
        setLocalLockTimeout(lockTimeout)
        return findServiceForUpdate(
            whereClause = "id = :serviceId",
            parameters = mapOf("serviceId" to serviceId)
        )
    }

    override fun findByProjectIdAndIdForUpdate(
        projectId: UUID,
        serviceId: UUID,
        lockTimeout: Duration
    ): ManagedService? {
        setLocalLockTimeout(lockTimeout)
        return findServiceForUpdate(
            whereClause = "project_id = :projectId AND id = :serviceId",
            parameters = mapOf(
                "projectId" to projectId,
                "serviceId" to serviceId
            )
        )
    }

    private fun setLocalLockTimeout(lockTimeout: Duration) {
        entityManager.createNativeQuery(
            """
            SELECT set_config(
                'lock_timeout',
                :lockTimeout,
                true
            )
            """.trimIndent()
        )
            .setParameter("lockTimeout", "${lockTimeout.toMillis()}ms")
            .singleResult
    }

    private fun findServiceForUpdate(
        whereClause: String,
        parameters: Map<String, Any>
    ): ManagedService? {
        val query = entityManager.createNativeQuery(
            """
            SELECT *
            FROM services
            WHERE $whereClause
            FOR NO KEY UPDATE
            """.trimIndent(),
            ManagedService::class.java
        )
        parameters.forEach { (name, value) -> query.setParameter(name, value) }
        return query.resultList.singleOrNull() as ManagedService?
    }
}
