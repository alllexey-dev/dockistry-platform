package io.dockistry.platform.projects.application

import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.iam.domain.PrincipalType
import io.dockistry.platform.iam.domain.RoleBinding
import io.dockistry.platform.iam.domain.SystemPermissionNames
import io.dockistry.platform.projects.domain.Project
import io.dockistry.platform.projects.domain.dto.ProjectInfoDto
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
import kotlin.test.assertTrue

@SpringBootTest
@Import(TestcontainersConfig::class)
@Testcontainers
@Transactional
class ProjectReadServiceIntegrationTests {
    @Autowired
    private lateinit var projectReadService: ProjectReadService

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    @Test
    fun `list visible projects returns empty list for user without readable projects`() {
        val fixtures = fixtures()
        fixtures.project(
            name = "Hidden ${fixtures.unique()}",
            slug = "hidden-${fixtures.unique()}"
        )
        val user = fixtures.user()
        fixtures.flush()

        val projects = projectReadService.listVisibleProjects(user.principal)

        assertTrue(projects.isEmpty())
    }

    @Test
    fun `list visible projects returns one project visible to user directly`() {
        val fixtures = fixtures()
        val owner = fixtures.user(username = "owner-${fixtures.unique()}")
        val visibleProject = fixtures.project(
            name = "Visible ${fixtures.unique()}",
            slug = "visible-${fixtures.unique()}",
            ownerPrincipal = owner.principal
        )
        fixtures.project(
            name = "Hidden ${fixtures.unique()}",
            slug = "hidden-${fixtures.unique()}"
        )
        val user = fixtures.user(username = "reader-${fixtures.unique()}")
        fixtures.grantRead(user.principal, visibleProject)
        fixtures.flush()

        val projects = projectReadService.listVisibleProjects(user.principal)

        assertEquals(listOf(visibleProject.slug), projects.map { it.slug })
        assertProjectDtoMatches(
            projects.single(),
            ExpectedProjectDto(visibleProject, owner.username, PrincipalType.USER)
        )
    }

    @Test
    fun `list visible projects returns several projects visible to user directly`() {
        val fixtures = fixtures()
        val owner = fixtures.user(username = "owner-${fixtures.unique()}")
        val firstVisibleProject = fixtures.project(
            name = "First Visible ${fixtures.unique()}",
            slug = "first-visible-${fixtures.unique()}",
            ownerPrincipal = owner.principal
        )
        val secondVisibleProject = fixtures.project(
            name = "Second Visible ${fixtures.unique()}",
            slug = "second-visible-${fixtures.unique()}",
            ownerPrincipal = owner.principal
        )
        fixtures.project(
            name = "Hidden ${fixtures.unique()}",
            slug = "hidden-${fixtures.unique()}"
        )
        val user = fixtures.user(username = "reader-${fixtures.unique()}")
        fixtures.grantRead(user.principal, firstVisibleProject)
        fixtures.grantRead(user.principal, secondVisibleProject)
        fixtures.flush()

        val projects = projectReadService.listVisibleProjects(user.principal)

        assertEquals(
            setOf(firstVisibleProject.slug, secondVisibleProject.slug),
            projects.map { it.slug }.toSet()
        )
        assertEquals(2, projects.size)
        assertProjectDtosMatch(
            projects,
            ExpectedProjectDto(firstVisibleProject, owner.username, PrincipalType.USER),
            ExpectedProjectDto(secondVisibleProject, owner.username, PrincipalType.USER)
        )
    }

    @Test
    fun `list visible projects returns empty list for user whose group has no readable projects`() {
        val fixtures = fixtures()
        fixtures.project(
            name = "Hidden ${fixtures.unique()}",
            slug = "hidden-${fixtures.unique()}"
        )
        val user = fixtures.user(username = "member-${fixtures.unique()}")
        val group = fixtures.group(name = "group-${fixtures.unique()}")
        user.groups += group
        fixtures.flush()

        val projects = projectReadService.listVisibleProjects(user.principal)

        assertTrue(projects.isEmpty())
    }

    @Test
    fun `list visible projects returns one project visible to user through group access`() {
        val fixtures = fixtures()
        val owner = fixtures.user(username = "owner-${fixtures.unique()}")
        val visibleProject = fixtures.project(
            name = "Visible ${fixtures.unique()}",
            slug = "visible-${fixtures.unique()}",
            ownerPrincipal = owner.principal
        )
        fixtures.project(
            name = "Hidden ${fixtures.unique()}",
            slug = "hidden-${fixtures.unique()}"
        )
        val user = fixtures.user(username = "member-${fixtures.unique()}")
        val group = fixtures.group(name = "group-${fixtures.unique()}")
        user.groups += group
        fixtures.grantRead(group.principal, visibleProject)
        fixtures.flush()

        val projects = projectReadService.listVisibleProjects(user.principal)

        assertEquals(listOf(visibleProject.slug), projects.map { it.slug })
        assertProjectDtoMatches(
            projects.single(),
            ExpectedProjectDto(visibleProject, owner.username, PrincipalType.USER)
        )
    }

    @Test
    fun `list visible projects returns several projects visible to user through group access`() {
        val fixtures = fixtures()
        val owner = fixtures.user(username = "owner-${fixtures.unique()}")
        val firstVisibleProject = fixtures.project(
            name = "First Visible ${fixtures.unique()}",
            slug = "first-visible-${fixtures.unique()}",
            ownerPrincipal = owner.principal
        )
        val secondVisibleProject = fixtures.project(
            name = "Second Visible ${fixtures.unique()}",
            slug = "second-visible-${fixtures.unique()}",
            ownerPrincipal = owner.principal
        )
        fixtures.project(
            name = "Hidden ${fixtures.unique()}",
            slug = "hidden-${fixtures.unique()}"
        )
        val user = fixtures.user(username = "member-${fixtures.unique()}")
        val group = fixtures.group(name = "group-${fixtures.unique()}")
        user.groups += group
        fixtures.grantRead(group.principal, firstVisibleProject)
        fixtures.grantRead(group.principal, secondVisibleProject)
        fixtures.flush()

        val projects = projectReadService.listVisibleProjects(user.principal)

        assertEquals(
            setOf(firstVisibleProject.slug, secondVisibleProject.slug),
            projects.map { it.slug }.toSet()
        )
        assertEquals(2, projects.size)
        assertProjectDtosMatch(
            projects,
            ExpectedProjectDto(firstVisibleProject, owner.username, PrincipalType.USER),
            ExpectedProjectDto(secondVisibleProject, owner.username, PrincipalType.USER)
        )
    }

    @Test
    fun `list visible projects maps group owner principal info for directly readable project`() {
        val fixtures = fixtures()
        val ownerGroup = fixtures.group(name = "owner-group-${fixtures.unique()}")
        val visibleProject = fixtures.project(
            name = "Visible ${fixtures.unique()}",
            slug = "visible-${fixtures.unique()}",
            ownerPrincipal = ownerGroup.principal
        )
        val user = fixtures.user(username = "reader-${fixtures.unique()}")
        fixtures.grantRead(user.principal, visibleProject)
        fixtures.flush()

        val projects = projectReadService.listVisibleProjects(user.principal)

        assertEquals(listOf(visibleProject.slug), projects.map { it.slug })
        assertProjectDtoMatches(
            projects.single(),
            ExpectedProjectDto(visibleProject, ownerGroup.name, PrincipalType.GROUP)
        )
    }

    private fun fixtures(): IamProjectFixtures = IamProjectFixtures(entityManager)

    private fun IamProjectFixtures.grantRead(
        principal: Principal,
        project: Project
    ): RoleBinding {
        return grantSystemPermission(
            principal,
            project.scope,
            SystemPermissionNames.PROJECT_READ.key
        )
    }

    private fun assertProjectDtosMatch(
        projects: List<ProjectInfoDto>,
        vararg expectedProjects: ExpectedProjectDto
    ) {
        val projectsBySlug = projects.associateBy { it.slug }
        expectedProjects.forEach { expectedProject ->
            val project = projectsBySlug.getValue(expectedProject.project.slug)
            assertProjectDtoMatches(project, expectedProject)
        }
    }

    private fun assertProjectDtoMatches(
        projectInfoDto: ProjectInfoDto,
        expectedProject: ExpectedProjectDto
    ) {
        val project = expectedProject.project

        assertEquals(project.name, projectInfoDto.name)
        assertEquals(project.slug, projectInfoDto.slug)
        assertEquals(project.description, projectInfoDto.description)
        assertEquals(project.status, projectInfoDto.status)
        assertEquals(project.ownerPrincipal.id, projectInfoDto.ownerPrincipalInfo.id)
        assertEquals(expectedProject.ownerType, projectInfoDto.ownerPrincipalInfo.type)
        assertEquals(expectedProject.ownerName, projectInfoDto.ownerPrincipalInfo.name)
    }

    private data class ExpectedProjectDto(
        val project: Project,
        val ownerName: String,
        val ownerType: PrincipalType
    )
}
