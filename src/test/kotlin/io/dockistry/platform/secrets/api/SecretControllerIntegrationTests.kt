package io.dockistry.platform.secrets.api

import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.auth.application.TokenManagementService
import io.dockistry.platform.iam.domain.User
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
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.junit.jupiter.Testcontainers
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class)
@Testcontainers
@Transactional
class SecretControllerIntegrationTests {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var tokenManagementService: TokenManagementService

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    @Test
    fun `rotate creates a new version for owner`() {
        val owner = authenticatedUser("rotate-owner")
        val secret = secretFixtures().secret(owner.user.principal)
        flushAndClear()

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
        val secret = secretFixtures().secret(owner.user.principal)
        flushAndClear()

        mockMvc.post("/api/secrets/${secret.id}/versions") {
            bearer(owner)
            json(SecretVersionCreateRequest(emptyMap()))
        }.andExpect { expectApiError(400, "empty_secret_values") }

        assertEquals(1, versionCount(secret.id))
    }

    @Test
    fun `rotate of foreign secret returns secret not found`() {
        val stranger = authenticatedUser("rotate-stranger")
        val secret = secretFixtures().secret(fixtures().principal())
        flushAndClear()

        mockMvc.post("/api/secrets/${secret.id}/versions") {
            bearer(stranger)
            json(SecretVersionCreateRequest(mapOf("password" to "rotated")))
        }.andExpect { expectApiError(404, "secret_not_found") }

        assertEquals(1, versionCount(secret.id))
    }

    @Test
    fun `delete version removes it for owner`() {
        val owner = authenticatedUser("version-owner")
        val secret = secretFixtures().secret(owner.user.principal)
        val versionId = secret.versions.single().id
        flushAndClear()

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
        val secretFixtures = secretFixtures()
        val secret = secretFixtures.secret(owner.user.principal)
        val otherVersionId = secretFixtures.secret(owner.user.principal).versions.single().id
        flushAndClear()

        mockMvc.delete("/api/secrets/${secret.id}/versions/$otherVersionId") {
            bearer(owner)
        }.andExpect { expectApiError(404, "secret_version_not_found") }

        assertEquals(1, count("secret_versions", otherVersionId))
    }

    @Test
    fun `delete removes secret with versions and delegations for owner`() {
        val owner = authenticatedUser("delete-owner")
        val secretFixtures = secretFixtures()
        val secret = secretFixtures.secret(owner.user.principal)
        val delegation = secretFixtures.delegate(secret, fixtures().project())
        flushAndClear()

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
        val secret = secretFixtures().secret(fixtures().principal())
        flushAndClear()

        mockMvc.delete("/api/secrets/${secret.id}") {
            bearer(stranger)
        }.andExpect { expectApiError(404, "secret_not_found") }

        assertEquals(1, count("secrets", secret.id))
    }

    @Test
    fun `revoke removes delegation for owner`() {
        val owner = authenticatedUser("revoke-owner")
        val project = fixtures().project()
        val delegation = secretFixtures().delegate(owner.user.principal, project)
        val secretId = delegation.secret.id
        flushAndClear()

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
        val project = fixtures().project()
        val delegation = secretFixtures().delegate(fixtures().principal(), project)
        flushAndClear()

        mockMvc.delete("/api/secrets/${delegation.secret.id}/delegations/${project.id}") {
            bearer(stranger)
        }.andExpect { expectApiError(404, "secret_not_found") }

        assertEquals(1, count("project_secret_delegations", delegation.id))
    }

    @Test
    fun `secret mutations require authentication`() {
        val secret = secretFixtures().secret(fixtures().principal())
        flushAndClear()

        mockMvc.delete("/api/secrets/${secret.id}")
            .andExpect { expectApiError(401, "authentication_required") }

        assertEquals(1, count("secrets", secret.id))
    }

    private fun fixtures(): IamProjectFixtures = IamProjectFixtures(entityManager)

    private fun secretFixtures(): SecretDelegationFixtures = SecretDelegationFixtures(entityManager)

    private fun flushAndClear() = fixtures().flushAndClear()

    private fun authenticatedUser(prefix: String): AuthenticatedTestUser {
        val fixtures = fixtures()
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
