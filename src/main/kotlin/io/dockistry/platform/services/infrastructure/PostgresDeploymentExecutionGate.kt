package io.dockistry.platform.services.infrastructure

import com.zaxxer.hikari.HikariDataSource
import io.dockistry.platform.services.application.ports.DeploymentExecutionGate
import io.dockistry.platform.services.application.ports.DeploymentExecutionGateLockFailureException
import io.dockistry.platform.services.application.ports.DeploymentExecutionGateLockTimeoutException
import io.dockistry.platform.services.application.ports.DeploymentExecutionGateOverloadException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.sql.Connection
import java.sql.SQLTransientConnectionException
import java.time.Duration
import java.util.UUID
import java.util.concurrent.locks.LockSupport
import kotlin.math.min

@Service
class PostgresDeploymentExecutionGate(
    @Qualifier("executionGateDataSource")
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
        var lockAcquired = false
        var connection: Connection? = null

        val timeoutNanos = timeout.toNanosSaturated()
        val startedAt = System.nanoTime()

        return try {
            while (true) {
                connection = try {
                    obtainConnection()
                } catch (exception: DeploymentExecutionGateOverloadException) {
                    throw exception
                } catch (exception: Exception) {
                    logger.warn(
                        "Failed to obtain a connection for execution gate lock: {}",
                        exception.message,
                        exception
                    )
                    throw DeploymentExecutionGateLockFailureException(exception)
                }

                try {
                    lockAcquired = acquireLock(connection, lockKey)
                } catch (exception: Exception) {
                    logger.warn(
                        "Deployment execution gate lock acquisition failed: {}",
                        exception.message,
                        exception
                    )
                    throw DeploymentExecutionGateLockFailureException(exception)
                }

                if (lockAcquired) break

                val elapsedNanos = System.nanoTime() - startedAt
                if (elapsedNanos >= timeoutNanos) {
                    throw DeploymentExecutionGateLockTimeoutException()
                }

                cleanup(connection, lockKey, false)
                connection = null
                retrySleep(timeoutNanos - elapsedNanos)
            }

            block()
        } finally {
            cleanup(connection, lockKey, lockAcquired)
        }
    }

    private fun cleanup(connection: Connection?, lockKey: Long, lockAcquired: Boolean) {
        if (connection != null) {
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

    private fun retrySleep(nanosAmount: Long) {
        LockSupport.parkNanos(
            min(RETRY_DELAY.toNanos(), nanosAmount)
        )

        if (Thread.interrupted()) {
            Thread.currentThread().interrupt()
            throw InterruptedException(
                "Interrupted while waiting for deployment execution gate lock"
            )
        }
    }

    private fun obtainConnection(): Connection = try {
        dataSource.connection
    } catch (exception: SQLTransientConnectionException) {
        throw DeploymentExecutionGateOverloadException(exception)
    }

    private fun acquireLock(
        connection: Connection,
        key: Long
    ): Boolean {
        return connection.prepareStatement("SELECT PG_TRY_ADVISORY_LOCK(?)")
            .use { statement ->
                statement.setLong(1, key)

                statement.executeQuery().use { resultSet ->
                    resultSet.next() && resultSet.getBoolean(1)
                }
            }
    }

    private fun releaseLock(connection: Connection, key: Long) {
        connection.prepareStatement("SELECT PG_ADVISORY_UNLOCK(?)").use { statement ->
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
