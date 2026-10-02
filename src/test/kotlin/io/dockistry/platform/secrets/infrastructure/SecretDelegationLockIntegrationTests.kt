package io.dockistry.platform.secrets.infrastructure

import io.dockistry.platform.TestcontainersConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest
@Import(TestcontainersConfig::class)
@Testcontainers
class SecretDelegationLockIntegrationTests {
    @Autowired private lateinit var secretDelegationLock: SecretDelegationLock
    @Autowired private lateinit var transactionTemplate: TransactionTemplate
    @Autowired private lateinit var dataSource: DataSource

    @Test
    fun `lock requires an existing transaction`() {
        assertFailsWith<IllegalTransactionStateException> {
            secretDelegationLock.lock(UUID.randomUUID().mostSignificantBits)
        }
    }

    @Test
    fun `lock excludes another connection until transaction commits`() {
        val key = UUID.randomUUID().mostSignificantBits

        dataSource.connection.use { otherConnection ->
            transactionTemplate.executeWithoutResult {
                secretDelegationLock.lock(key)
                assertFalse(tryLock(otherConnection, key))
            }

            assertTrue(tryLock(otherConnection, key))
        }
    }

    @Test
    fun `lock is released when transaction rolls back`() {
        val key = UUID.randomUUID().mostSignificantBits

        dataSource.connection.use { otherConnection ->
            transactionTemplate.executeWithoutResult { status ->
                secretDelegationLock.lock(key)
                assertFalse(tryLock(otherConnection, key))
                status.setRollbackOnly()
            }

            assertTrue(tryLock(otherConnection, key))
        }
    }

    private fun tryLock(connection: Connection, key: Long): Boolean =
        connection.prepareStatement("SELECT pg_try_advisory_xact_lock(?)")
            .use { statement ->
                statement.setLong(1, key)
                statement.executeQuery().use { result ->
                    result.next()
                    result.getBoolean(1)
                }
            }
}
