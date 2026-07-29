package io.dockistry.platform.services.infrastructure

import com.zaxxer.hikari.HikariDataSource
import io.dockistry.platform.services.application.ports.ExecutionGate
import io.dockistry.platform.services.application.ports.ExecutionGateLockFailureException
import io.dockistry.platform.services.application.ports.ExecutionGateLockTimeoutException
import io.dockistry.platform.services.application.ports.ExecutionGateOverloadException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import java.sql.Connection
import java.sql.SQLTransientConnectionException
import java.time.Duration
import java.util.concurrent.locks.LockSupport
import kotlin.math.min

@Service
class PostgresExecutionGate(
    @Qualifier("executionGateDataSource")
    private val dataSource: HikariDataSource
) : ExecutionGate {
    companion object {
        private val RETRY_DELAY = Duration.ofMillis(10)

        private val logger =
            LoggerFactory.getLogger(PostgresExecutionGate::class.java)
    }

    override fun <T> withLock(
        advisoryLockKey: Long,
        timeout: Duration,
        block: () -> T
    ): T {
        var lockAcquired = false
        var connection: Connection? = null

        val timeoutNanos = timeout.toNanosSaturated()
        val startedAt = System.nanoTime()

        return try {
            while (true) {
                connection = try {
                    obtainConnection()
                } catch (exception: ExecutionGateOverloadException) {
                    throw exception
                } catch (exception: Exception) {
                    logger.warn(
                        "Failed to obtain a connection for execution gate lock: {}",
                        exception.message,
                        exception
                    )
                    throw ExecutionGateLockFailureException(exception)
                }

                try {
                    lockAcquired = acquireLock(connection, advisoryLockKey)
                } catch (exception: Exception) {
                    logger.warn(
                        "Execution gate lock acquisition failed: {}",
                        exception.message,
                        exception
                    )
                    throw ExecutionGateLockFailureException(exception)
                }

                if (lockAcquired) break

                val elapsedNanos = System.nanoTime() - startedAt
                if (elapsedNanos >= timeoutNanos) {
                    throw ExecutionGateLockTimeoutException()
                }

                cleanup(connection, advisoryLockKey, false)
                connection = null
                retrySleep(timeoutNanos - elapsedNanos)
            }

            block()
        } finally {
            cleanup(connection, advisoryLockKey, lockAcquired)
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
                    "Execution gate failed to gracefully release lock " +
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
                "Interrupted while waiting for execution gate lock"
            )
        }
    }

    private fun obtainConnection(): Connection = try {
        dataSource.connection
    } catch (exception: SQLTransientConnectionException) {
        throw ExecutionGateOverloadException(exception)
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
                        "PostgreSQL reported that the execution gate lock " +
                                "was not held by its connection"
                    )
                }
            }
        }
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
