package io.dockistry.platform.secrets.application

import io.dockistry.platform.TestClockConfig
import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.secrets.application.ports.SecretEncryptor
import io.dockistry.platform.secrets.infrastructure.SecretRepository
import io.dockistry.platform.testsupport.IamProjectFixtures
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals

@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
@Testcontainers
@Transactional
class SecretServiceIntegrationTests {
    @Autowired
    private lateinit var secretService: SecretService

    @Autowired
    private lateinit var secretRepository: SecretRepository

    @Autowired
    private lateinit var secretEncryptor: SecretEncryptor

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    @Test
    fun `create persists secret and reads the same data after database round trip`() {
        val owner = IamProjectFixtures(entityManager).principal()
        entityManager.flush()

        val values = mapOf(
            "database" to "app",
            "username" to "app_user",
            "password" to "very-secret-value"
        )
        val secretId = secretService.create(
            name = "postgres-credentials",
            description = "PostgreSQL credentials",
            values = values,
            ownerPrincipal = owner
        )
        entityManager.flush()
        entityManager.clear()

        val persisted = secretService.list(owner).single()

        assertEquals(secretId, persisted.id)
        assertEquals("postgres-credentials", persisted.name)
        assertEquals("PostgreSQL credentials", persisted.description)
        assertEquals(values.keys, persisted.keys)

        val storedSecret = secretRepository.findById(secretId).orElseThrow()
        val decryptedValues = storedSecret.encryptedValues.mapValues { (_, encryptedValue) ->
            secretEncryptor.decrypt(encryptedValue)
        }
        assertEquals(values, decryptedValues)
        assertEquals(0L, persisted.version)
        assertEquals(1, secretRepository.count())
    }
}
