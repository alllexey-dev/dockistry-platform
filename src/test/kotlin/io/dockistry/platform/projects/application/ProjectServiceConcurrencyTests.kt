package io.dockistry.platform.projects.application

import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.iam.domain.SystemPermissionNames
import io.dockistry.platform.projects.domain.ProjectAlreadyExistsException
import io.dockistry.platform.projects.infrastructure.ProjectRepository
import io.dockistry.platform.testsupport.IamProjectFixtures
import io.dockistry.platform.testsupport.concurrency.ConcurrencyPoint.BEFORE_PROJECT_SAVE
import io.dockistry.platform.testsupport.concurrency.ConcurrencyTestSupport
import io.dockistry.platform.testsupport.concurrency.RepositoryConcurrencyTestConfiguration
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
import kotlin.test.assertIs

@SpringBootTest
@Testcontainers
@Import(TestcontainersConfig::class, RepositoryConcurrencyTestConfiguration::class)
class ProjectServiceConcurrencyTests @Autowired constructor(
    private val projectService: ProjectService,
    private val projectRepository: ProjectRepository,
    private val concurrency: ConcurrencyTestSupport,
    private val entityManager: EntityManager
) {
    @Test
    fun `concurrent create with the same name returns project already exists to the loser`() {
        val suffix = IamProjectFixtures(entityManager).unique()
        val name = "Racing Project $suffix"

        raceCreates(name to "racing-$suffix-a", name to "racing-$suffix-b")

        assertEquals(1, projectRepository.findAll().count { it.name == name })
    }

    @Test
    fun `concurrent create with the same slug returns project already exists to the loser`() {
        val suffix = IamProjectFixtures(entityManager).unique()
        val slug = "racing-$suffix"

        raceCreates(
            "Racing Project $suffix A" to slug,
            "Racing Project $suffix B" to slug
        )

        assertEquals(1, projectRepository.findAll().count { it.slug == slug })
    }

    /** Runs two creates that both pass the pre-check before either inserts. */
    private fun raceCreates(vararg nameAndSlug: Pair<String, String>) {
        val owner = projectCreator()
        val outcomes = concurrency.run {
            val beforeSave = pauseAt(
                BEFORE_PROJECT_SAVE,
                matching = owner.id,
                participants = 2
            )
            val creates = nameAndSlug.map { (name, slug) ->
                start("create racing project $slug") {
                    projectService.create(name, slug, ownerPrincipalId = owner.id)
                }
            }
            beforeSave.awaitReached()
            beforeSave.release()
            creates.map { it.outcome() }
        }

        assertEquals(1, outcomes.count { it.isSuccess })
        assertIs<ProjectAlreadyExistsException>(
            outcomes.single { it.isFailure }.exceptionOrNull()
        )
    }

    private fun projectCreator(): Principal = concurrency.inNewTransaction {
        val fixtures = IamProjectFixtures(entityManager)
        fixtures.principal().also {
            fixtures.grantSystemPermission(
                it,
                fixtures.globalScope(),
                SystemPermissionNames.PROJECT_CREATE.key
            )
        }
    }
}
