package io.dockistry.platform.projects.api

import io.dockistry.platform.TestClockConfig
import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.auth.application.TokenManagementService
import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.iam.domain.PrincipalType
import io.dockistry.platform.iam.domain.SystemPermissionNames
import io.dockistry.platform.iam.domain.User
import io.dockistry.platform.projects.domain.Project
import io.dockistry.platform.projects.domain.ProjectStatus
import io.dockistry.platform.projects.domain.dto.ProjectSaveRequest
import io.dockistry.platform.projects.domain.dto.ProjectUpdateRequest
import io.dockistry.platform.projects.infrastructure.ProjectRepository
import io.dockistry.platform.services.testsupport.managedService
import io.dockistry.platform.testsupport.IamProjectFixtures
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.hamcrest.Matchers.hasSize
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
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Testcontainers
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID
import kotlin.test.assertFalse

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class, TestClockConfig::class)
@Testcontainers
@Transactional
class ProjectControllerIntegrationTests {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var tokenManagementService: TokenManagementService

    @Autowired
    private lateinit var projectRepository: ProjectRepository

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
    fun `list returns project info dtos for visible projects`() {
        val fixtures = fixtures()
        val user = authenticatedUser(fixtures, "reader")
        val owner = fixtures.user(username = "owner-${fixtures.unique()}")
        val visibleProject = fixtures.project(
            name = "Visible ${fixtures.unique()}",
            slug = "visible-${fixtures.unique()}",
            ownerPrincipal = owner.principal
        ).apply {
            description = "Visible project"
        }
        fixtures.project(
            name = "Hidden ${fixtures.unique()}",
            slug = "hidden-${fixtures.unique()}"
        )
        fixtures.grantRead(user.user.principal, visibleProject)
        fixtures.flush()

        mockMvc.get("/api/projects") {
            bearer(user)
        }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.data", hasSize<Any>(1))
            jsonPath("$.errorMessage") { value(nullValue()) }
            jsonPath("$.errorCode") { value(nullValue()) }
            expectProjectDto("$.data[0]", visibleProject, owner)
        }
    }

    @Test
    fun `get returns project info dto when user can read project`() {
        val fixtures = fixtures()
        val user = authenticatedUser(fixtures, "reader")
        val owner = fixtures.user(username = "owner-${fixtures.unique()}")
        val project = fixtures.project(
            name = "Readable ${fixtures.unique()}",
            slug = "readable-${fixtures.unique()}",
            ownerPrincipal = owner.principal
        ).apply {
            description = "Readable project"
        }
        fixtures.grantRead(user.user.principal, project)
        fixtures.flush()

        mockMvc.get("/api/projects/${project.id}") {
            bearer(user)
        }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            expectProjectDto("$.data", project, owner)
            jsonPath("$.errorMessage") { value(nullValue()) }
            jsonPath("$.errorCode") { value(nullValue()) }
        }
    }

    @Test
    fun `create returns created project info dto`() {
        val fixtures = fixtures()
        val user = authenticatedUser(fixtures, "creator")
        fixtures.grantSystemPermission(
            user.user.principal,
            fixtures.globalScope(),
            SystemPermissionNames.PROJECT_CREATE
        )
        fixtures.flush()

        mockMvc.post("/api/projects") {
            bearer(user)
            contentType = MediaType.APPLICATION_JSON
            content = projectJson(
                name = "  Cafe\u0301\nDeploy  ",
                slug = "cafe-deploy",
                description = "Deployments"
            )
        }.andExpect {
            status { isCreated() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.data.id") { exists() }
            jsonPath("$.data.name") { value("Café Deploy") }
            jsonPath("$.data.slug") { value("cafe-deploy") }
            jsonPath("$.data.description") { value("Deployments") }
            jsonPath("$.data.status") { value(ProjectStatus.ACTIVE.name) }
            jsonPath("$.data.createdAt") { value("2026-01-01T00:00:00Z") }
            jsonPath("$.data.ownerPrincipalInfo.id") { value(user.user.id.toString()) }
            jsonPath("$.data.ownerPrincipalInfo.type") { value(PrincipalType.USER.name) }
            jsonPath("$.data.ownerPrincipalInfo.name") { value(user.user.username) }
            jsonPath("$.errorMessage") { value(nullValue()) }
            jsonPath("$.errorCode") { value(nullValue()) }
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `update returns updated project info dto when user can update project`() {
        val context = committedUpdateContext(
            SystemPermissionNames.PROJECT_UPDATE,
            description = "Old description"
        )

        try {
            mockMvc.patch("/api/projects/${context.project.id}") {
                bearer(context.user)
                contentType = MediaType.APPLICATION_JSON
                content = projectUpdateJson(
                    name = "  New\tName  ",
                    description = "New description",
                    expectedVersion = context.project.version
                )
            }.andExpect {
                status { isOk() }
                content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
                jsonPath("$.data.id") { value(context.project.id.toString()) }
                jsonPath("$.data.name") { value("New Name") }
                jsonPath("$.data.slug") { value(context.project.slug) }
                jsonPath("$.data.description") { value("New description") }
                jsonPath("$.data.status") { value(ProjectStatus.ACTIVE.name) }
                jsonPath("$.data.createdAt") {
                    value(context.project.createdAt.toString())
                }
                jsonPath("$.data.ownerPrincipalInfo.id") {
                    value(context.owner.id.toString())
                }
                jsonPath("$.data.ownerPrincipalInfo.type") {
                    value(PrincipalType.USER.name)
                }
                jsonPath("$.data.ownerPrincipalInfo.name") {
                    value(context.owner.username)
                }
                jsonPath("$.errorMessage") { value(nullValue()) }
                jsonPath("$.errorCode") { value(nullValue()) }
            }
        } finally {
            cleanupCommittedProject(context.project.id)
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `update rejects negative expected version`() {
        val context = committedUpdateContext(SystemPermissionNames.PROJECT_UPDATE)

        try {
            mockMvc.patch("/api/projects/${context.project.id}") {
                bearer(context.user)
                contentType = MediaType.APPLICATION_JSON
                content = projectUpdateJson(
                    name = context.project.name,
                    description = context.project.description,
                    expectedVersion = -1
                )
            }.andExpect {
                expectApiError(statusCode = 400, errorCode = "validation_error")
                jsonPath("$.errorMessage") {
                    value("expectedVersion: Project expectedVersion must not be negative")
                }
            }
        } finally {
            cleanupCommittedProject(context.project.id)
        }
    }

    @Test
    fun `archive returns archived project info dto when user can archive project`() {
        val fixtures = fixtures()
        val user = authenticatedUser(fixtures, "archiver")
        val owner = fixtures.user(username = "owner-${fixtures.unique()}")
        val project = fixtures.project(
            name = "Archived ${fixtures.unique()}",
            slug = "archived-${fixtures.unique()}",
            ownerPrincipal = owner.principal
        )
        fixtures.grantProjectPermissions(
            user.user.principal,
            project,
            SystemPermissionNames.PROJECT_ARCHIVE
        )
        fixtures.flush()

        mockMvc.post("/api/projects/${project.id}/archive") {
            bearer(user)
        }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.data.id") { value(project.id.toString()) }
            jsonPath("$.data.name") { value(project.name) }
            jsonPath("$.data.slug") { value(project.slug) }
            jsonPath("$.data.status") { value(ProjectStatus.ARCHIVED.name) }
            jsonPath("$.data.ownerPrincipalInfo.id") { value(owner.id.toString()) }
            jsonPath("$.data.ownerPrincipalInfo.type") { value(PrincipalType.USER.name) }
            jsonPath("$.data.ownerPrincipalInfo.name") { value(owner.username) }
            jsonPath("$.errorMessage") { value(nullValue()) }
            jsonPath("$.errorCode") { value(nullValue()) }
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `delete removes project and returns ok response when user can delete project`() {
        val context = committedDeleteContext(SystemPermissionNames.PROJECT_DELETE)

        try {
            mockMvc.delete("/api/projects/${context.projectId}") {
                bearer(context.user)
            }.andExpect {
                status { isOk() }
                content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
                jsonPath("$.data") { value("ok") }
                jsonPath("$.errorMessage") { value(nullValue()) }
                jsonPath("$.errorCode") { value(nullValue()) }
            }

            assertFalse(
                inNewTransaction { projectRepository.existsById(context.projectId) }
            )
        } finally {
            cleanupCommittedProject(context.projectId)
        }
    }

    @Test
    fun `create returns forbidden when user cannot create projects`() {
        val user = authenticatedUser(fixtures(), "creator")

        mockMvc.post("/api/projects") {
            bearer(user)
            contentType = MediaType.APPLICATION_JSON
            content = projectJson(name = "Forbidden", slug = "forbidden")
        }.andExpect {
            expectApiError(
                statusCode = 403,
                errorCode = "insufficient_permission_for_operation"
            )
        }
    }

    @Test
    fun `get returns not found when user cannot read project`() {
        val fixtures = fixtures()
        val user = authenticatedUser(fixtures, "reader")
        val project = fixtures.project(
            name = "Forbidden ${fixtures.unique()}",
            slug = "forbidden-${fixtures.unique()}"
        )
        fixtures.flush()

        mockMvc.get("/api/projects/${project.id}") {
            bearer(user)
        }.andExpect {
            expectApiError(
                statusCode = 404,
                errorCode = "project_not_found"
            )
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `update returns not found when user cannot read project`() {
        val context = committedUpdateContext()

        try {
            mockMvc.patch("/api/projects/${context.project.id}") {
                bearer(context.user)
                contentType = MediaType.APPLICATION_JSON
                content = projectUpdateJson(
                    name = "New Name",
                    expectedVersion = context.project.version
                )
            }.andExpect {
                expectApiError(statusCode = 404, errorCode = "project_not_found")
            }
        } finally {
            cleanupCommittedProject(context.project.id)
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `update returns forbidden when user cannot update project`() {
        val context = committedUpdateContext(SystemPermissionNames.PROJECT_READ)

        try {
            mockMvc.patch("/api/projects/${context.project.id}") {
                bearer(context.user)
                contentType = MediaType.APPLICATION_JSON
                content = projectUpdateJson(
                    name = "New Name",
                    expectedVersion = context.project.version
                )
            }.andExpect {
                expectApiError(
                    statusCode = 403,
                    errorCode = "insufficient_permission_for_operation"
                )
            }
        } finally {
            cleanupCommittedProject(context.project.id)
        }
    }

    @Test
    fun `archive returns not found when user cannot read project`() {
        val fixtures = fixtures()
        val user = authenticatedUser(fixtures, "archiver")
        val project = fixtures.project(
            name = "Hidden ${fixtures.unique()}",
            slug = "hidden-${fixtures.unique()}"
        )
        fixtures.flush()

        mockMvc.post("/api/projects/${project.id}/archive") {
            bearer(user)
        }.andExpect {
            expectApiError(statusCode = 404, errorCode = "project_not_found")
        }
    }

    @Test
    fun `archive returns forbidden when user cannot archive project`() {
        val fixtures = fixtures()
        val user = authenticatedUser(fixtures, "archiver")
        val project = fixtures.project(
            name = "Forbidden ${fixtures.unique()}",
            slug = "forbidden-${fixtures.unique()}"
        )
        fixtures.grantRead(user.user.principal, project)
        fixtures.flush()

        mockMvc.post("/api/projects/${project.id}/archive") {
            bearer(user)
        }.andExpect {
            expectApiError(
                statusCode = 403,
                errorCode = "insufficient_permission_for_operation"
            )
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `delete returns not found when user cannot read project`() {
        val context = committedDeleteContext()

        try {
            mockMvc.delete("/api/projects/${context.projectId}") {
                bearer(context.user)
            }.andExpect {
                expectApiError(statusCode = 404, errorCode = "project_not_found")
            }
        } finally {
            cleanupCommittedProject(context.projectId)
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `delete returns forbidden when user cannot delete project`() {
        val context = committedDeleteContext(SystemPermissionNames.PROJECT_READ)

        try {
            mockMvc.delete("/api/projects/${context.projectId}") {
                bearer(context.user)
            }.andExpect {
                expectApiError(
                    statusCode = 403,
                    errorCode = "insufficient_permission_for_operation"
                )
            }
        } finally {
            cleanupCommittedProject(context.projectId)
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `delete returns conflict when project has managed services`() {
        val context = committedDeleteContext(
            SystemPermissionNames.PROJECT_READ,
            SystemPermissionNames.PROJECT_DELETE,
            withManagedService = true
        )

        try {
            mockMvc.delete("/api/projects/${context.projectId}") {
                bearer(context.user)
            }.andExpect {
                expectApiError(
                    statusCode = 409,
                    errorCode = "project_deletion_blocked_by_managed_services"
                )
            }
        } finally {
            cleanupCommittedProject(context.projectId)
        }
    }

    @Test
    fun `get returns not found when project does not exist`() {
        val user = authenticatedUser(fixtures(), "reader")

        mockMvc.get("/api/projects/${UUID.randomUUID()}") {
            bearer(user)
        }.andExpect {
            expectApiError(statusCode = 404, errorCode = "project_not_found")
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `update returns not found when project does not exist`() {
        val user = committedAuthenticatedUser("editor")

        mockMvc.patch("/api/projects/${UUID.randomUUID()}") {
            bearer(user)
            contentType = MediaType.APPLICATION_JSON
            content = projectUpdateJson(
                name = "Missing",
                expectedVersion = 0
            )
        }.andExpect {
            expectApiError(statusCode = 404, errorCode = "project_not_found")
        }
    }

    @Test
    fun `archive returns not found when project does not exist`() {
        val user = authenticatedUser(fixtures(), "archiver")

        mockMvc.post("/api/projects/${UUID.randomUUID()}/archive") {
            bearer(user)
        }.andExpect {
            expectApiError(statusCode = 404, errorCode = "project_not_found")
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `delete returns not found when project does not exist`() {
        val user = committedAuthenticatedUser("deleter")

        mockMvc.delete("/api/projects/${UUID.randomUUID()}") {
            bearer(user)
        }.andExpect {
            expectApiError(statusCode = 404, errorCode = "project_not_found")
        }
    }

    @Test
    fun `create returns invalid project name error for blank name`() {
        val fixtures = fixtures()
        val user = authenticatedUser(fixtures, "creator")
        fixtures.grantSystemPermission(
            user.user.principal,
            fixtures.globalScope(),
            SystemPermissionNames.PROJECT_CREATE
        )
        fixtures.flush()

        mockMvc.post("/api/projects") {
            bearer(user)
            contentType = MediaType.APPLICATION_JSON
            content = projectJson(name = " \n\t ", slug = "valid-slug")
        }.andExpect {
            expectApiError(statusCode = 400, errorCode = "invalid_project_name")
        }
    }

    @Test
    fun `create returns invalid project slug error for invalid slug`() {
        val fixtures = fixtures()
        val user = authenticatedUser(fixtures, "creator")
        fixtures.grantSystemPermission(
            user.user.principal,
            fixtures.globalScope(),
            SystemPermissionNames.PROJECT_CREATE
        )
        fixtures.flush()

        mockMvc.post("/api/projects") {
            bearer(user)
            contentType = MediaType.APPLICATION_JSON
            content = projectJson(name = "Valid Name", slug = "Invalid Slug")
        }.andExpect {
            expectApiError(statusCode = 400, errorCode = "invalid_project_slug")
        }
    }

    @Test
    fun `create returns conflict when project name or slug already exists`() {
        val fixtures = fixtures()
        val user = authenticatedUser(fixtures, "creator")
        fixtures.grantSystemPermission(
            user.user.principal,
            fixtures.globalScope(),
            SystemPermissionNames.PROJECT_CREATE
        )
        fixtures.project(name = "Existing Project", slug = "existing-project")
        fixtures.flush()

        mockMvc.post("/api/projects") {
            bearer(user)
            contentType = MediaType.APPLICATION_JSON
            content =
                projectJson(name = "Existing Project Copy", slug = "existing-project")
        }.andExpect {
            expectApiError(statusCode = 409, errorCode = "project_already_exists")
        }
    }

    @Test
    fun `project endpoints require authentication`() {
        mockMvc.get("/api/projects").andExpect {
            status { isUnauthorized() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.data") { value(nullValue()) }
            jsonPath("$.errorMessage") {
                value("Full authentication is required to access this resource")
            }
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

    private fun committedAuthenticatedUser(prefix: String): AuthenticatedTestUser =
        inNewTransaction {
            authenticatedUser(fixtures(), prefix)
        }

    private fun committedUpdateContext(
        vararg permissionKeys: String,
        description: String? = null
    ): ProjectUpdateHttpContext = inNewTransaction {
        val fixtures = fixtures()
        val user = authenticatedUser(fixtures, "editor")
        val owner = fixtures.user(username = "owner-${fixtures.unique()}")
        val project = fixtures.project(
            name = "Editable ${fixtures.unique()}",
            slug = "editable-${fixtures.unique()}",
            ownerPrincipal = owner.principal
        ).apply {
            this.description = description
        }
        if (permissionKeys.isNotEmpty()) {
            fixtures.grantProjectPermissions(
                user.user.principal,
                project,
                *permissionKeys.filterNot {
                    it == SystemPermissionNames.PROJECT_READ
                }.toTypedArray()
            )
        }
        fixtures.flush()

        ProjectUpdateHttpContext(
            project = project,
            owner = owner,
            user = user
        )
    }

    private fun committedDeleteContext(
        vararg permissionKeys: String,
        withManagedService: Boolean = false
    ): ProjectDeleteHttpContext = inNewTransaction {
        val fixtures = fixtures()
        val user = authenticatedUser(fixtures, "deleter")
        val project = fixtures.project(
            name = "Deleted ${fixtures.unique()}",
            slug = "deleted-${fixtures.unique()}"
        )
        if (permissionKeys.isNotEmpty()) {
            fixtures.grantProjectPermissions(
                user.user.principal,
                project,
                *permissionKeys.filterNot {
                    it == SystemPermissionNames.PROJECT_READ
                }.toTypedArray()
            )
        }
        if (withManagedService) {
            entityManager.persist(managedService(project, "blocking-service"))
        }
        fixtures.flush()

        ProjectDeleteHttpContext(project.id, user)
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

    private fun IamProjectFixtures.grantRead(principal: Principal, project: Project) {
        grantSystemPermission(
            principal,
            project.scope,
            SystemPermissionNames.PROJECT_READ
        )
    }

    private fun IamProjectFixtures.grantProjectPermissions(
        principal: Principal,
        project: Project,
        vararg permissionKeys: String
    ) {
        grantSystemPermission(
            principal,
            project.scope,
            *(listOf(SystemPermissionNames.PROJECT_READ) + permissionKeys).toTypedArray()
        )
    }

    private fun projectJson(
        name: String,
        slug: String,
        description: String? = null
    ): String {
        return objectMapper.writeValueAsString(
            ProjectSaveRequest(name = name, slug = slug, description = description)
        )
    }

    private fun projectUpdateJson(
        name: String,
        description: String? = null,
        expectedVersion: Long
    ): String {
        return objectMapper.writeValueAsString(
            ProjectUpdateRequest(
                name = name,
                description = description,
                expectedVersion = expectedVersion
            )
        )
    }

    private fun MockHttpServletRequestDsl.bearer(user: AuthenticatedTestUser) {
        header(HttpHeaders.AUTHORIZATION, "Bearer ${user.accessToken}")
    }

    private fun MockMvcResultMatchersDsl.expectProjectDto(
        path: String,
        project: Project,
        owner: User
    ) {
        jsonPath("$path.id") { value(project.id.toString()) }
        jsonPath("$path.name") { value(project.name) }
        jsonPath("$path.slug") { value(project.slug) }
        jsonPath("$path.description") { value(project.description) }
        jsonPath("$path.status") { value(project.status.name) }
        jsonPath("$path.createdAt") { value(project.createdAt.toString()) }
        jsonPath("$path.ownerPrincipalInfo.id") { value(owner.id.toString()) }
        jsonPath("$path.ownerPrincipalInfo.type") { value(project.ownerPrincipal.principalType.name) }
        jsonPath("$path.ownerPrincipalInfo.name") { value(owner.username) }
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

    private data class ProjectUpdateHttpContext(
        val project: Project,
        val owner: User,
        val user: AuthenticatedTestUser
    )

    private data class ProjectDeleteHttpContext(
        val projectId: UUID,
        val user: AuthenticatedTestUser
    )
}
