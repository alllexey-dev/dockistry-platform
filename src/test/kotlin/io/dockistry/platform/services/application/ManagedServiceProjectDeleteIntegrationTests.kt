package io.dockistry.platform.services.application

import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.iam.domain.SystemPermissionNames
import io.dockistry.platform.projects.domain.Project
import io.dockistry.platform.projects.domain.ProjectNotFoundException
import io.dockistry.platform.projects.infrastructure.ProjectRepository
import io.dockistry.platform.services.application.dto.ManagedServiceCreateCommand
import io.dockistry.platform.services.testsupport.managedServiceDraft
import io.dockistry.platform.testsupport.IamProjectFixtures
import io.dockistry.platform.testsupport.concurrency.ClockConcurrencyTestConfiguration
import io.dockistry.platform.testsupport.concurrency.ConcurrencyPoint.CLOCK_INSTANT
import io.dockistry.platform.testsupport.concurrency.ConcurrencyTestSupport
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.assertInstanceOf
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.Test

@SpringBootTest
@Testcontainers
@Import(
    TestcontainersConfig::class,
    ClockConcurrencyTestConfiguration::class
)
class ManagedServiceProjectDeleteIntegrationTests @Autowired constructor(
    private val concurrency: ConcurrencyTestSupport,
    private val entityManager: EntityManager,
    private val projectRepository: ProjectRepository,
    private val managedServiceService: ManagedServiceService
) {
    @Test
    fun `service create returns 404 after concurrent project delete wins`() {
        val context = persistProjectContext()
        concurrency.run {
            // Create has read the project but has not persisted the service yet.
            val beforeCreate = pauseAt(CLOCK_INSTANT)
            val create = start("create service") {
                managedServiceService.create(
                    ManagedServiceCreateCommand(
                        projectId = context.project.id,
                        principal = context.owner,
                        name = "blocking-service",
                        configuration = managedServiceDraft()
                    )
                )
            }
            beforeCreate.awaitReached()
            concurrency.inNewTransaction { projectRepository.delete(context.project) }
            beforeCreate.release()
            assertInstanceOf<ProjectNotFoundException>(create.failure())
        }
    }

    private fun persistProjectContext(): ProjectContext = concurrency.inNewTransaction {
        val fixtures = fixtures(entityManager)
        val owner = fixtures.principal()
        val project = fixtures.project(ownerPrincipal = owner)
        fixtures.grantSystemPermission(
            principal = owner,
            scope = project.scope,
            SystemPermissionNames.PROJECT_READ.key,
            SystemPermissionNames.PROJECT_SERVICES_MANAGE.key
        )

        ProjectContext(
            owner = owner,
            project = project
        )
    }

    private data class ProjectContext(
        val owner: Principal,
        val project: Project
    )

    private fun fixtures(entityManager: EntityManager) = IamProjectFixtures(entityManager)
}
