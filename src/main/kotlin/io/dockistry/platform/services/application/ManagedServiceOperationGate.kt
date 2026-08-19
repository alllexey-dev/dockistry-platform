package io.dockistry.platform.services.application

import io.dockistry.platform.services.application.ports.ExecutionGate
import io.dockistry.platform.services.application.ports.ExecutionGateLockFailureException
import io.dockistry.platform.services.application.ports.ExecutionGateLockTimeoutException
import io.dockistry.platform.services.application.ports.ExecutionGateOverloadException
import io.dockistry.platform.services.domain.ManagedServiceExecutionCapacityExhaustedException
import io.dockistry.platform.services.domain.ManagedServiceOperationConcurrencyCheckFailureException
import io.dockistry.platform.services.domain.ManagedServiceOperationInProgressException
import io.dockistry.platform.services.infrastructure.ServiceOperationGateConfig
import org.springframework.stereotype.Service
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID

@Service
class ManagedServiceOperationGate(
    private val executionGate: ExecutionGate,
    private val config: ServiceOperationGateConfig
) {
    fun <T> withLock(serviceId: UUID, block: () -> T): T =
        withLock(
            serviceId = serviceId,
            timeout = config.lockTimeout,
            block = block
        )

    fun <T> withLock(
        serviceId: UUID,
        timeout: Duration,
        block: () -> T
    ): T = translateGateExceptions {
        executionGate.withLock(
            advisoryLockKey = advisoryLockKey(serviceId),
            timeout = timeout,
            block = block
        )
    }

    private fun <T> translateGateExceptions(block: () -> T): T {
        try {
            return block()
        } catch (exception: ExecutionGateLockTimeoutException) {
            throw ManagedServiceOperationInProgressException().also {
                it.initCause(exception)
            }
        } catch (exception: ExecutionGateLockFailureException) {
            throw ManagedServiceOperationConcurrencyCheckFailureException().also {
                it.initCause(exception)
            }
        } catch (exception: ExecutionGateOverloadException) {
            throw ManagedServiceExecutionCapacityExhaustedException().also {
                it.initCause(exception)
            }
        }
    }

    private fun advisoryLockKey(serviceId: UUID): Long {
        val bytes = MessageDigest
            .getInstance("SHA-256")
            .digest("managed-service: $serviceId".toByteArray(Charsets.UTF_8))
        return ByteBuffer.wrap(bytes).long
    }
}
