package io.dockistry.platform.projects.application

import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.iam.domain.InsufficientPermissionException
import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.iam.domain.Role
import io.dockistry.platform.iam.domain.SystemPermissionNames
import io.dockistry.platform.iam.domain.SystemRoleNames
import io.dockistry.platform.iam.infrastructure.RoleRepository
import io.dockistry.platform.projects.domain.Project
import io.dockistry.platform.projects.domain.ProjectMemberNotFoundException
import io.dockistry.platform.projects.domain.ProjectRoleBindingNotFoundException
import io.dockistry.platform.projects.domain.ProjectRoleNotAssignableException
import io.dockistry.platform.testsupport.IamProjectFixtures
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.hibernate.exception.ConstraintViolationException
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest
@Import(TestcontainersConfig::class)
@Testcontainers
@Transactional
class ProjectMemberServiceIntegrationTests {
    @Autowired
    private lateinit var projectMemberService: ProjectMemberService

    @Autowired
    private lateinit var roleRepository: RoleRepository

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    @Test
    fun `list returns direct project members with readable role bindings only`() {
        val fixtures = fixtures()
        val project = fixtures.project()
        val manager = fixtures.principal()
        val directUser = fixtures.user(username = "direct-${fixtures.unique()}")
        val group = fixtures.group(name = "group-${fixtures.unique()}")
        val transitiveUser = fixtures.user(username = "transitive-${fixtures.unique()}")
        val noReadPrincipal = fixtures.principal()
        transitiveUser.groups += group

        val viewerRole = role(SystemRoleNames.PROJECT_VIEWER.key)
        val operatorRole = role(SystemRoleNames.PROJECT_OPERATOR.key)
        val updateOnlyRole = fixtures.role(
            permissions = setOf(fixtures.systemPermission(SystemPermissionNames.PROJECT_UPDATE.key))
        )
        val firstUserBinding = fixtures.bind(directUser.principal, project.scope, viewerRole)
        val secondUserBinding = fixtures.bind(directUser.principal, project.scope, operatorRole)
        val groupBinding = fixtures.bind(group.principal, project.scope, viewerRole)
        fixtures.bind(noReadPrincipal, project.scope, updateOnlyRole)
        fixtures.grantSystemPermission(
            manager,
            fixtures.globalScope(),
            SystemPermissionNames.PROJECT_READ.key
        )
        fixtures.flushAndClear()

        val members = projectMemberService.list(manager, project.id)

        assertEquals(
            setOf(directUser.id, group.id),
            members.map { it.principalInfo.id }.toSet()
        )
        assertFalse(members.any { it.principalInfo.id == transitiveUser.id })
        assertFalse(members.any { it.principalInfo.id == noReadPrincipal.id })

        val userMember = assertNotNull(
            members.firstOrNull { it.principalInfo.id == directUser.id }
        )
        assertEquals(
            setOf(firstUserBinding.id, secondUserBinding.id),
            userMember.roles.map { it.roleBindingId }.toSet()
        )
        assertEquals(
            setOf(viewerRole.id, operatorRole.id),
            userMember.roles.map { it.roleId }.toSet()
        )

        val groupMember = assertNotNull(
            members.firstOrNull { it.principalInfo.id == group.id }
        )
        assertEquals(listOf(groupBinding.id), groupMember.roles.map { it.roleBindingId })
    }

    @Test
    fun `list available roles returns system and current project roles with project read permission`() {
        val fixtures = fixtures()
        val project = fixtures.project()
        val otherProject = fixtures.project()
        val manager = fixtures.principal()
        val projectRead = fixtures.systemPermission(SystemPermissionNames.PROJECT_READ.key)
        val projectUpdate = fixtures.systemPermission(SystemPermissionNames.PROJECT_UPDATE.key)
        val customRole = fixtures.role(
            name = "custom-readable-${fixtures.unique()}",
            permissions = setOf(projectRead, projectUpdate),
            isSystem = false,
            project = project
        )
        val otherProjectRole = fixtures.role(
            name = "other-readable-${fixtures.unique()}",
            permissions = setOf(projectRead),
            isSystem = false,
            project = otherProject
        )
        val noReadRole = fixtures.role(
            name = "custom-no-read-${fixtures.unique()}",
            permissions = setOf(projectUpdate),
            isSystem = false,
            project = project
        )
        fixtures.grantRead(manager, project)
        fixtures.flushAndClear()

        val roles = projectMemberService.listAvailableRoles(manager, project.id)

        val roleNames = roles.map { it.roleName }.toSet()
        assertTrue(roleNames.containsAll(SystemRoleNames.entries.map { it.key }))
        assertTrue(customRole.name in roleNames)
        assertFalse(otherProjectRole.name in roleNames)
        assertFalse(noReadRole.name in roleNames)

        val customRoleDto = assertNotNull(roles.firstOrNull { it.id == customRole.id })
        assertEquals(
            setOf(SystemPermissionNames.PROJECT_READ.key, SystemPermissionNames.PROJECT_UPDATE.key),
            customRoleDto.permissions.map { it.key }.toSet()
        )
    }

    @Test
    fun `assign role creates project scoped role binding`() {
        val fixtures = fixtures()
        val project = fixtures.project()
        val manager = fixtures.principal()
        val subject = fixtures.principal()
        val role = role(SystemRoleNames.PROJECT_VIEWER.key)
        fixtures.grantMemberManagement(manager, project)
        fixtures.flushAndClear()

        val result = projectMemberService.assignRole(
            managerPrincipal = manager,
            projectId = project.id,
            subjectPrincipalId = subject.id,
            roleId = role.id
        )

        assertEquals(role.id, result.roleId)
        assertEquals(
            1L,
            roleBindingCount(project, subject, role, result.roleBindingId)
        )
    }

    @Test
    fun `assign role rejects manager without member management permission`() {
        val fixtures = fixtures()
        val project = fixtures.project()
        val manager = fixtures.principal()
        val subject = fixtures.principal()
        val role = role(SystemRoleNames.PROJECT_VIEWER.key)
        fixtures.grantRead(manager, project)
        fixtures.flushAndClear()

        assertFailsWith<InsufficientPermissionException> {
            projectMemberService.assignRole(
                managerPrincipal = manager,
                projectId = project.id,
                subjectPrincipalId = subject.id,
                roleId = role.id
            )
        }
    }

    @Test
    fun `assign role rejects role that is not assignable in project`() {
        val fixtures = fixtures()
        val project = fixtures.project()
        val manager = fixtures.principal()
        val subject = fixtures.principal()
        val updateOnlyRole = fixtures.role(
            permissions = setOf(fixtures.systemPermission(SystemPermissionNames.PROJECT_UPDATE.key))
        )
        fixtures.grantMemberManagement(manager, project)
        fixtures.flushAndClear()

        assertFailsWith<ProjectRoleNotAssignableException> {
            projectMemberService.assignRole(
                managerPrincipal = manager,
                projectId = project.id,
                subjectPrincipalId = subject.id,
                roleId = updateOnlyRole.id
            )
        }
    }

    @Test
    fun `role binding uniqueness is enforced by database`() {
        val fixtures = fixtures()
        val project = fixtures.project()
        val subject = fixtures.principal()
        val role = role(SystemRoleNames.PROJECT_VIEWER.key)
        fixtures.bind(subject, project.scope, role)
        fixtures.flushAndClear()

        fixtures.bind(subject, project.scope, role)

        assertFailsWith<ConstraintViolationException> {
            fixtures.flush()
        }
    }

    @Test
    fun `delete role binding removes only requested binding`() {
        val fixtures = fixtures()
        val project = fixtures.project()
        val manager = fixtures.principal()
        val subject = fixtures.principal()
        val viewerRole = role(SystemRoleNames.PROJECT_VIEWER.key)
        val operatorRole = role(SystemRoleNames.PROJECT_OPERATOR.key)
        val deletedBinding = fixtures.bind(subject, project.scope, viewerRole)
        val keptBinding = fixtures.bind(subject, project.scope, operatorRole)
        fixtures.grantMemberManagement(manager, project)
        fixtures.flushAndClear()

        projectMemberService.deleteRoleBinding(
            managerPrincipal = manager,
            projectId = project.id,
            subjectPrincipalId = subject.id,
            roleBindingId = deletedBinding.id
        )
        entityManager.flush()

        assertFalse(roleBindingExists(deletedBinding.id))
        assertTrue(roleBindingExists(keptBinding.id))
    }

    @Test
    fun `delete role binding returns not found when binding does not belong to subject in project`() {
        val fixtures = fixtures()
        val project = fixtures.project()
        val manager = fixtures.principal()
        val subject = fixtures.principal()
        val otherSubject = fixtures.principal()
        val binding = fixtures.bind(otherSubject, project.scope, role(SystemRoleNames.PROJECT_VIEWER.key))
        fixtures.grantMemberManagement(manager, project)
        fixtures.flushAndClear()

        assertFailsWith<ProjectRoleBindingNotFoundException> {
            projectMemberService.deleteRoleBinding(
                managerPrincipal = manager,
                projectId = project.id,
                subjectPrincipalId = subject.id,
                roleBindingId = binding.id
            )
        }
    }

    @Test
    fun `delete member removes all project bindings and keeps other scopes`() {
        val fixtures = fixtures()
        val project = fixtures.project()
        val otherProject = fixtures.project()
        val manager = fixtures.principal()
        val subject = fixtures.principal()
        val viewerRole = role(SystemRoleNames.PROJECT_VIEWER.key)
        val operatorRole = role(SystemRoleNames.PROJECT_OPERATOR.key)
        val firstProjectBinding = fixtures.bind(subject, project.scope, viewerRole)
        val secondProjectBinding = fixtures.bind(subject, project.scope, operatorRole)
        val otherProjectBinding = fixtures.bind(subject, otherProject.scope, viewerRole)
        fixtures.grantMemberManagement(manager, project)
        fixtures.flushAndClear()

        projectMemberService.deleteMember(
            managerPrincipal = manager,
            projectId = project.id,
            subjectPrincipalId = subject.id
        )
        entityManager.flush()

        assertFalse(roleBindingExists(firstProjectBinding.id))
        assertFalse(roleBindingExists(secondProjectBinding.id))
        assertTrue(roleBindingExists(otherProjectBinding.id))
    }

    @Test
    fun `delete member returns not found when principal has no project bindings`() {
        val fixtures = fixtures()
        val project = fixtures.project()
        val manager = fixtures.principal()
        val subject = fixtures.principal()
        fixtures.grantMemberManagement(manager, project)
        fixtures.flushAndClear()

        assertFailsWith<ProjectMemberNotFoundException> {
            projectMemberService.deleteMember(
                managerPrincipal = manager,
                projectId = project.id,
                subjectPrincipalId = subject.id
            )
        }
    }

    @Test
    fun `delete member returns not found when project binding does not grant read permission`() {
        val fixtures = fixtures()
        val project = fixtures.project()
        val manager = fixtures.principal()
        val subject = fixtures.principal()
        val updateOnlyRole = fixtures.role(
            permissions = setOf(fixtures.systemPermission(SystemPermissionNames.PROJECT_UPDATE.key))
        )
        val updateOnlyBinding = fixtures.bind(subject, project.scope, updateOnlyRole)
        fixtures.grantMemberManagement(manager, project)
        fixtures.flushAndClear()

        assertFailsWith<ProjectMemberNotFoundException> {
            projectMemberService.deleteMember(
                managerPrincipal = manager,
                projectId = project.id,
                subjectPrincipalId = subject.id
            )
        }
        assertTrue(roleBindingExists(updateOnlyBinding.id))
    }

    private fun fixtures(): IamProjectFixtures = IamProjectFixtures(entityManager)

    private fun role(name: String): Role =
        assertNotNull(roleRepository.findByNameAndIsSystemTrue(name))

    private fun IamProjectFixtures.grantRead(principal: Principal, project: Project) {
        grantSystemPermission(principal, project.scope, SystemPermissionNames.PROJECT_READ.key)
    }

    private fun IamProjectFixtures.grantMemberManagement(
        principal: Principal,
        project: Project
    ) {
        grantSystemPermission(
            principal,
            project.scope,
            SystemPermissionNames.PROJECT_READ.key,
            SystemPermissionNames.PROJECT_MEMBERS_MANAGE.key
        )
    }

    private fun roleBindingCount(
        project: Project,
        subject: Principal,
        role: Role,
        bindingId: UUID
    ): Long {
        return entityManager
            .createQuery(
                """
                select count(binding)
                from RoleBinding binding
                where binding.id = :bindingId
                    and binding.scope.id = :scopeId
                    and binding.subjectPrincipal.id = :principalId
                    and binding.role.id = :roleId
                """.trimIndent(),
                Long::class.javaObjectType
            )
            .setParameter("bindingId", bindingId)
            .setParameter("scopeId", project.scope.id)
            .setParameter("principalId", subject.id)
            .setParameter("roleId", role.id)
            .singleResult
            .toLong()
    }

    private fun roleBindingExists(bindingId: UUID): Boolean {
        return entityManager
            .createQuery(
                """
                select count(binding)
                from RoleBinding binding
                where binding.id = :bindingId
                """.trimIndent(),
                Long::class.javaObjectType
            )
            .setParameter("bindingId", bindingId)
            .singleResult
            .toLong() > 0
    }
}
