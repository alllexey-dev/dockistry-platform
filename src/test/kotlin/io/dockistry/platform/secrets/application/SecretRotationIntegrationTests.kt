package io.dockistry.platform.secrets.application

import io.dockistry.platform.TestClockConfig
import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.secrets.domain.ManagedServiceSecretReferenceEntity
import io.dockistry.platform.secrets.domain.ProjectSecretDelegation
import io.dockistry.platform.secrets.domain.Secret
import io.dockistry.platform.secrets.domain.SecretVersion
import io.dockistry.platform.secrets.domain.SecretWarningType
import io.dockistry.platform.secrets.infrastructure.SecretVersionRepository
import io.dockistry.platform.secrets.infrastructure.ServiceSecretWarningRepository
import io.dockistry.platform.services.testsupport.managedService
import io.dockistry.platform.testsupport.IamProjectFixtures
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Clock
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
@Testcontainers
@Transactional
class SecretRotationIntegrationTests {
    @Autowired
    private lateinit var secretService: SecretService

    @Autowired
    private lateinit var versionRepository: SecretVersionRepository

    @Autowired
    private lateinit var warningRepository: ServiceSecretWarningRepository

    @Autowired
    private lateinit var clock: Clock

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    @Test
    fun `rotate changes the default version from old to new`() {
        val owner = IamProjectFixtures(entityManager).principal()
        val secret = secretWithVersion(owner)
        entityManager.flush()

        val oldVersionId = secret.versions.single().id
        assertEquals(oldVersionId, versionRepository.findLatestBySecretId(secret.id).id)

        val newVersionId = secretService.rotate(
            secret.id, mapOf("password" to "new-value"), owner
        )
        entityManager.flush()
        entityManager.clear()

        assertNotEquals(oldVersionId, newVersionId)
        assertEquals(newVersionId, versionRepository.findLatestBySecretId(secret.id).id)
    }

    @Test
    fun `rotate creates an outdated warning for a delegated latest reference`() {
        val fixtures = IamProjectFixtures(entityManager)
        val owner = fixtures.principal()
        val project = fixtures.project(ownerPrincipal = owner)
        val service = managedService(project, "service-${fixtures.unique()}")
        entityManager.persist(service)
        val secret = secretWithVersion(owner)
        val now = clock.instant()
        entityManager.persist(ProjectSecretDelegation(secret, project, now, now))
        entityManager.persist(ManagedServiceSecretReferenceEntity(
            service = service,
            secret = secret,
            secretKey = "password",
            secretVersion = null,
            envName = "PASSWORD"
        ))
        entityManager.flush()

        assertTrue(warningRepository.findByServiceAndResolvedAtIsNullOrderByCreatedAtAsc(
            service
        ).isEmpty())

        secretService.rotate(secret.id, mapOf("password" to "new-value"), owner)
        entityManager.flush()
        entityManager.clear()

        val warning = warningRepository.findActiveByServiceAndType(
            service.id, SecretWarningType.OUTDATED
        ).single()
        assertEquals(service.id, warning.service.id)
        assertEquals(secret.id, warning.secretId)
        assertEquals("password", warning.secretKey)
        assertEquals("PASSWORD", warning.envName)
        assertNull(warning.secretVersion)
        assertNull(warning.resolvedAt)
    }

    @Test
    fun `rotate does not create an outdated warning for a pinned reference`() {
        val fixtures = IamProjectFixtures(entityManager)
        val owner = fixtures.principal()
        val project = fixtures.project(ownerPrincipal = owner)
        val service = managedService(project, "service-${fixtures.unique()}")
        entityManager.persist(service)
        val secret = secretWithVersion(owner)
        val now = clock.instant()
        entityManager.persist(ProjectSecretDelegation(secret, project, now, now))
        entityManager.persist(ManagedServiceSecretReferenceEntity(
            service = service,
            secret = secret,
            secretKey = "password",
            secretVersion = secret.versions.single().id,
            envName = "PASSWORD"
        ))
        entityManager.flush()

        secretService.rotate(secret.id, mapOf("password" to "new-value"), owner)
        entityManager.flush()
        entityManager.clear()

        assertTrue(warningRepository.findActiveByServiceAndType(
            service.id, SecretWarningType.OUTDATED
        ).isEmpty())
    }

    private fun secretWithVersion(owner: Principal): Secret {
        val now = clock.instant().minusSeconds(1)
        val secret = Secret("postgres-credentials", null, owner, now, now)
        secret.addVersion(SecretVersion(secret, mapOf("password" to "old-value"), now))
        entityManager.persist(secret)
        return secret
    }
}
