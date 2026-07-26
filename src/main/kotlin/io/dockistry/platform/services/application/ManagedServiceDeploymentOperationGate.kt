package io.dockistry.platform.services.application

import io.dockistry.platform.services.application.ports.DeploymentExecutionGate
import io.dockistry.platform.services.application.ports.DeploymentExecutionGateLockFailureException
import io.dockistry.platform.services.application.ports.DeploymentExecutionGateLockTimeoutException
import io.dockistry.platform.services.application.ports.DeploymentExecutionGateOverloadException
import io.dockistry.platform.services.domain.DeploymentExecutionCapacityExhaustedException
import io.dockistry.platform.services.domain.DeploymentOperationConcurrencyCheckFailureException
import io.dockistry.platform.services.domain.DeploymentOperationInProgressException
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.UUID

@Service
class ManagedServiceDeploymentOperationGate(
    private val deploymentExecutionGate: DeploymentExecutionGate
) {
    fun <T> withLock(deploymentId: UUID, block: () -> T): T =
        translateGateExceptions {
            deploymentExecutionGate.withLock(deploymentId, block)
        }

    fun <T> withLock(
        deploymentId: UUID,
        timeout: Duration,
        block: () -> T
    ): T = translateGateExceptions {
        deploymentExecutionGate.withLock(deploymentId, timeout, block)
    }

    private fun <T> translateGateExceptions(block: () -> T): T {
        try {
            return block()
        } catch (exception: DeploymentExecutionGateLockTimeoutException) {
            throw DeploymentOperationInProgressException().also {
                it.initCause(exception)
            }
        } catch (exception: DeploymentExecutionGateLockFailureException) {
            throw DeploymentOperationConcurrencyCheckFailureException().also {
                it.initCause(exception)
            }
        } catch (exception: DeploymentExecutionGateOverloadException) {
            throw DeploymentExecutionCapacityExhaustedException().also {
                it.initCause(exception)
            }
        }
    }
}
