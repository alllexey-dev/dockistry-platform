package io.dockistry.platform.projects.api

import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.auth.application.TokenManagementService
import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.iam.domain.PrincipalType
import io.dockistry.platform.iam.domain.Role
import io.dockistry.platform.iam.domain.SystemPermissionNames
import io.dockistry.platform.iam.domain.SystemRoleNames
import io.dockistry.platform.iam.domain.User
import io.dockistry.platform.iam.infrastructure.RoleRepository
import io.dockistry.platform.projects.domain.Project
import io.dockistry.platform.projects.domain.dto.AssignRoleRequest
import io.dockistry.platform.projects.domain.dto.AssignRoleResponse
import io.dockistry.platform.projects.domain.dto.ProjectMemberDto
import io.dockistry.platform.projects.domain.dto.RoleDto
import io.dockistry.platform.shared.api.ApiResponse
import io.dockistry.platform.testsupport.IamProjectFixtures
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MockMvcResultMatchersDsl
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.junit.jupiter.Testcontainers
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class)
@Testcontainers
@Transactional
class ProjectMemberControllerIntegrationTests {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var tokenManagementService: TokenManagementService

    @Autowired
    private lateinit var roleRepository: RoleRepository

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    @Test
    fun `list members returns direct project members with readable role bindings`() {
        val fixtures = fixtures()
        val manager = authenticatedUser(fixtures, "member-reader")
        val project = fixtures.project()
        val directUser = fixtures.user(username = "direct-${fixtures.unique()}")
        val group = fixtures.group(name = "operators-${fixtures.unique()}")
        val transitiveUser = fixtures.user(username = "transitive-${fixtures.unique()}")
        transitiveUser.groups += group
        val userBinding = fixtures.bind(
            directUser.principal,
            project.scope,
            role(SystemRoleNames.PROJECT_VIEWER)
        )
        val groupBinding = fixtures.bind(
            group.principal,
            project.scope,
            role(SystemRoleNames.PROJECT_OPERATOR)
        )
        fixtures.grantSystemPermission(
            manager.user.principal,
            fixtures.globalScope(),
            SystemPermissionNames.PROJECT_READ
        )
        fixtures.flushAndClear()

        val response = mockMvc.get("/api/projects/${project.id}/members") {
            bearer(manager)
        }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.errorMessage") { value(nullValue()) }
            jsonPath("$.errorCode") { value(nullValue()) }
        }.andReturn().apiResponse<List<ProjectMemberDto>>()

        val members = assertNotNull(response.data)
        assertEquals(setOf(directUser.id, group.id), members.map { it.principalInfo.id }.toSet())
        assertFalse(members.any { it.principalInfo.id == transitiveUser.id })

        val userMember = assertNotNull(members.firstOrNull { it.principalInfo.id == directUser.id })
        assertEquals(PrincipalType.USER, userMember.principalInfo.type)
        assertEquals(directUser.username, userMember.principalInfo.name)
        assertEquals(listOf(userBinding.id), userMember.roles.map { it.roleBindingId })

        val groupMember = assertNotNull(members.firstOrNull { it.principalInfo.id == group.id })
        assertEquals(PrincipalType.GROUP, groupMember.principalInfo.type)
        assertEquals(group.name, groupMember.principalInfo.name)
        assertEquals(listOf(groupBinding.id), groupMember.roles.map { it.roleBindingId })
    }

    @Test
    fun `list assignable roles returns system and project roles with permissions`() {
        val fixtures = fixtures()
        val manager = authenticatedUser(fixtures, "role-reader")
        val project = fixtures.project()
        val otherProject = fixtures.project()
        val projectRead = fixtures.systemPermission(SystemPermissionNames.PROJECT_READ)
        val projectUpdate = fixtures.systemPermission(SystemPermissionNames.PROJECT_UPDATE)
        val customRole = fixtures.role(
            name = "deploy-viewer-${fixtures.unique()}",
            permissions = setOf(projectRead, projectUpdate),
            isSystem = false,
            project = project
        )
        val otherProjectRole = fixtures.role(
            name = "other-viewer-${fixtures.unique()}",
            permissions = setOf(projectRead),
            isSystem = false,
            project = otherProject
        )
        val noReadRole = fixtures.role(
            name = "deploy-editor-no-read-${fixtures.unique()}",
            permissions = setOf(projectUpdate),
            isSystem = false,
            project = project
        )
        fixtures.grantRead(manager.user.principal, project)
        fixtures.flushAndClear()

        val response = mockMvc.get("/api/projects/${project.id}/assignable-roles") {
            bearer(manager)
        }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.errorMessage") { value(nullValue()) }
            jsonPath("$.errorCode") { value(nullValue()) }
        }.andReturn().apiResponse<List<RoleDto>>()

        val roles = assertNotNull(response.data)
        val roleNames = roles.map { it.roleName }.toSet()
        assertTrue(roleNames.containsAll(projectRoleNames()))
        assertTrue(customRole.name in roleNames)
        assertFalse(otherProjectRole.name in roleNames)
        assertFalse(noReadRole.name in roleNames)

        val customRoleDto = assertNotNull(roles.firstOrNull { it.id == customRole.id })
        assertEquals(
            setOf(SystemPermissionNames.PROJECT_READ, SystemPermissionNames.PROJECT_UPDATE),
            customRoleDto.permissions.map { it.key }.toSet()
        )
    }

    @Test
    fun `assign role creates binding and returns only role binding id`() {
        val fixtures = fixtures()
        val manager = authenticatedUser(fixtures, "member-manager")
        val project = fixtures.project()
        val subject = fixtures.principal()
        val viewerRole = role(SystemRoleNames.PROJECT_VIEWER)
        fixtures.grantMemberManagement(manager.user.principal, project)
        fixtures.flushAndClear()

        val response = mockMvc.post("/api/projects/${project.id}/members/${subject.id}/roles") {
            bearer(manager)
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(AssignRoleRequest(viewerRole.id))
        }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.data.roleBindingId") { exists() }
            jsonPath("$.data.roleId") { doesNotExist() }
            jsonPath("$.errorMessage") { value(nullValue()) }
            jsonPath("$.errorCode") { value(nullValue()) }
        }.andReturn().apiResponse<AssignRoleResponse>()

        val bindingId = assertNotNull(response.data).roleBindingId
        assertEquals(1L, roleBindingCount(project, subject, viewerRole, bindingId))
    }

    @Test
    fun `assign role returns validation error when role id is missing`() {
        val fixtures = fixtures()
        val manager = authenticatedUser(fixtures, "member-manager")
        val project = fixtures.project()
        val subject = fixtures.principal()
        fixtures.grantMemberManagement(manager.user.principal, project)
        fixtures.flushAndClear()

        mockMvc.post("/api/projects/${project.id}/members/${subject.id}/roles") {
            bearer(manager)
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect {
            expectApiError(statusCode = 400, errorCode = "bad_request")
        }
    }

    @Test
    fun `assign role returns forbidden when manager cannot manage members`() {
        val fixtures = fixtures()
        val manager = authenticatedUser(fixtures, "readonly-manager")
        val project = fixtures.project()
        val subject = fixtures.principal()
        val viewerRole = role(SystemRoleNames.PROJECT_VIEWER)
        fixtures.grantRead(manager.user.principal, project)
        fixtures.flushAndClear()

        mockMvc.post("/api/projects/${project.id}/members/${subject.id}/roles") {
            bearer(manager)
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(AssignRoleRequest(viewerRole.id))
        }.andExpect {
            expectApiError(
                statusCode = 403,
                errorCode = "insufficient_permission_for_operation"
            )
        }
    }

    @Test
    fun `assign role returns bad request when role is not assignable`() {
        val fixtures = fixtures()
        val manager = authenticatedUser(fixtures, "member-manager")
        val project = fixtures.project()
        val subject = fixtures.principal()
        val updateOnlyRole = fixtures.role(
            permissions = setOf(fixtures.systemPermission(SystemPermissionNames.PROJECT_UPDATE))
        )
        fixtures.grantMemberManagement(manager.user.principal, project)
        fixtures.flushAndClear()

        mockMvc.post("/api/projects/${project.id}/members/${subject.id}/roles") {
            bearer(manager)
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(AssignRoleRequest(updateOnlyRole.id))
        }.andExpect {
            expectApiError(statusCode = 400, errorCode = "project_role_not_assignable")
        }
    }

    @Test
    fun `assign role returns conflict when role is already assigned`() {
        val fixtures = fixtures()
        val manager = authenticatedUser(fixtures, "member-manager")
        val project = fixtures.project()
        val subject = fixtures.principal()
        val viewerRole = role(SystemRoleNames.PROJECT_VIEWER)
        fixtures.bind(subject, project.scope, viewerRole)
        fixtures.grantMemberManagement(manager.user.principal, project)
        fixtures.flushAndClear()

        mockMvc.post("/api/projects/${project.id}/members/${subject.id}/roles") {
            bearer(manager)
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(AssignRoleRequest(viewerRole.id))
        }.andExpect {
            expectApiError(statusCode = 409, errorCode = "project_role_already_assigned")
        }
    }

    @Test
    fun `delete role binding removes requested binding`() {
        val fixtures = fixtures()
        val manager = authenticatedUser(fixtures, "member-manager")
        val project = fixtures.project()
        val subject = fixtures.principal()
        val deletedBinding = fixtures.bind(
            subject,
            project.scope,
            role(SystemRoleNames.PROJECT_VIEWER)
        )
        val keptBinding = fixtures.bind(
            subject,
            project.scope,
            role(SystemRoleNames.PROJECT_OPERATOR)
        )
        fixtures.grantMemberManagement(manager.user.principal, project)
        fixtures.flushAndClear()

        mockMvc.delete(
            "/api/projects/${project.id}/members/${subject.id}/roles/${deletedBinding.id}"
        ) {
            bearer(manager)
        }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.data") { value("ok") }
            jsonPath("$.errorMessage") { value(nullValue()) }
            jsonPath("$.errorCode") { value(nullValue()) }
        }

        assertFalse(roleBindingExists(deletedBinding.id))
        assertTrue(roleBindingExists(keptBinding.id))
    }

    @Test
    fun `delete member removes all project bindings and keeps other project bindings`() {
        val fixtures = fixtures()
        val manager = authenticatedUser(fixtures, "member-manager")
        val project = fixtures.project()
        val otherProject = fixtures.project()
        val subject = fixtures.principal()
        val viewerRole = role(SystemRoleNames.PROJECT_VIEWER)
        val operatorRole = role(SystemRoleNames.PROJECT_OPERATOR)
        val firstProjectBinding = fixtures.bind(subject, project.scope, viewerRole)
        val secondProjectBinding = fixtures.bind(subject, project.scope, operatorRole)
        val otherProjectBinding = fixtures.bind(subject, otherProject.scope, viewerRole)
        fixtures.grantMemberManagement(manager.user.principal, project)
        fixtures.flushAndClear()

        mockMvc.delete("/api/projects/${project.id}/members/${subject.id}") {
            bearer(manager)
        }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.data") { value("ok") }
            jsonPath("$.errorMessage") { value(nullValue()) }
            jsonPath("$.errorCode") { value(nullValue()) }
        }

        assertFalse(roleBindingExists(firstProjectBinding.id))
        assertFalse(roleBindingExists(secondProjectBinding.id))
        assertTrue(roleBindingExists(otherProjectBinding.id))
    }

    @Test
    fun `project member endpoints require authentication`() {
        mockMvc.get("/api/projects/${UUID.randomUUID()}/members").andExpect {
            status { isUnauthorized() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.data") { value(nullValue()) }
            jsonPath("$.errorCode") { value("authentication_required") }
        }
    }

    private fun fixtures(): IamProjectFixtures = IamProjectFixtures(entityManager)

    private fun authenticatedUser(
        fixtures: IamProjectFixtures,
        prefix: String
    ): AuthenticatedTestUser {
        val suffix = fixtures.unique()
        val user = fixtures.user(
            username = "$prefix-$suffix",
            email = "$prefix-$suffix@dockistry.io"
        ).apply {
            emailVerifiedAt = Instant.parse("2026-01-01T00:00:00Z")
        }
        fixtures.flush()

        return AuthenticatedTestUser(
            user = user,
            accessToken = tokenManagementService.issueTokenPair(user.id).accessToken
        )
    }

    private fun role(name: String): Role =
        assertNotNull(roleRepository.findByNameAndIsSystemTrue(name))

    private fun IamProjectFixtures.grantRead(principal: Principal, project: Project) {
        grantSystemPermission(principal, project.scope, SystemPermissionNames.PROJECT_READ)
    }

    private fun IamProjectFixtures.grantMemberManagement(
        principal: Principal,
        project: Project
    ) {
        grantSystemPermission(
            principal,
            project.scope,
            SystemPermissionNames.PROJECT_READ,
            SystemPermissionNames.PROJECT_MEMBERS_MANAGE
        )
    }

    private fun projectRoleNames(): Set<String> = setOf(
        SystemRoleNames.PROJECT_ADMIN,
        SystemRoleNames.PROJECT_OPERATOR,
        SystemRoleNames.PROJECT_VIEWER
    )

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

    private inline fun <reified T> MvcResult.apiResponse(): ApiResponse<T> {
        return objectMapper.readValue(response.contentAsString)
    }

    private fun MockHttpServletRequestDsl.bearer(user: AuthenticatedTestUser) {
        header(HttpHeaders.AUTHORIZATION, "Bearer ${user.accessToken}")
    }

    private fun MockMvcResultMatchersDsl.expectApiError(
        statusCode: Int,
        errorCode: String
    ) {
        status { isEqualTo(statusCode) }
        content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
        jsonPath("$.data") { value(nullValue()) }
        jsonPath("$.errorMessage") { exists() }
        jsonPath("$.errorCode") { value(errorCode) }
    }

    private data class AuthenticatedTestUser(
        val user: User,
        val accessToken: String
    )
}
