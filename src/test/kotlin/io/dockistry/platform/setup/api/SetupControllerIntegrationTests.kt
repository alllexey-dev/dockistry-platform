package io.dockistry.platform.setup.api

import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.auth.domain.dto.AuthResponse
import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.iam.domain.PrincipalType
import io.dockistry.platform.iam.domain.RoleBinding
import io.dockistry.platform.iam.domain.Scope
import io.dockistry.platform.iam.domain.ScopeType
import io.dockistry.platform.iam.domain.SystemRoleNames
import io.dockistry.platform.iam.domain.User
import io.dockistry.platform.iam.infrastructure.RoleRepository
import io.dockistry.platform.iam.infrastructure.ScopeRepository
import io.dockistry.platform.setup.infrastructure.InitialAdminRepository
import io.dockistry.platform.shared.api.ApiResponse
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.junit.jupiter.Testcontainers
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class)
@Testcontainers
@Transactional
class SetupControllerIntegrationTests {
    @Autowired
    private lateinit var scopeRepository: ScopeRepository

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var initialAdminRepository: InitialAdminRepository

    @Autowired
    private lateinit var roleRepository: RoleRepository

    @PersistenceContext
    private lateinit var entityManager: EntityManager

    @Test
    fun `initial admin setup endpoint is accessible without authentication`() {
        val result = mockMvc.post("/api/setup/initial-admin") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                SetupInitialAdminRequest(
                    username = "ab",
                    email = "public-admin@dockistry.io",
                    password = "password-123"
                )
            )
        }.andExpect {
            status { isBadRequest() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
        }.andReturn()

        assertApiError(result, expectedCode = "username_validation_failed")
    }

    @Test
    fun `initial admin setup returns conflict when admin already exists`() {
        persistExistingInitialAdmin(
            username = "existing-admin",
            email = "existing-admin@dockistry.io"
        )

        val result = mockMvc.post("/api/setup/initial-admin") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                SetupInitialAdminRequest(
                    username = "another-admin",
                    email = "another-admin@dockistry.io",
                    password = "password-123"
                )
            )
        }.andExpect {
            status { isConflict() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
        }.andReturn()

        assertApiError(
            result = result,
            expectedCode = "initial_admin_already_exists",
            expectedMessage = "Initial admin already exists"
        )
    }

    @Test
    fun `initial admin setup rejects invalid credentials as bad requests`() {
        listOf(
            InvalidRequestCase(
                request = SetupInitialAdminRequest(
                    username = "ab",
                    email = "valid-admin@dockistry.io",
                    password = "password-123"
                ),
                expectedCode = "username_validation_failed"
            ),
            InvalidRequestCase(
                request = SetupInitialAdminRequest(
                    username = "valid-admin",
                    email = "not-an-email",
                    password = "password-123"
                ),
                expectedCode = "email_validation_failed"
            ),
            InvalidRequestCase(
                request = SetupInitialAdminRequest(
                    username = "valid-admin",
                    email = "valid-admin@dockistry.io",
                    password = "short"
                ),
                expectedCode = "password_validation_failed"
            )
        ).forEach { case ->
            val result = mockMvc.post("/api/setup/initial-admin") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(case.request)
            }.andExpect {
                status { isBadRequest() }
                content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            }.andReturn()

            assertApiError(result, expectedCode = case.expectedCode)
        }
    }

    @Test
    fun `initial admin setup creates admin and returns usable token pair`() {
        val tokens = setupInitialAdmin(
            username = "token-admin",
            email = "token-admin@dockistry.io",
            password = "password-123"
        )
        entityManager.flush()

        assertTrue(initialAdminRepository.existsAdmin())
        assertPrivatePing(tokens.accessToken, "token-admin")

        val refreshedTokens = refresh(tokens.refreshToken)
        assertPrivatePing(refreshedTokens.accessToken, "token-admin")
    }

    private fun setupInitialAdmin(
        username: String,
        email: String,
        password: String
    ): AuthResponse {
        val result = mockMvc.post("/api/setup/initial-admin") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                SetupInitialAdminRequest(
                    username = username,
                    email = email,
                    password = password
                )
            )
        }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
        }.andReturn()

        val response = objectMapper.readValue<ApiResponse<AuthResponse>>(
            result.response.contentAsString
        )
        val tokens = assertNotNull(response.data)

        assertNull(response.errorCode)
        assertNull(response.errorMessage)
        assertTrue(tokens.accessToken.isNotBlank())
        assertTrue(tokens.refreshToken.isNotBlank())

        return tokens
    }

    private fun refresh(refreshToken: String): AuthResponse {
        val result = mockMvc.post("/api/auth/refresh") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(RefreshTokenRequest(refreshToken))
        }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
        }.andReturn()

        val response = objectMapper.readValue<ApiResponse<AuthResponse>>(
            result.response.contentAsString
        )
        val tokens = assertNotNull(response.data)

        assertTrue(tokens.accessToken.isNotBlank())
        assertTrue(tokens.refreshToken.isNotBlank())

        return tokens
    }

    private fun assertPrivatePing(accessToken: String, username: String) {
        mockMvc.get("/api/private/ping") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
        }.andExpect {
            status { isOk() }
            content { string("pong to $username") }
        }
    }

    private fun assertApiError(
        result: MvcResult,
        expectedCode: String,
        expectedMessage: String? = null
    ) {
        val response = objectMapper.readValue<ApiResponse<Unit>>(result.response.contentAsString)

        assertNull(response.data)
        assertEquals(expectedCode, response.errorCode)
        if (expectedMessage != null) {
            assertEquals(expectedMessage, response.errorMessage)
        } else {
            assertEquals(response.errorMessage?.isNotBlank(), true)
        }
    }

    private data class SetupInitialAdminRequest(
        val username: String,
        val email: String,
        val password: String
    )

    private data class InvalidRequestCase(
        val request: SetupInitialAdminRequest,
        val expectedCode: String
    )

    private data class RefreshTokenRequest(
        val refreshToken: String
    )

    private fun persistExistingInitialAdmin(username: String, email: String) {
        val globalScope = scopeRepository.findByScopeType(ScopeType.GLOBAL).single()
        val adminRole = assertNotNull(
            roleRepository.findByNameAndIsSystemTrue(SystemRoleNames.PROJECT_ADMIN)
        )
        val user = User(
            username = username,
            email = email,
            passwordHash = "hashed-password"
        ).apply {
            principal = Principal(PrincipalType.USER)
            emailVerifiedAt = Instant.parse("2026-01-01T00:00:00Z")
        }

        entityManager.persist(user)
        user.principal.roleBindings.add(
            RoleBinding(
                subjectPrincipal = user.principal,
                scope = globalScope,
                role = adminRole
            )
        )
        entityManager.flush()
    }
}
