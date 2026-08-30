package io.dockistry.platform.secrets.application

import io.dockistry.platform.TestClockConfig
import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.iam.domain.SystemPermissionNames
import io.dockistry.platform.secrets.domain.SecretAlreadyExistsException
import io.dockistry.platform.secrets.application.ports.SecretEncryptor
import io.dockistry.platform.secrets.infrastructure.ProjectSecretDelegationRepository
import io.dockistry.platform.secrets.infrastructure.SecretRepository
import io.dockistry.platform.testsupport.IamProjectFixtures
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
    private lateinit var projectSecretDelegationRepository: ProjectSecretDelegationRepository

    @Autowired
    private lateinit var secretEncryptor: SecretEncryptor

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

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

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `create with the same payload is idempotent`() {
        val owner = createOwner()
        val values = mapOf("password" to "very-secret-value")

        val firstId = secretService.create(
            name = "postgres-credentials",
            description = "PostgreSQL credentials",
            values = values,
            ownerPrincipal = owner
        )
        val secondId = secretService.create(
            name = "postgres-credentials",
            description = "PostgreSQL credentials",
            values = values,
            ownerPrincipal = owner
        )

        assertEquals(firstId, secondId)
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `create with the same name and different payload returns conflict`() {
        val owner = createOwner()

        secretService.create(
            name = "postgres-credentials",
            description = "PostgreSQL credentials",
            values = mapOf("password" to "old-value"),
            ownerPrincipal = owner
        )

        assertFailsWith<SecretAlreadyExistsException> {
            secretService.create(
                name = "postgres-credentials",
                description = "PostgreSQL credentials",
                values = mapOf("password" to "new-value"),
                ownerPrincipal = owner
            )
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `delegate persists delegation with requested project and secret`() {
        val (owner, project) = transactionTemplate.execute {
            val fixtures = IamProjectFixtures(entityManager)
            val owner = fixtures.principal()
            val project = fixtures.project(ownerPrincipal = owner)

            fixtures.grantSystemPermission(
                owner,
                project.scope,
                SystemPermissionNames.PROJECT_READ,
                SystemPermissionNames.PROJECT_UPDATE
            )
            entityManager.flush()
            owner to project
        }
        val secretId = secretService.create(
            name = "postgres-credentials",
            description = null,
            values = mapOf("password" to "very-secret-value"),
            ownerPrincipal = owner
        )

        val delegationId = secretService.delegate(
            secretId = secretId,
            projectId = project.id,
            ownerPrincipal = owner
        )
        val repeatedDelegationId = secretService.delegate(
            secretId = secretId,
            projectId = project.id,
            ownerPrincipal = owner
        )

        assertEquals(delegationId, repeatedDelegationId)
        val delegation = projectSecretDelegationRepository.findById(delegationId).orElseThrow()

        assertEquals(delegationId, delegation.id)
        assertEquals(project.id, delegation.project.id)
        assertEquals(secretId, delegation.secret.id)
    }

    private fun createOwner() = transactionTemplate.execute {
        IamProjectFixtures(entityManager).principal()
    }
}
