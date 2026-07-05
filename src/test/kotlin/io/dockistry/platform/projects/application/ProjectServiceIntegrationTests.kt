package io.dockistry.platform.projects.application

import io.dockistry.platform.TestClockConfig
import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.iam.domain.RoleBinding
import io.dockistry.platform.iam.domain.ScopeType
import io.dockistry.platform.iam.domain.SystemPermissionNames
import io.dockistry.platform.iam.domain.SystemRoleNames
import io.dockistry.platform.iam.infrastructure.RoleRepository
import io.dockistry.platform.iam.domain.InsufficientPermissionException
import io.dockistry.platform.projects.domain.InvalidProjectSlugException
import io.dockistry.platform.projects.domain.Project
import io.dockistry.platform.projects.domain.ProjectAlreadyExistsException
import io.dockistry.platform.projects.domain.ProjectNotFoundException
import io.dockistry.platform.projects.domain.ProjectStatus
import io.dockistry.platform.projects.infrastructure.ProjectRepository
import io.dockistry.platform.shared.exception.InternalDataIntegrityException
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
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest
@Import(TestcontainersConfig::class, TestClockConfig::class)
@Testcontainers
@Transactional
class ProjectServiceIntegrationTests {
    @Autowired
    private lateinit var projectService: ProjectService

    @Autowired
    private lateinit var projectRepository: ProjectRepository

    @Autowired
    private lateinit var roleRepository: RoleRepository

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    @Test
    fun `create persists project and owner admin binding`() {
        val fixtures = fixtures()
        val owner = fixtures.principal()
        fixtures.grantSystemPermission(owner, fixtures.globalScope(), SystemPermissionNames.PROJECT_CREATE)
        fixtures.flush()

        val project = projectService.create(
            name = "  Cafe\u0301\nDeploy  ",
            slug = "cafe-deploy",
            description = "Deployments",
            ownerPrincipalId = owner.id
        )
        entityManager.flush()

        assertEquals("Café Deploy", project.name)
        assertEquals("cafe-deploy", project.slug)
        assertEquals("Deployments", project.description)
        assertEquals(ProjectStatus.ACTIVE, project.status)
        assertEquals(ScopeType.PROJECT, project.scope.scopeType)
        assertEquals(owner.id, project.ownerPrincipal.id)
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), project.createdAt)
        assertEquals(1L, ownerAdminBindingCount(owner.id, project.scope.id))
    }

    @Test
    fun `create throws internal data integrity exception when owner principal is missing`() {
        assertFailsWith<InternalDataIntegrityException> {
            projectService.create(
                name = "Missing Owner",
                slug = "missing-owner",
                ownerPrincipalId = UUID.randomUUID()
            )
        }
    }

    @Test
    fun `create throws insufficient permission when owner cannot create projects`() {
        val owner = fixtures().principal()

        assertFailsWith<InsufficientPermissionException> {
            projectService.create(
                name = "Forbidden Project",
                slug = "forbidden-project",
                ownerPrincipalId = owner.id
            )
        }
    }

    @Test
    fun `create throws internal data integrity exception when project admin role is missing`() {
        val fixtures = fixtures()
        val owner = fixtures.principal()
        fixtures.grantSystemPermission(owner, fixtures.globalScope(), SystemPermissionNames.PROJECT_CREATE)
        val projectAdmin = roleRepository.findByNameAndIsSystemTrue(SystemRoleNames.PROJECT_ADMIN)
            ?: throw AssertionError("Expected project admin role to be bootstrapped")
        projectAdmin.permissions.clear()
        roleRepository.delete(projectAdmin)
        entityManager.flush()

        assertFailsWith<InternalDataIntegrityException> {
            projectService.create(
                name = "No Admin Role",
                slug = "no-admin-role",
                ownerPrincipalId = owner.id
            )
        }
    }

    @Test
    fun `create converts invalid slug validation failure to invalid project slug exception`() {
        val fixtures = fixtures()
        val owner = fixtures.principal()
        fixtures.grantSystemPermission(owner, fixtures.globalScope(), SystemPermissionNames.PROJECT_CREATE)
        fixtures.flush()

        assertFailsWith<InvalidProjectSlugException> {
            projectService.create(
                name = "Invalid Slug",
                slug = "Invalid Slug",
                ownerPrincipalId = owner.id
            )
        }
    }

    @Test
    fun `create converts duplicate name or slug to project already exists exception`() {
        val fixtures = fixtures()
        val owner = fixtures.principal()
        fixtures.grantSystemPermission(owner, fixtures.globalScope(), SystemPermissionNames.PROJECT_CREATE)
        fixtures.flush()
        projectService.create(
            name = "Duplicate Project",
            slug = "duplicate-project",
            ownerPrincipalId = owner.id
        )

        assertFailsWith<ProjectAlreadyExistsException> {
            projectService.create(
                name = "Duplicate Project",
                slug = "duplicate-project-copy",
                ownerPrincipalId = owner.id
            )
        }
    }

    @Test
    fun `update changes project fields when principal has read and update permission`() {
        val fixtures = fixtures()
        val project = fixtures.project(name = "Old Name", slug = "old-name")
        val principal = fixtures.principal()
        fixtures.grant(projectReader = principal, project = project, SystemPermissionNames.PROJECT_UPDATE)
        fixtures.flush()

        val updated = projectService.update(
            projectId = project.id,
            name = "  New\tName  ",
            slug = "new-name",
            description = "Updated description",
            principal = principal
        )

        assertEquals("New Name", updated.name)
        assertEquals("new-name", updated.slug)
        assertEquals("Updated description", updated.description)
    }

    @Test
    fun `update throws not found before checking permissions`() {
        val principal = fixtures().principal()

        assertFailsWith<ProjectNotFoundException> {
            projectService.update(
                projectId = UUID.randomUUID(),
                name = "Missing",
                slug = "missing",
                principal = principal
            )
        }
    }

    @Test
    fun `update throws not found when principal cannot read`() {
        val fixtures = fixtures()
        val project = fixtures.project()
        val principal = fixtures.principal()

        assertFailsWith<ProjectNotFoundException> {
            projectService.update(
                projectId = project.id,
                name = "Forbidden",
                slug = "forbidden",
                principal = principal
            )
        }
    }

    @Test
    fun `update throws insufficient permission when principal can read but cannot update`() {
        val fixtures = fixtures()
        val project = fixtures.project()
        val principal = fixtures.principal()
        fixtures.grant(projectReader = principal, project = project)
        fixtures.flush()

        assertFailsWith<InsufficientPermissionException> {
            projectService.update(
                projectId = project.id,
                name = "Forbidden",
                slug = "forbidden",
                principal = principal
            )
        }
    }

    @Test
    fun `update converts invalid slug validation failure to invalid project slug exception`() {
        val fixtures = fixtures()
        val project = fixtures.project()
        val principal = fixtures.principal()
        fixtures.grant(projectReader = principal, project = project, SystemPermissionNames.PROJECT_UPDATE)
        fixtures.flush()

        assertFailsWith<InvalidProjectSlugException> {
            projectService.update(
                projectId = project.id,
                name = "Invalid Slug",
                slug = "Invalid Slug",
                principal = principal
            )
        }
    }

    @Test
    fun `update converts duplicate name or slug to project already exists exception`() {
        val fixtures = fixtures()
        val existing = fixtures.project(name = "Existing Project", slug = "existing-project")
        val edited = fixtures.project(name = "Edited Project", slug = "edited-project")
        val principal = fixtures.principal()
        fixtures.grant(projectReader = principal, project = edited, SystemPermissionNames.PROJECT_UPDATE)
        fixtures.flush()

        assertFailsWith<ProjectAlreadyExistsException> {
            projectService.update(
                projectId = edited.id,
                name = existing.name,
                slug = "edited-project-new",
                principal = principal
            )
        }
    }

    @Test
    fun `archive marks project archived when principal has read and archive permission`() {
        val fixtures = fixtures()
        val project = fixtures.project()
        val principal = fixtures.principal()
        fixtures.grant(projectReader = principal, project = project, SystemPermissionNames.PROJECT_ARCHIVE)
        fixtures.flush()

        val archived = projectService.archive(project.id, principal)

        assertEquals(ProjectStatus.ARCHIVED, archived.status)
    }

    @Test
    fun `archive throws not found before checking permissions`() {
        val principal = fixtures().principal()

        assertFailsWith<ProjectNotFoundException> {
            projectService.archive(UUID.randomUUID(), principal)
        }
    }

    @Test
    fun `archive throws not found when principal cannot read`() {
        val fixtures = fixtures()
        val project = fixtures.project()
        val principal = fixtures.principal()

        assertFailsWith<ProjectNotFoundException> {
            projectService.archive(project.id, principal)
        }
    }

    @Test
    fun `archive throws insufficient permission when principal can read but cannot archive`() {
        val fixtures = fixtures()
        val project = fixtures.project()
        val principal = fixtures.principal()
        fixtures.grant(projectReader = principal, project = project)
        fixtures.flush()

        assertFailsWith<InsufficientPermissionException> {
            projectService.archive(project.id, principal)
        }
    }

    @Test
    fun `delete removes project when principal has read and delete permission`() {
        val fixtures = fixtures()
        val project = fixtures.project()
        val principal = fixtures.principal()
        fixtures.grant(projectReader = principal, project = project, SystemPermissionNames.PROJECT_DELETE)
        fixtures.flush()

        val deleted = projectService.delete(project.id, principal)
        entityManager.flush()

        assertEquals(project.id, deleted.id)
        assertFalse(projectRepository.existsById(project.id))
    }

    @Test
    fun `delete throws not found before checking permissions`() {
        val principal = fixtures().principal()

        assertFailsWith<ProjectNotFoundException> {
            projectService.delete(UUID.randomUUID(), principal)
        }
    }

    @Test
    fun `delete throws not found when principal cannot read`() {
        val fixtures = fixtures()
        val project = fixtures.project()
        val principal = fixtures.principal()

        assertFailsWith<ProjectNotFoundException> {
            projectService.delete(project.id, principal)
        }
    }

    @Test
    fun `delete throws insufficient permission when principal can read but cannot delete`() {
        val fixtures = fixtures()
        val project = fixtures.project()
        val principal = fixtures.principal()
        fixtures.grant(projectReader = principal, project = project)
        fixtures.flush()

        assertFailsWith<InsufficientPermissionException> {
            projectService.delete(project.id, principal)
        }
    }

    @Test
    fun `get returns project when principal can read`() {
        val fixtures = fixtures()
        val project = fixtures.project()
        val principal = fixtures.principal()
        fixtures.grant(projectReader = principal, project = project)
        fixtures.flush()

        val found = projectService.get(project.id, principal)

        assertEquals(project.id, found.id)
    }

    @Test
    fun `get throws not found before checking permissions`() {
        val principal = fixtures().principal()

        assertFailsWith<ProjectNotFoundException> {
            projectService.get(UUID.randomUUID(), principal)
        }
    }

    @Test
    fun `get throws not found when principal cannot read`() {
        val fixtures = fixtures()
        val project = fixtures.project()
        val principal = fixtures.principal()

        assertFailsWith<ProjectNotFoundException> {
            projectService.get(project.id, principal)
        }
    }

    @Test
    fun `list returns all projects when principal has global read permission`() {
        val fixtures = fixtures()
        val firstProject = fixtures.project(name = "First ${fixtures.unique()}", slug = "first-${fixtures.unique()}")
        val secondProject = fixtures.project(name = "Second ${fixtures.unique()}", slug = "second-${fixtures.unique()}")
        val principal = fixtures.principal()
        fixtures.grantSystemPermission(principal, fixtures.globalScope(), SystemPermissionNames.PROJECT_READ)
        fixtures.flush()

        val projects = projectService.list(principal)

        assertTrue(projects.map { it.id }.containsAll(listOf(firstProject.id, secondProject.id)))
    }

    @Test
    fun `list returns distinct project scoped readable projects only`() {
        val fixtures = fixtures()
        val readableProject =
            fixtures.project(name = "Readable ${fixtures.unique()}", slug = "readable-${fixtures.unique()}")
        fixtures.project(name = "Unreadable ${fixtures.unique()}", slug = "unreadable-${fixtures.unique()}")
        val principal = fixtures.principal()
        fixtures.grant(projectReader = principal, project = readableProject)
        fixtures.grant(projectReader = principal, project = readableProject)
        fixtures.flush()

        val projects = projectService.list(principal)

        assertEquals(listOf(readableProject.id), projects.map { it.id })
    }

    @Test
    fun `list ignores readable project scope ids that do not belong to projects`() {
        val fixtures = fixtures()
        val principal = fixtures.principal()
        fixtures.grantSystemPermission(principal, fixtures.scope(ScopeType.PROJECT), SystemPermissionNames.PROJECT_READ)
        fixtures.flush()

        assertEquals(listOf(), projectService.list(principal))
    }

    @Test
    fun `list returns projects visible only through group`() {
        val fixtures = fixtures()
        val readableProject = fixtures.project(
            name = "Readable ${fixtures.unique()}",
            slug = "readable-${fixtures.unique()}"
        )

        val user = fixtures.user()
        val group = fixtures.group()
        user.groups += group
        fixtures.grant(projectReader = group.principal, project = readableProject)

        fixtures.flush()

        val projectsVisible = projectService.list(user.principal)
        assertEquals(listOf(readableProject), projectsVisible)
    }

    private fun fixtures(): IamProjectFixtures = IamProjectFixtures(entityManager)

    private fun IamProjectFixtures.grant(
        projectReader: Principal,
        project: Project,
        vararg extraPermissionKeys: String
    ): RoleBinding {
        val permissionKeys = listOf(SystemPermissionNames.PROJECT_READ) + extraPermissionKeys
        return grantSystemPermission(projectReader, project.scope, *permissionKeys.toTypedArray())
    }

    private fun ownerAdminBindingCount(ownerPrincipalId: UUID, scopeId: UUID): Long {
        return entityManager
            .createQuery(
                """
                select count(binding)
                from RoleBinding binding
                where binding.subjectPrincipal.id = :ownerPrincipalId
                    and binding.scope.id = :scopeId
                    and binding.role.name = :roleName
                """.trimIndent(),
                Long::class.javaObjectType
            )
            .setParameter("ownerPrincipalId", ownerPrincipalId)
            .setParameter("scopeId", scopeId)
            .setParameter("roleName", SystemRoleNames.PROJECT_ADMIN)
            .singleResult
            .toLong()
    }
}
