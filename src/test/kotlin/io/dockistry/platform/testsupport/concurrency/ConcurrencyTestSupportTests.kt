package io.dockistry.platform.testsupport.concurrency

import io.dockistry.platform.services.application.ports.ExecutionGate
import io.dockistry.platform.testsupport.concurrency.ConcurrencyPoint.BEFORE_EXECUTION_GATE_LOCK
import io.dockistry.platform.testsupport.concurrency.ConcurrencyPoint.BEFORE_SECRET_SAVE
import org.mockito.Mockito.mock
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ConcurrencyTestSupportTests {
    private val concurrency = ConcurrencyTestSupport(
        mock(JdbcTemplate::class.java),
        mock(PlatformTransactionManager::class.java)
    )

    @Test
    fun `scenario failure releases paused workers and checkpoints can be reused`() {
        val failure = AssertionError("scenario failed")
        lateinit var worker: ConcurrencyTestSupport.Task<String>

        assertSame(failure, assertFailsWith<AssertionError> {
            concurrency.run {
                val beforeSave = pauseAt(BEFORE_SECRET_SAVE)
                worker = start("paused worker") {
                    concurrency.checkpoint(BEFORE_SECRET_SAVE)
                    "released"
                }
                beforeSave.awaitReached()
                throw failure
            }
        })
        assertEquals("released", worker.await())

        concurrency.run {
            val beforeSave = pauseAt(BEFORE_SECRET_SAVE)
            val next = start("next scenario") {
                concurrency.checkpoint(BEFORE_SECRET_SAVE)
                "next"
            }
            beforeSave.awaitReached()
            beforeSave.release()
            assertEquals("next", next.await())
        }
    }

    @Test
    fun `an unobserved worker failure fails the scenario`() {
        val failure = IllegalStateException("worker failed")
        assertSame(failure, assertFailsWith<IllegalStateException> {
            concurrency.run { start("forgotten worker") { throw failure } }
        })
    }

    @Test
    fun `cleanup failure preserves the original scenario failure`() {
        val scenarioFailure = AssertionError("scenario failed")
        val workerFailure = IllegalStateException("worker failed after release")
        assertSame(scenarioFailure, assertFailsWith<AssertionError> {
            concurrency.run {
                val beforeSave = pauseAt(BEFORE_SECRET_SAVE)
                start("failing paused worker") {
                    concurrency.checkpoint(BEFORE_SECRET_SAVE)
                    throw workerFailure
                }
                beforeSave.awaitReached()
                throw scenarioFailure
            }
        })
        assertSame(workerFailure, scenarioFailure.suppressed.single())
    }

    @Test
    fun `matching checkpoint pauses only the selected operation`() {
        concurrency.run {
            val selectedSave = pauseAt(BEFORE_SECRET_SAVE, matching = "selected")
            val other = start("other save") {
                concurrency.checkpoint(BEFORE_SECRET_SAVE, "other")
                "other saved"
            }
            assertEquals("other saved", other.await())
            val selected = start("selected save") {
                concurrency.checkpoint(BEFORE_SECRET_SAVE, "selected")
                "selected saved"
            }
            selectedSave.awaitReached()
            selectedSave.release()
            assertEquals("selected saved", selected.await())
        }
    }

    @Test
    fun `simultaneous operations preserve successful values and original errors`() {
        val failure = Error("simulated executor death")
        val outcomes = concurrency.run {
            startTogether({ "saved" }, { throw failure })
        }
        assertEquals("saved", outcomes.first().getOrThrow())
        assertSame(failure, outcomes.last().exceptionOrNull())
    }

    @Test
    fun `gate holder uses the paused key even after an unrelated gate call`() {
        var heldKey: Long? = null
        val realGate = object : ExecutionGate {
            override fun <T> withLock(
                advisoryLockKey: Long,
                timeout: Duration,
                block: () -> T
            ): T {
                heldKey = advisoryLockKey
                return block()
            }
        }
        val observedGate = ControllableExecutionGate(realGate, concurrency)
        val timeout = Duration.ofMillis(100)

        concurrency.run {
            val beforeLock = pauseAt(BEFORE_EXECUTION_GATE_LOCK)
            val paused = start("paused gate call") { observedGate.withLock(11L, timeout) {} }
            beforeLock.awaitReached()

            observedGate.withLock(22L, timeout) {}
            holdExecutionGateLockAt(realGate, beforeLock, timeout)
            assertEquals(11L, heldKey)

            beforeLock.release()
            paused.await()
        }
    }
}
