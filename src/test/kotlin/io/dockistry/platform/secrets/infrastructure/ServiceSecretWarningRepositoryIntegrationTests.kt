package io.dockistry.platform.secrets.infrastructure

import io.dockistry.platform.TestClockConfig
import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.secrets.domain.SecretWarningType
import io.dockistry.platform.secrets.domain.ServiceSecretWarning
import io.dockistry.platform.services.domain.ManagedService
import io.dockistry.platform.services.testsupport.managedService
import io.dockistry.platform.testsupport.IamProjectFixtures
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
@Testcontainers
@Transactional
class ServiceSecretWarningRepositoryIntegrationTests {
    @Autowired private lateinit var warningRepository: ServiceSecretWarningRepository
    @Autowired private lateinit var entityManager: EntityManager

    private val now = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `latest warnings preserve different secrets and event types for reused env`() {
        val service = service()
        val outdated = warning(service)

        assertEquals(1, warningRepository.saveOrRefreshActive(outdated))
        assertEquals(1, warningRepository.saveOrRefreshActive(outdated))
        assertEquals(1, warningRepository.saveOrRefreshActive(
            warning(service, secretId = "sec-BBBBBBBBBBBB")
        ))
        assertEquals(1, warningRepository.saveOrRefreshActive(
            warning(service, type = SecretWarningType.DELEGATION_REVOKED)
        ))
        assertEquals(1, warningRepository.saveOrRefreshActive(
            warning(service, type = SecretWarningType.DELETED)
        ))

        val warnings = warningRepository
            .findByServiceAndResolvedAtIsNullOrderByCreatedAtAsc(service)
        assertEquals(4, warnings.size)
        assertEquals(setOf(
            "sec-AAAAAAAAAAAA" to SecretWarningType.OUTDATED,
            "sec-BBBBBBBBBBBB" to SecretWarningType.OUTDATED,
            "sec-AAAAAAAAAAAA" to SecretWarningType.DELEGATION_REVOKED,
            "sec-AAAAAAAAAAAA" to SecretWarningType.DELETED
        ), warnings.map { it.secretId to it.warningType }.toSet())
    }

    @Test
    fun `pinned warnings distinguish versions keys and environment targets`() {
        val service = service()
        val deleted = warning(
            service,
            type = SecretWarningType.DELETED,
            secretVersion = "ver-AAAAAAAAAAAA"
        )

        assertEquals(1, warningRepository.saveOrRefreshActive(deleted))
        assertEquals(1, warningRepository.saveOrRefreshActive(deleted))
        assertEquals(1, warningRepository.saveOrRefreshActive(
            warning(service, SecretWarningType.DELETED,
                secretVersion = "ver-BBBBBBBBBBBB")
        ))
        assertEquals(1, warningRepository.saveOrRefreshActive(
            warning(service, SecretWarningType.DELETED,
                secretVersion = "ver-AAAAAAAAAAAA", secretKey = "username")
        ))
        assertEquals(1, warningRepository.saveOrRefreshActive(
            warning(service, SecretWarningType.DELETED,
                secretVersion = "ver-AAAAAAAAAAAA", envName = "OTHER_PASSWORD")
        ))

        assertEquals(4, warningRepository
            .findByServiceAndResolvedAtIsNullOrderByCreatedAtAsc(service).size)
    }

    @Test
    fun `repeated event re-identifies the active warning instead of adding a row`() {
        val service = service()
        warningRepository.saveOrRefreshActive(warning(service))
        val first = warningRepository.findActiveByServiceIds(listOf(service.id)).single()
        entityManager.clear()

        warningRepository.saveOrRefreshActive(warning(service, createdAt = now.plusSeconds(5)))
        entityManager.clear()

        val refreshed = warningRepository.findActiveByServiceIds(listOf(service.id)).single()
        assertNotEquals(first.id, refreshed.id)
        assertEquals(now.plusSeconds(5), refreshed.createdAt)
        assertEquals(0, warningRepository.saveResolvedBulk(listOf(first.id), now.plusSeconds(6)))
    }

    @Test
    fun `resolved warning allows the same reference to warn again`() {
        val service = service()
        val warning = warning(service)
        assertEquals(1, warningRepository.saveOrRefreshActive(warning))
        val previous = warningRepository
            .findByServiceAndResolvedAtIsNullOrderByCreatedAtAsc(service).single()

        assertEquals(1, warningRepository.saveResolvedBulk(
            listOf(previous.id), now.plusSeconds(1)
        ))
        assertEquals(1, warningRepository.saveOrRefreshActive(warning))
        entityManager.clear()

        val active = warningRepository
            .findByServiceAndResolvedAtIsNullOrderByCreatedAtAsc(service).single()
        assertNotEquals(previous.id, active.id)
        val history = warningRepository.findAll().filter { it.service.id == service.id }
        assertEquals(2, history.size)
        assertEquals(now.plusSeconds(1), history.single {
            it.id == previous.id
        }.resolvedAt)
    }

    private fun service(): ManagedService {
        val fixtures = IamProjectFixtures(entityManager)
        val project = fixtures.project()
        val service = managedService(project, "service-${fixtures.unique()}")
        entityManager.persist(service)
        entityManager.flush()
        return service
    }

    private fun warning(
        service: ManagedService,
        type: SecretWarningType = SecretWarningType.OUTDATED,
        secretId: String = "sec-AAAAAAAAAAAA",
        secretKey: String = "password",
        secretVersion: String? = null,
        envName: String = "PASSWORD",
        createdAt: Instant = now
    ): ServiceSecretWarning = ServiceSecretWarning(
        service = service,
        secretId = secretId,
        secretKey = secretKey,
        secretVersion = secretVersion,
        warningType = type,
        envName = envName,
        createdAt = createdAt
    )
}
