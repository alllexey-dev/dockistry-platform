package io.dockistry.platform.services.application

import io.dockistry.platform.projects.domain.ProjectExecutionCapacityExhaustedException
import io.dockistry.platform.projects.domain.ProjectOperationConcurrencyCheckFailureException
import io.dockistry.platform.projects.domain.ProjectOperationInProgressException
import io.dockistry.platform.services.application.ports.ExecutionGate
import io.dockistry.platform.services.application.ports.ExecutionGateLockFailureException
import io.dockistry.platform.services.application.ports.ExecutionGateLockTimeoutException
import io.dockistry.platform.services.application.ports.ExecutionGateOverloadException
import io.dockistry.platform.services.infrastructure.ProjectOperationGateConfig
import org.springframework.stereotype.Service
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID

@Service
class ProjectOperationGate(
    private val executionGate: ExecutionGate,
    private val config: ProjectOperationGateConfig
) {
    fun <T> withLock(projectId: UUID, block: () -> T): T =
        withLock(
            projectId = projectId,
            timeout = config.lockTimeout,
            block = block
        )

    fun <T> withLock(
        projectId: UUID,
        timeout: Duration,
        block: () -> T
    ): T = translateGateExceptions {
        executionGate.withLock(
            advisoryLockKey = advisoryLockKey(projectId),
            timeout = timeout,
            block = block
        )
    }

    private fun <T> translateGateExceptions(block: () -> T): T {
        try {
            return block()
        } catch (exception: ExecutionGateLockTimeoutException) {
            throw ProjectOperationInProgressException().also {
                it.initCause(exception)
            }
        } catch (exception: ExecutionGateLockFailureException) {
            throw ProjectOperationConcurrencyCheckFailureException().also {
                it.initCause(exception)
            }
        } catch (exception: ExecutionGateOverloadException) {
            throw ProjectExecutionCapacityExhaustedException().also {
                it.initCause(exception)
            }
        }
    }

    private fun advisoryLockKey(projectId: UUID): Long {
        val bytes = MessageDigest
            .getInstance("SHA-256")
            .digest("project: $projectId".toByteArray(Charsets.UTF_8))
        return ByteBuffer.wrap(bytes).long
    }
}
