package io.dockistry.platform.testsupport.concurrency

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.queryForObject
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Owns workers and checkpoints for one scenario. Use real service calls in workers;
 * independent transactions below are for fixtures, readback and explicit lock holders.
 */
class ConcurrencyTestSupport(
    private val jdbcTemplate: JdbcTemplate,
    transactionManager: PlatformTransactionManager
) {
    private val transactions = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }
    private val participating = ThreadLocal.withInitial { false }
    @Volatile private var scenario: Scenario? = null

    fun <T> run(workers: Int = 2, block: Scenario.() -> T): T {
        check(scenario == null) { "A concurrency scenario is already running" }
        val current = Scenario(workers)
        scenario = current
        var failure: Throwable? = null
        try {
            return current.block()
        } catch (exception: Throwable) {
            failure = exception
            throw exception
        } finally {
            try {
                current.close()
            } catch (cleanupFailure: Throwable) {
                if (failure == null) throw cleanupFailure
                if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
            } finally {
                scenario = null
            }
        }
    }

    fun <T> inNewTransaction(block: () -> T): T = transactions.execute { block() }

    fun <T> inRolledBackTransaction(block: () -> T): T = transactions.execute { status ->
        status.setRollbackOnly()
        block()
    }

    fun assertDatabaseTransactionAvailable() {
        inNewTransaction {
            assertEquals(1, jdbcTemplate.queryForObject<Int>("SELECT 1"))
        }
    }

    /** Test adapters call this at existing production boundaries, never from hooks in main. */
    fun checkpoint(point: ConcurrencyPoint, value: Any? = null) {
        if (!participating.get()) return
        scenario?.visit(point, value)
    }

    fun assertTakesAtLeast(duration: Duration, block: () -> Unit) {
        val start = System.nanoTime()
        block()
        assertTrue(
            System.nanoTime() - start >= duration.toNanos(),
            "Operation returned before the configured wait of $duration"
        )
    }

    fun withAdvisoryLockProbe(key: Long, block: (AdvisoryLockProbe) -> Unit) {
        assertNotNull(jdbcTemplate.dataSource).connection.use { connection ->
            block(AdvisoryLockProbe {
                connection.prepareStatement("SELECT pg_try_advisory_xact_lock(?)").use {
                    it.setLong(1, key)
                    it.executeQuery().use { result ->
                        result.next()
                        result.getBoolean(1)
                    }
                }
            })
        }
    }

    inner class Scenario internal constructor(private val capacity: Int) {
        init {
            check(capacity > 0) { "A scenario needs at least one worker" }
        }
        private val executor: ExecutorService = Executors.newFixedThreadPool(capacity)
        private val tasks = mutableListOf<Task<*>>()
        private val pauses = mutableListOf<Pause>()
        private val checkpoints = ConcurrentHashMap<ConcurrencyPoint, Checkpoint>()

        fun <T> start(name: String, action: () -> T): Task<T> {
            val future = executor.submit<Result<T>> {
                participating.set(true)
                try {
                    runCatching(action)
                } finally {
                    participating.remove()
                }
            }
            return Task(name, future).also { tasks += it }
        }

        fun <T> startTogether(vararg actions: () -> T): List<Result<T>> {
            val ready = newPause("simultaneous start", actions.size)
            val running = actions.mapIndexed { index, action ->
                start("concurrent operation ${index + 1}") {
                    ready.reach()
                    action()
                }
            }
            ready.awaitReached()
            ready.release()
            return running.map { it.outcome() }
        }

        fun pauseAt(
            point: ConcurrencyPoint,
            matching: Any? = null,
            participants: Int = 1,
            skip: Int = 0
        ): Pause {
            check(skip >= 0) { "Checkpoint skip count cannot be negative" }
            val pause = newPause(point.name, participants)
            register(point, Checkpoint(matching, skip, pause = pause))
            return pause
        }

        fun observeNext(point: ConcurrencyPoint): Observation {
            val observation = Observation(point.name)
            register(point, Checkpoint(observation = observation))
            return observation
        }

        /** Checks the actual PostgreSQL wait, including the identity of both sessions. */
        fun assertBlocked(waitingAt: Observation, by: Pause) {
            val waitingPid = assertNotNull(waitingAt.awaitHit().backendPid)
            val blockingPid = assertNotNull(by.awaitHit().backendPid)
            awaitCondition("${waitingAt.name} did not wait for ${by.name}") {
                jdbcTemplate.queryForObject<Boolean>("""
                    SELECT ? = ANY(pg_blocking_pids(?))
                """, blockingPid, waitingPid) == true
            }
        }

        fun assertBlocked(waitingAt: Observation, by: ResourceHolders) {
            assertBlocked(waitingAt, by.pause)
        }

        /**
         * Runs resource holders in workers and returns once all have acquired their resources.
         * This method coordinates the holders; [action] acquires and releases each resource.
         *
         * [action] receives a `signalAcquiredAndAwaitRelease` callback: only the action
         * knows when acquisition has succeeded. Call it exactly once, after acquisition,
         * while still inside the transaction or other scope that owns the resource.
         * The callback:
         * 1. Signals that this holder has acquired its resource.
         * 2. Blocks the worker until release, subject to the test timeout.
         *
         * For example, the gate owns the lock for the entire callback wait:
         * ```
         * val holders = hold("execution gate lock") { signalAcquiredAndAwaitRelease ->
         *     gate.withLock(key, timeout) { signalAcquiredAndAwaitRelease() }
         * }
         * // The lock is now held; run assertions or competing operations here.
         * holders.release()
         * ```
         *
         * [ResourceHolders.release] unblocks the callbacks and waits for the actions to
         * finish, including their resource cleanup. Scenario cleanup also unblocks them
         * if the test exits without an explicit release. [action] must release its resource
         * on both normal and exceptional exit, for example via `finally` or a transaction.
         *
         * @param name Diagnostic name used in holder tasks and timeout messages.
         * @param participants Number of holders; each occupies one scenario worker.
         * @param action Resource lifetime: acquire, call `signalAcquiredAndAwaitRelease`,
         *   then release.
         */
        fun hold(
            name: String,
            participants: Int = 1,
            action: (signalAcquiredAndAwaitRelease: () -> Unit) -> Unit
        ): ResourceHolders {
            val pause = newPause(name, participants)
            val holders = List(participants) { index ->
                start("$name ${index + 1}") {
                    action { pause.reach(Hit(currentTransactionPid())) }
                }
            }
            pause.awaitReached()
            return ResourceHolders(pause, holders)
        }

        fun holdInTransaction(name: String, acquire: () -> Unit): ResourceHolders =
            hold(name) { signalAcquiredAndAwaitRelease ->
                inNewTransaction {
                    acquire()
                    signalAcquiredAndAwaitRelease()
                }
            }

        internal fun visit(point: ConcurrencyPoint, value: Any?) {
            val checkpoint = checkpoints[point] ?: return
            if (!checkpoint.accepts(value)) return
            val hit = Hit(currentTransactionPid(), value)
            checkpoint.observation?.record(hit)
            checkpoint.pause?.reach(hit)
        }

        private fun register(point: ConcurrencyPoint, checkpoint: Checkpoint) {
            check(checkpoints.putIfAbsent(point, checkpoint) == null) {
                "Checkpoint $point is already configured"
            }
        }

        private fun newPause(name: String, participants: Int): Pause {
            check(participants in 1..capacity) {
                "$name needs $participants participants, but the scenario has $capacity workers"
            }
            return Pause(name, participants).also { pauses += it }
        }

        internal fun close() {
            pauses.forEach { it.release() }
            executor.shutdown()
            if (!executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                tasks.forEach { it.cancel() }
                executor.shutdownNow()
                assertTrue(
                    executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "Concurrency workers did not terminate"
                )
                throw AssertionError("Concurrency workers exceeded the cleanup timeout")
            }
            // A forgotten await must not hide a failed worker behind a passing test.
            tasks.filterNot { it.observed }.forEach { it.await() }
        }
    }

    class Task<T> internal constructor(
        private val name: String,
        private val future: Future<Result<T>>
    ) {
        internal var observed = false
            private set

        fun await(): T = outcome().getOrThrow()

        fun failure(): Throwable = assertNotNull(
            outcome().exceptionOrNull(), "$name unexpectedly succeeded"
        )

        fun outcome(): Result<T> {
            val result = try {
                future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (exception: TimeoutException) {
                throw AssertionError("Timed out waiting for $name", exception)
            }
            observed = true
            return result
        }

        internal fun cancel() { future.cancel(true) }
    }

    class Pause internal constructor(val name: String, participants: Int) {
        private val reached = CountDownLatch(participants)
        private val released = CountDownLatch(1)
        private val firstHit = AtomicReference<Hit?>()

        fun awaitReached() {
            assertTrue(
                reached.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "Not all participants reached $name"
            )
        }

        fun release() { released.countDown() }

        internal fun awaitHit(): Hit {
            awaitReached()
            return assertNotNull(firstHit.get(), "No transaction recorded at $name")
        }

        internal fun reach(hit: Hit? = null) {
            hit?.let { firstHit.compareAndSet(null, it) }
            reached.countDown()
            assertTrue(
                released.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "$name was not released"
            )
        }
    }

    class Observation internal constructor(val name: String) {
        private val recorded = CountDownLatch(1)
        @Volatile private var hit: Hit? = null

        @Synchronized
        internal fun record(hit: Hit) {
            if (this.hit != null) return
            this.hit = hit
            recorded.countDown()
        }

        internal fun awaitHit(): Hit {
            assertTrue(
                recorded.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "$name was not reached"
            )
            return assertNotNull(hit)
        }
    }

    class ResourceHolders internal constructor(
        internal val pause: Pause,
        private val tasks: List<Task<Unit>>
    ) {
        fun release() {
            pause.release()
            tasks.forEach { it.await() }
        }
    }

    class AdvisoryLockProbe internal constructor(private val tryLock: () -> Boolean) {
        fun assertAvailable() { assertTrue(tryLock(), "Advisory lock is still held") }
        fun assertUnavailable() { assertFalse(tryLock(), "Advisory lock was not held") }
    }

    internal data class Hit(val backendPid: Int?, val value: Any? = null)

    private class Checkpoint(
        private val matching: Any? = null,
        private var skip: Int = 0,
        val pause: Pause? = null,
        val observation: Observation? = null
    ) {
        @Synchronized
        fun accepts(value: Any?): Boolean {
            if (matching != null && matching != value) return false
            if (skip > 0) {
                skip -= 1
                return false
            }
            return true
        }
    }

    private fun currentTransactionPid(): Int? =
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            jdbcTemplate.queryForObject<Int>("SELECT PG_BACKEND_PID()")
        } else null

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        do {
            if (condition()) return
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)
        throw AssertionError(message)
    }

    private companion object {
        const val TIMEOUT_SECONDS = 15L
    }
}
