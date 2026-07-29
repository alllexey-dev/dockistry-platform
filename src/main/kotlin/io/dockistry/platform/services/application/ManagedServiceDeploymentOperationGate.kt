package io.dockistry.platform.services.application

import io.dockistry.platform.services.application.ports.ExecutionGate
import io.dockistry.platform.services.application.ports.ExecutionGateLockFailureException
import io.dockistry.platform.services.application.ports.ExecutionGateLockTimeoutException
import io.dockistry.platform.services.application.ports.ExecutionGateOverloadException
import io.dockistry.platform.services.domain.DeploymentExecutionCapacityExhaustedException
import io.dockistry.platform.services.domain.DeploymentOperationConcurrencyCheckFailureException
import io.dockistry.platform.services.domain.DeploymentOperationInProgressException
import io.dockistry.platform.services.infrastructure.DeploymentOperationGateConfig
import org.springframework.stereotype.Service
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID

@Service
class ManagedServiceDeploymentOperationGate(
    private val executionGate: ExecutionGate,
    private val config: DeploymentOperationGateConfig
) {
    fun <T> withLock(deploymentId: UUID, block: () -> T): T =
        withLock(
            deploymentId = deploymentId,
            timeout = config.lockTimeout,
            block = block
        )

    fun <T> withLock(
        deploymentId: UUID,
        timeout: Duration,
        block: () -> T
    ): T = translateGateExceptions {
        executionGate.withLock(
            advisoryLockKey = advisoryLockKey(deploymentId),
            timeout = timeout,
            block = block
        )
    }

    private fun <T> translateGateExceptions(block: () -> T): T {
        try {
            return block()
        } catch (exception: ExecutionGateLockTimeoutException) {
            throw DeploymentOperationInProgressException().also {
                it.initCause(exception)
            }
        } catch (exception: ExecutionGateLockFailureException) {
            throw DeploymentOperationConcurrencyCheckFailureException().also {
                it.initCause(exception)
            }
        } catch (exception: ExecutionGateOverloadException) {
            throw DeploymentExecutionCapacityExhaustedException().also {
                it.initCause(exception)
            }
        }
    }

    private fun advisoryLockKey(deploymentId: UUID): Long {
        val bytes = MessageDigest
            .getInstance("SHA-256")
            .digest("managed-service-deployment: $deploymentId".toByteArray(Charsets.UTF_8))
        return ByteBuffer.wrap(bytes).long
    }
}
