package io.dockistry.platform.secrets.application

import io.dockistry.platform.TestClockConfig
import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.projects.domain.Project
import io.dockistry.platform.secrets.domain.InvalidSecretReferenceException
import io.dockistry.platform.secrets.domain.InvalidSecretReferenceFormatException
import io.dockistry.platform.secrets.domain.ProjectSecretDelegation
import io.dockistry.platform.secrets.domain.Secret
import io.dockistry.platform.secrets.domain.SecretVersion
import io.dockistry.platform.secrets.infrastructure.ManagedServiceSecretReferenceRepository
import io.dockistry.platform.services.domain.ManagedService
import io.dockistry.platform.services.domain.ManagedServiceSecretReference
import io.dockistry.platform.services.testsupport.managedService
import io.dockistry.platform.testsupport.IamProjectFixtures
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
@Testcontainers
@Transactional
class ManagedServiceSecretReferenceServiceIntegrationTests {
    @Autowired
    private lateinit var referenceService: ManagedServiceSecretReferenceService

    @Autowired
    private lateinit var referenceRepository: ManagedServiceSecretReferenceRepository

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    @Test
    fun `delegated latest and pinned references persist and round trip`() {
        val (project, service) = projectAndService()
        val secret = secret(project)
        val oldVersion = version(secret, mapOf("password" to "old"), EARLIER)
        version(secret, mapOf("password" to "new"), LATER)
        delegate(project, secret)
        entityManager.flush()

        val references = listOf(
            reference(secret, "password", "LATEST_PASSWORD"),
            reference(secret, "password", "PINNED_PASSWORD", oldVersion.id)
        )
        referenceService.replace(service, references)
        entityManager.flush()
        entityManager.clear()

        assertEquals(references.toSet(), referenceService.getReferences(service.id).toSet())
        assertEquals(2, referenceRepository.findByServiceId(service.id).size)
    }

    @Test
    fun `reference without project delegation is rejected`() {
        val (project, service) = projectAndService()
        val secret = secret(project)
        version(secret, mapOf("password" to "value"), LATER)
        entityManager.flush()

        val error = assertFailsWith<InvalidSecretReferenceException> {
            referenceService.replace(
                service,
                listOf(reference(secret, "password", "PASSWORD"))
            )
        }

        assertEquals("invalid_secret_reference", error.code)
        assertEquals(0, referenceRepository.findByServiceId(service.id).size)
    }

    @Test
    fun `invalid reference inputs return their expected error codes`() {
        val (project, service) = projectAndService()
        val secret = secret(project)
        version(secret, mapOf("password" to "value"), LATER)
        delegate(project, secret)
        entityManager.flush()

        val invalidFormats = listOf(
            reference(secret, "password", "PASSWORD").copy(secretId = "bad-id"),
            reference(secret, "bad key", "PASSWORD"),
            reference(secret, "password", "BAD-NAME!"),
            reference(secret, "password", "PASSWORD", "bad-version")
        )
        invalidFormats.forEach { invalid ->
            val error = assertFailsWith<InvalidSecretReferenceFormatException> {
                referenceService.replace(service, listOf(invalid))
            }
            assertEquals("invalid_secret_reference_format", error.code)
        }

        val unresolved = listOf(
            reference(secret, "missing", "PASSWORD"),
            reference(secret, "password", "PASSWORD", "ver-AAAAAAAAAAAA")
        )
        unresolved.forEach { invalid ->
            val error = assertFailsWith<InvalidSecretReferenceException> {
                referenceService.replace(service, listOf(invalid))
            }
            assertEquals("invalid_secret_reference", error.code)
        }

    }

    @Test
    fun `null version resolves latest by created at instead of insertion order`() {
        val (project, service) = projectAndService()
        val secret = secret(project)
        val latest = version(secret, mapOf("latest_only" to "new"), LATER)
        version(secret, mapOf("older_only" to "old"), EARLIER)
        version(secret, mapOf("middle_only" to "middle"), MIDDLE)
        delegate(project, secret)
        entityManager.flush()
        entityManager.clear()

        val currentService = entityManager.find(ManagedService::class.java, service.id)
        referenceService.replace(
            currentService,
            listOf(reference(secret, "latest_only", "LATEST"))
        )
        entityManager.flush()

        assertEquals(null, referenceRepository.findByServiceId(service.id).single().secretVersion)
        assertEquals(
            latest.id,
            entityManager.createNativeQuery(
                """
                    SELECT id FROM secret_versions
                    WHERE secret_id = :secretId
                    ORDER BY created_at DESC, id
                    LIMIT 1
                """.trimIndent()
            ).setParameter("secretId", secret.id).singleResult
        )
    }

    @Test
    fun `pinned version belonging to another secret is invalid reference`() {
        val (project, service) = projectAndService()
        val requestedSecret = secret(project)
        val otherSecret = secret(project)
        version(requestedSecret, mapOf("password" to "requested"), EARLIER)
        val otherVersion = version(otherSecret, mapOf("password" to "other"), LATER)
        delegate(project, requestedSecret)
        delegate(project, otherSecret)
        entityManager.flush()

        val error = assertFailsWith<InvalidSecretReferenceException> {
            referenceService.replace(
                service,
                listOf(reference(requestedSecret, "password", "PASSWORD", otherVersion.id))
            )
        }

        assertEquals("invalid_secret_reference", error.code)
        assertEquals(0, referenceRepository.findByServiceId(service.id).size)
    }

    private fun projectAndService(): Pair<Project, ManagedService> {
        val fixtures = IamProjectFixtures(entityManager)
        val project = fixtures.project()
        val service = managedService(project, "service-${fixtures.unique()}")
        entityManager.persist(service)
        return project to service
    }

    private fun secret(project: Project): Secret {
        val secret = Secret(
            name = "secret-${IamProjectFixtures(entityManager).unique()}",
            ownerPrincipal = project.ownerPrincipal,
            createdAt = EARLIER,
            updatedAt = EARLIER
        )
        entityManager.persist(secret)
        return secret
    }

    private fun version(secret: Secret, values: Map<String, String>, createdAt: Instant): SecretVersion {
        val version = SecretVersion(secret, values, createdAt)
        entityManager.persist(version)
        return version
    }

    private fun delegate(project: Project, secret: Secret) {
        entityManager.persist(ProjectSecretDelegation(secret, project, EARLIER, EARLIER))
    }

    private fun reference(
        secret: Secret,
        key: String,
        envName: String,
        version: String? = null
    ) = ManagedServiceSecretReference(secret.id, key, envName, version)

    companion object {
        private val EARLIER = Instant.parse("2025-01-01T00:00:00Z")
        private val MIDDLE = Instant.parse("2025-06-01T00:00:00Z")
        private val LATER = Instant.parse("2026-01-01T00:00:00Z")
    }
}
