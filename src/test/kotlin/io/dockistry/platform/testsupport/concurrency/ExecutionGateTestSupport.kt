package io.dockistry.platform.testsupport.concurrency

import com.zaxxer.hikari.HikariDataSource
import io.dockistry.platform.services.application.ports.ExecutionGate
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Returns only after every holder owns a connection from the dedicated gate pool. */
fun ConcurrencyTestSupport.Scenario.holdExecutionGateConnections(
    gate: ExecutionGate,
    count: Int,
    timeout: Duration
): ConcurrencyTestSupport.ResourceHolders =
    hold("execution gate pool", count) { signalAcquiredAndAwaitRelease ->
        gate.withLock(UUID.randomUUID().mostSignificantBits, timeout) {
            signalAcquiredAndAwaitRelease()
        }
    }

fun ConcurrencyTestSupport.Scenario.holdExecutionGateLock(
    gate: ExecutionGate,
    key: Long,
    timeout: Duration
): ConcurrencyTestSupport.ResourceHolders =
    hold("execution gate lock") { signalAcquiredAndAwaitRelease ->
        gate.withLock(key, timeout) { signalAcquiredAndAwaitRelease() }
    }

/** Holds the key captured by this checkpoint, unaffected by other gate calls. */
fun ConcurrencyTestSupport.Scenario.holdExecutionGateLockAt(
    gate: ExecutionGate,
    pausedAt: ConcurrencyTestSupport.Pause,
    timeout: Duration
): ConcurrencyTestSupport.ResourceHolders {
    val key = assertIs<Long>(pausedAt.awaitHit().value, "Checkpoint did not publish a gate key")
    return holdExecutionGateLock(gate, key, timeout)
}

/** Keeps pool configuration and Hikari instrumentation out of business scenarios. */
class ExecutionGateTestSupport(private val dataSource: HikariDataSource) {
    val poolCapacity: Int get() = dataSource.maximumPoolSize

    fun capturePoolState(): PoolState =
        PoolState(dataSource.hikariPoolMXBean.totalConnections)

    /** Verifies both one occupied connection and no pool growth caused by waiters. */
    fun assertOnlyHolderConnectionRemains(beforeWaiters: PoolState) {
        val pool = dataSource.hikariPoolMXBean
        assertEquals(1, pool.activeConnections)
        assertEquals(beforeWaiters.totalConnections, pool.totalConnections)
    }

    data class PoolState(val totalConnections: Int)
}
