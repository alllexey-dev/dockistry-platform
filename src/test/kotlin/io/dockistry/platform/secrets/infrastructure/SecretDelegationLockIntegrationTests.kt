package io.dockistry.platform.secrets.infrastructure

import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.testsupport.concurrency.ConcurrencyTestConfiguration
import io.dockistry.platform.testsupport.concurrency.ConcurrencyTestSupport
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.IllegalTransactionStateException
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.assertFailsWith

@SpringBootTest
@Import(TestcontainersConfig::class, ConcurrencyTestConfiguration::class)
@Testcontainers
class SecretDelegationLockIntegrationTests {
    @Autowired private lateinit var secretDelegationLock: SecretDelegationLock
    @Autowired private lateinit var concurrency: ConcurrencyTestSupport

    @Test
    fun `lock requires an existing transaction`() {
        assertFailsWith<IllegalTransactionStateException> {
            secretDelegationLock.lock(UUID.randomUUID().mostSignificantBits)
        }
    }

    @Test
    fun `lock excludes another connection until transaction commits`() {
        val key = UUID.randomUUID().mostSignificantBits

        concurrency.withAdvisoryLockProbe(key) { probe ->
            concurrency.inNewTransaction {
                secretDelegationLock.lock(key)
                probe.assertUnavailable()
            }

            probe.assertAvailable()
        }
    }

    @Test
    fun `lock is released when transaction rolls back`() {
        val key = UUID.randomUUID().mostSignificantBits

        concurrency.withAdvisoryLockProbe(key) { probe ->
            concurrency.inRolledBackTransaction {
                secretDelegationLock.lock(key)
                probe.assertUnavailable()
            }

            probe.assertAvailable()
        }
    }
}
