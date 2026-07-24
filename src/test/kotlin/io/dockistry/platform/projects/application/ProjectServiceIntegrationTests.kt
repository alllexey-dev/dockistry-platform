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
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
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

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    private val newTransactionTemplate: TransactionTemplate by lazy {
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }
    }

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
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `update changes project fields when principal has read and update permission`() {
        val context = committedUpdateContext(
            SystemPermissionNames.PROJECT_READ,
            SystemPermissionNames.PROJECT_UPDATE,
            name = "Old Name",
            slug = "old-name"
        )

        try {
            val updated = projectService.update(
                projectId = context.projectId,
                name = "  New\tName  ",
                slug = "new-name",
                description = "Updated description",
                expectedVersion = context.version,
                principal = context.principal
            )

            assertEquals("New Name", updated.name)
            assertEquals("new-name", updated.slug)
            assertEquals("Updated description", updated.description)
        } finally {
            cleanupCommittedProject(context.projectId)
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `update throws not found before checking permissions`() {
        val principal = committedPrincipal()

        assertFailsWith<ProjectNotFoundException> {
            projectService.update(
                projectId = UUID.randomUUID(),
                name = "Missing",
                slug = "missing",
                expectedVersion = 0,
                principal = principal
            )
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `update throws not found when principal cannot read`() {
        val context = committedUpdateContext()

        try {
            assertFailsWith<ProjectNotFoundException> {
                projectService.update(
                    projectId = context.projectId,
                    name = "Forbidden",
                    slug = "forbidden",
                    expectedVersion = context.version,
                    principal = context.principal
                )
            }
        } finally {
            cleanupCommittedProject(context.projectId)
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `update throws insufficient permission when principal can read but cannot update`() {
        val context = committedUpdateContext(SystemPermissionNames.PROJECT_READ)

        try {
            assertFailsWith<InsufficientPermissionException> {
                projectService.update(
                    projectId = context.projectId,
                    name = "Forbidden",
                    slug = "forbidden",
                    expectedVersion = context.version,
                    principal = context.principal
                )
            }
        } finally {
            cleanupCommittedProject(context.projectId)
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `update converts invalid slug validation failure to invalid project slug exception`() {
        val context = committedUpdateContext(
            SystemPermissionNames.PROJECT_READ,
            SystemPermissionNames.PROJECT_UPDATE
        )

        try {
            assertFailsWith<InvalidProjectSlugException> {
                projectService.update(
                    projectId = context.projectId,
                    name = "Invalid Slug",
                    slug = "Invalid Slug",
                    expectedVersion = context.version,
                    principal = context.principal
                )
            }
        } finally {
            cleanupCommittedProject(context.projectId)
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `update converts duplicate name or slug to project already exists exception`() {
        val context = committedDuplicateUpdateContext()

        try {
            assertFailsWith<ProjectAlreadyExistsException> {
                projectService.update(
                    projectId = context.projectId,
                    name = context.existingName,
                    slug = "edited-project-new",
                    expectedVersion = context.version,
                    principal = context.principal
                )
            }
        } finally {
            context.projectIds.forEach(::cleanupCommittedProject)
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
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `delete removes project when principal has read and delete permission`() {
        val context = committedDeleteContext(SystemPermissionNames.PROJECT_DELETE)

        try {
            val deleted = projectService.delete(context.projectId, context.principal)

            assertEquals(context.projectId, deleted.id)
            assertFalse(inNewTransaction { projectRepository.existsById(context.projectId) })
        } finally {
            cleanupCommittedProject(context.projectId)
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `delete throws not found before checking permissions`() {
        val principal = committedPrincipal()

        assertFailsWith<ProjectNotFoundException> {
            projectService.delete(UUID.randomUUID(), principal)
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `delete throws not found when principal cannot read`() {
        val context = committedDeleteContext()

        try {
            assertFailsWith<ProjectNotFoundException> {
                projectService.delete(context.projectId, context.principal)
            }
        } finally {
            cleanupCommittedProject(context.projectId)
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `delete throws insufficient permission when principal can read but cannot delete`() {
        val context = committedDeleteContext(SystemPermissionNames.PROJECT_READ)

        try {
            assertFailsWith<InsufficientPermissionException> {
                projectService.delete(context.projectId, context.principal)
            }
        } finally {
            cleanupCommittedProject(context.projectId)
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

    private fun committedPrincipal(): Principal = inNewTransaction {
        fixtures().principal()
    }

    private fun committedUpdateContext(
        vararg permissionKeys: String,
        name: String = "Project ${UUID.randomUUID()}",
        slug: String = "project-${UUID.randomUUID()}"
    ): ProjectUpdateContext = inNewTransaction {
        val fixtures = fixtures()
        val project = fixtures.project(name = name, slug = slug)
        val principal = fixtures.principal()
        if (permissionKeys.isNotEmpty()) {
            fixtures.grantSystemPermission(
                principal,
                project.scope,
                *permissionKeys
            )
        }
        fixtures.flush()

        ProjectUpdateContext(
            projectId = project.id,
            version = project.version,
            principal = principal
        )
    }

    private fun committedDuplicateUpdateContext(): DuplicateProjectUpdateContext =
        inNewTransaction {
            val fixtures = fixtures()
            val existing = fixtures.project(
                name = "Existing ${fixtures.unique()}",
                slug = "existing-${fixtures.unique()}"
            )
            val edited = fixtures.project(
                name = "Edited ${fixtures.unique()}",
                slug = "edited-${fixtures.unique()}"
            )
            val principal = fixtures.principal()
            fixtures.grant(
                projectReader = principal,
                project = edited,
                SystemPermissionNames.PROJECT_UPDATE
            )
            fixtures.flush()

            DuplicateProjectUpdateContext(
                projectId = edited.id,
                version = edited.version,
                principal = principal,
                existingName = existing.name,
                projectIds = listOf(edited.id, existing.id)
            )
        }

    private fun committedDeleteContext(
        vararg permissionKeys: String
    ): ProjectDeleteContext = inNewTransaction {
        val fixtures = fixtures()
        val project = fixtures.project()
        val principal = fixtures.principal()
        if (permissionKeys.isNotEmpty()) {
            val grantedPermissionKeys =
                (listOf(SystemPermissionNames.PROJECT_READ) + permissionKeys).distinct()
            fixtures.grantSystemPermission(
                principal,
                project.scope,
                *grantedPermissionKeys.toTypedArray()
            )
        }
        fixtures.flush()

        ProjectDeleteContext(project.id, principal)
    }

    private fun cleanupCommittedProject(projectId: UUID) {
        inNewTransaction {
            entityManager.createQuery(
                "delete from ManagedService service where service.project.id = :projectId"
            )
                .setParameter("projectId", projectId)
                .executeUpdate()
            projectRepository.findById(projectId).ifPresent(projectRepository::delete)
            projectRepository.flush()
        }
    }

    private fun <T : Any> inNewTransaction(block: () -> T): T {
        return checkNotNull(newTransactionTemplate.execute { block() })
    }

    private data class ProjectUpdateContext(
        val projectId: UUID,
        val version: Long,
        val principal: Principal
    )

    private data class DuplicateProjectUpdateContext(
        val projectId: UUID,
        val version: Long,
        val principal: Principal,
        val existingName: String,
        val projectIds: List<UUID>
    )

    private data class ProjectDeleteContext(
        val projectId: UUID,
        val principal: Principal
    )

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
