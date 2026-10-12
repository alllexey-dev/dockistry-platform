package io.dockistry.platform.secrets.api

import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.auth.application.TokenManagementService
import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.iam.domain.User
import io.dockistry.platform.projects.domain.Project
import io.dockistry.platform.secrets.domain.ProjectSecretDelegation
import io.dockistry.platform.secrets.domain.Secret
import io.dockistry.platform.secrets.domain.dto.SecretDto
import io.dockistry.platform.secrets.domain.dto.SecretUpdateRequest
import io.dockistry.platform.secrets.domain.dto.SecretVersionCreateRequest
import io.dockistry.platform.shared.api.ApiResponse
import io.dockistry.platform.testsupport.IamProjectFixtures
import io.dockistry.platform.testsupport.SecretDelegationFixtures
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
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Testcontainers
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class)
@Testcontainers
class SecretControllerIntegrationTests {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var tokenManagementService: TokenManagementService

    // REQUIRES_NEW: Secret updates run in their own transactions, so fixtures are committed.
    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    @Test
    fun `update replaces name and description for owner`() {
        val owner = authenticatedUser("update-owner")
        val secret = secret(owner.user.principal)

        val result = mockMvc.put("/api/secrets/${secret.id}") {
            bearer(owner)
            json(SecretUpdateRequest("  renamed   secret ", "  new description  ", 0))
        }.andExpect {
            status { isOk() }
            jsonPath("$.errorCode") { value(nullValue()) }
        }.andReturn()

        val updated = assertNotNull(
            objectMapper.readValue<ApiResponse<SecretDto>>(result.response.contentAsString).data
        )
        assertEquals("renamed secret", updated.name)
        assertEquals("new description", updated.description)
        assertEquals(1, updated.version)
        assertEquals(listOf(secret.versions.single().id), updated.versionsIds)
    }

    @Test
    fun `stale update returns current secret when metadata already matches`() {
        val owner = authenticatedUser("update-stale-same")
        val secret = secret(owner.user.principal)
        val request = SecretUpdateRequest("stale-same-${unique()}", null, 0)

        repeat(2) {
            mockMvc.put("/api/secrets/${secret.id}") {
                bearer(owner)
                json(request)
            }.andExpect {
                status { isOk() }
                jsonPath("$.data.name") { value(request.name) }
                jsonPath("$.data.version") { value(1) }
            }
        }
    }

    @Test
    fun `stale update with different metadata returns version conflict`() {
        val owner = authenticatedUser("update-stale")
        val secret = secret(owner.user.principal)

        mockMvc.put("/api/secrets/${secret.id}") {
            bearer(owner)
            json(SecretUpdateRequest("first-${unique()}", null, 0))
        }.andExpect { status { isOk() } }

        mockMvc.put("/api/secrets/${secret.id}") {
            bearer(owner)
            json(SecretUpdateRequest("second-${unique()}", null, 0))
        }.andExpect { expectApiError(409, "secret_metadata_version_conflict") }
    }

    @Test
    fun `update to name of another own secret returns conflict`() {
        val owner = authenticatedUser("update-duplicate")
        val secret = secret(owner.user.principal)
        val other = secret(owner.user.principal)

        mockMvc.put("/api/secrets/${secret.id}") {
            bearer(owner)
            json(SecretUpdateRequest(other.name, null, 0))
        }.andExpect { expectApiError(409, "secret_already_exists") }
    }

    @Test
    fun `update allows name used by another owner`() {
        val owner = authenticatedUser("update-foreign-name")
        val secret = secret(owner.user.principal)
        val foreign = secret(principal())

        mockMvc.put("/api/secrets/${secret.id}") {
            bearer(owner)
            json(SecretUpdateRequest(foreign.name, null, 0))
        }.andExpect {
            status { isOk() }
            jsonPath("$.data.name") { value(foreign.name) }
        }
    }

    @Test
    fun `update rejects invalid name`() {
        val owner = authenticatedUser("update-invalid")
        val secret = secret(owner.user.principal)

        mockMvc.put("/api/secrets/${secret.id}") {
            bearer(owner)
            json(SecretUpdateRequest("   ", null, 0))
        }.andExpect { expectApiError(400, "invalid_secret_name") }
    }

    @Test
    fun `update of foreign secret returns secret not found`() {
        val stranger = authenticatedUser("update-stranger")
        val secret = secret(principal())

        mockMvc.put("/api/secrets/${secret.id}") {
            bearer(stranger)
            json(SecretUpdateRequest("stolen-${unique()}", null, 0))
        }.andExpect { expectApiError(404, "secret_not_found") }
    }

    @Test
    fun `rotate creates a new version for owner`() {
        val owner = authenticatedUser("rotate-owner")
        val secret = secret(owner.user.principal)

        val result = mockMvc.post("/api/secrets/${secret.id}/versions") {
            bearer(owner)
            json(SecretVersionCreateRequest(mapOf("password" to "rotated")))
        }.andExpect {
            status { isCreated() }
            jsonPath("$.errorCode") { value(nullValue()) }
        }.andReturn()

        val versionId = assertNotNull(
            objectMapper.readValue<ApiResponse<String>>(result.response.contentAsString).data
        )
        assertEquals(1, count("secret_versions", versionId))
        assertEquals(2, versionCount(secret.id))
    }

    @Test
    fun `rotate rejects empty values`() {
        val owner = authenticatedUser("rotate-empty")
        val secret = secret(owner.user.principal)

        mockMvc.post("/api/secrets/${secret.id}/versions") {
            bearer(owner)
            json(SecretVersionCreateRequest(emptyMap()))
        }.andExpect { expectApiError(400, "empty_secret_values") }

        assertEquals(1, versionCount(secret.id))
    }

    @Test
    fun `rotate of foreign secret returns secret not found`() {
        val stranger = authenticatedUser("rotate-stranger")
        val secret = secret(principal())

        mockMvc.post("/api/secrets/${secret.id}/versions") {
            bearer(stranger)
            json(SecretVersionCreateRequest(mapOf("password" to "rotated")))
        }.andExpect { expectApiError(404, "secret_not_found") }

        assertEquals(1, versionCount(secret.id))
    }

    @Test
    fun `delete version removes it for owner`() {
        val owner = authenticatedUser("version-owner")
        val secret = secret(owner.user.principal)
        val versionId = secret.versions.single().id

        mockMvc.delete("/api/secrets/${secret.id}/versions/$versionId") {
            bearer(owner)
        }.andExpect {
            status { isOk() }
            jsonPath("$.data") { value("ok") }
        }

        assertEquals(0, count("secret_versions", versionId))
        assertEquals(1, count("secrets", secret.id))
    }

    @Test
    fun `delete version of another secret returns version not found`() {
        val owner = authenticatedUser("version-mismatch")
        val secret = secret(owner.user.principal)
        val otherVersionId = secret(owner.user.principal).versions.single().id

        mockMvc.delete("/api/secrets/${secret.id}/versions/$otherVersionId") {
            bearer(owner)
        }.andExpect { expectApiError(404, "secret_version_not_found") }

        assertEquals(1, count("secret_versions", otherVersionId))
    }

    @Test
    fun `delete removes secret with versions and delegations for owner`() {
        val owner = authenticatedUser("delete-owner")
        val secret = secret(owner.user.principal)
        val delegation = delegate(secret, project())

        mockMvc.delete("/api/secrets/${secret.id}") {
            bearer(owner)
        }.andExpect {
            status { isOk() }
            jsonPath("$.data") { value("ok") }
        }

        assertEquals(0, count("secrets", secret.id))
        assertEquals(0, versionCount(secret.id))
        assertEquals(0, count("project_secret_delegations", delegation.id))
    }

    @Test
    fun `delete of foreign secret returns secret not found`() {
        val stranger = authenticatedUser("delete-stranger")
        val secret = secret(principal())

        mockMvc.delete("/api/secrets/${secret.id}") {
            bearer(stranger)
        }.andExpect { expectApiError(404, "secret_not_found") }

        assertEquals(1, count("secrets", secret.id))
    }

    @Test
    fun `revoke removes delegation for owner`() {
        val owner = authenticatedUser("revoke-owner")
        val project = project()
        val delegation = delegate(owner.user.principal, project)
        val secretId = delegation.secret.id

        mockMvc.delete("/api/secrets/$secretId/delegations/${project.id}") {
            bearer(owner)
        }.andExpect {
            status { isOk() }
            jsonPath("$.data") { value("ok") }
        }

        assertEquals(0, count("project_secret_delegations", delegation.id))
        assertEquals(1, count("secrets", secretId))
    }

    @Test
    fun `revoke of foreign secret returns secret not found`() {
        val stranger = authenticatedUser("revoke-stranger")
        val project = project()
        val delegation = delegate(principal(), project)

        mockMvc.delete("/api/secrets/${delegation.secret.id}/delegations/${project.id}") {
            bearer(stranger)
        }.andExpect { expectApiError(404, "secret_not_found") }

        assertEquals(1, count("project_secret_delegations", delegation.id))
    }

    @Test
    fun `secret mutations require authentication`() {
        val secret = secret(principal())

        mockMvc.delete("/api/secrets/${secret.id}")
            .andExpect { expectApiError(401, "authentication_required") }

        assertEquals(1, count("secrets", secret.id))
    }

    private fun <T> committed(block: () -> T): T = transactionTemplate.execute { block() }

    private fun fixtures(): IamProjectFixtures = IamProjectFixtures(entityManager)

    private fun unique(): String = UUID.randomUUID().toString().replace("-", "").take(12)

    private fun principal(): Principal = committed { fixtures().principal() }

    private fun project(): Project = committed { fixtures().project() }

    private fun secret(owner: Principal): Secret = committed {
        SecretDelegationFixtures(entityManager).secret(reference<Principal>(owner.id))
    }

    private fun delegate(owner: Principal, project: Project): ProjectSecretDelegation =
        delegate(secret(owner), project)

    private fun delegate(secret: Secret, project: Project): ProjectSecretDelegation = committed {
        SecretDelegationFixtures(entityManager).delegate(
            reference<Secret>(secret.id),
            reference<Project>(project.id)
        )
    }

    private inline fun <reified T : Any> reference(id: Any): T =
        entityManager.getReference(T::class.java, id)

    private fun authenticatedUser(prefix: String): AuthenticatedTestUser {
        val user = committed {
            val suffix = unique()
            fixtures().user(
                username = "$prefix-$suffix",
                email = "$prefix-$suffix@dockistry.io"
            ).apply {
                emailVerifiedAt = Instant.parse("2026-01-01T00:00:00Z")
            }
        }

        return AuthenticatedTestUser(
            user = user,
            accessToken = tokenManagementService.issueTokenPair(user.id).accessToken
        )
    }

    private fun count(table: String, id: Any): Long =
        (entityManager.createNativeQuery("select count(*) from $table where id = :id")
            .setParameter("id", id)
            .singleResult as Number).toLong()

    private fun versionCount(secretId: String): Long =
        (entityManager
            .createNativeQuery("select count(*) from secret_versions where secret_id = :id")
            .setParameter("id", secretId)
            .singleResult as Number).toLong()

    private fun MockHttpServletRequestDsl.bearer(user: AuthenticatedTestUser) {
        header(HttpHeaders.AUTHORIZATION, "Bearer ${user.accessToken}")
    }

    private fun MockHttpServletRequestDsl.json(body: Any) {
        contentType = MediaType.APPLICATION_JSON
        content = objectMapper.writeValueAsString(body)
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
