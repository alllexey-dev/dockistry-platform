package io.dockistry.platform.services.infrastructure

import com.zaxxer.hikari.HikariDataSource
import io.dockistry.platform.services.application.ports.DeploymentExecutionGate
import io.dockistry.platform.services.application.ports.DeploymentExecutionGateLockFailureException
import io.dockistry.platform.services.application.ports.DeploymentExecutionGateLockTimeoutException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.sql.Connection
import java.time.Duration
import java.util.UUID
import java.util.concurrent.locks.LockSupport
import kotlin.math.min

@Service
class PostgresDeploymentExecutionGate(
    private val dataSource: HikariDataSource,
    private val executionGateConfig: DeploymentExecutionGateConfig
) : DeploymentExecutionGate {
    companion object {
        private val RETRY_DELAY = Duration.ofMillis(10)

        private val logger =
            LoggerFactory.getLogger(PostgresDeploymentExecutionGate::class.java)
    }

    override fun <T> withLock(deploymentId: UUID, block: () -> T): T = withLock(
        deploymentId = deploymentId,
        timeout = executionGateConfig.lockTimeout,
        block = block
    )

    override fun <T> withLock(
        deploymentId: UUID,
        timeout: Duration,
        block: () -> T
    ): T {
        val lockKey = getAdvisoryLockKey(deploymentId)
        val connection = try {
            obtainConnection()
        } catch (exception: Exception) {
            logger.warn(
                "Failed to obtain a connection for execution gate lock: {}",
                exception.message,
                exception
            )
            throw DeploymentExecutionGateLockFailureException(exception)
        }

        var lockAcquired = false

        return try {
            try {
                lockAcquired = acquireLock(connection, timeout, lockKey)
            } catch (exception: Exception) {
                logger.warn(
                    "Deployment execution gate lock acquisition failed: {}",
                    exception.message,
                    exception
                )
                throw DeploymentExecutionGateLockFailureException(exception)
            }

            if (!lockAcquired) {
                throw DeploymentExecutionGateLockTimeoutException()
            }

            block()
        } finally {
            try {
                if (lockAcquired) {
                    releaseLock(connection, lockKey)
                }
                connection.close()
            } catch (exception: Throwable) {
                dataSource.evictConnection(connection)
                logger.warn(
                    "Deployment execution gate failed to gracefully release lock " +
                            "and close the connection, evicting connection: {}",
                    exception.message,
                    exception
                )
            }
        }
    }

    private fun obtainConnection(): Connection = dataSource.connection

    private fun acquireLock(connection: Connection, timeout: Duration, key: Long): Boolean {
        val timeoutNanos = timeout.toNanosSaturated()
        val startedAt = System.nanoTime()

        connection.prepareStatement("SELECT pg_try_advisory_lock(?)").use { statement ->
            statement.setLong(1, key)

            while (true) {
                statement.executeQuery().use { resultSet ->
                    if (resultSet.next() && resultSet.getBoolean(1)) {
                        return true
                    }
                }

                val elapsedNanos = System.nanoTime() - startedAt
                if (elapsedNanos >= timeoutNanos) {
                    return false
                }

                LockSupport.parkNanos(
                    min(RETRY_DELAY.toNanos(), timeoutNanos - elapsedNanos)
                )
                if (Thread.interrupted()) {
                    Thread.currentThread().interrupt()
                    throw InterruptedException(
                        "Interrupted while waiting for deployment execution gate lock"
                    )
                }
            }
        }
    }

    private fun releaseLock(connection: Connection, key: Long) {
        connection.prepareStatement("SELECT pg_advisory_unlock(?)").use { statement ->
            statement.setLong(1, key)
            statement.executeQuery().use { resultSet ->
                if (!resultSet.next() || !resultSet.getBoolean(1)) {
                    throw IllegalStateException(
                        "PostgreSQL reported that the deployment execution gate lock " +
                                "was not held by its connection"
                    )
                }
            }
        }
    }

    private fun getAdvisoryLockKey(deploymentId: UUID): Long {
        val bytes = MessageDigest
            .getInstance("SHA-256")
            .digest("managed-service-deployment: $deploymentId".toByteArray(Charsets.UTF_8))
        return ByteBuffer.wrap(bytes).long
    }

    private fun Duration.toNanosSaturated(): Long {
        if (isNegative || isZero) {
            return 0
        }

        return try {
            toNanos()
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }
    }
}
