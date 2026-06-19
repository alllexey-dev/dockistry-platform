package io.dockistry.platform.security

import io.dockistry.platform.TestEmailSenderConfig
import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.auth.domain.dto.AuthResponse
import io.dockistry.platform.email.application.EmailSendJobProcessor
import io.dockistry.platform.shared.api.ApiResponse
import io.dockistry.platform.testsupport.AuthTestClient
import io.dockistry.platform.testsupport.InMemoryRawEmailSender
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
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
import org.testcontainers.junit.jupiter.Testcontainers
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class, TestEmailSenderConfig::class)
@Testcontainers
class JwtAuthenticationIntegrationTests {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var emailSender: InMemoryRawEmailSender

    @Autowired
    private lateinit var emailSendJobProcessor: EmailSendJobProcessor

    @Test
    fun `access token allows authenticated actuator health request`() {
        val tokens = registerUser()

        mockMvc.get("/actuator/health") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.accessToken}")
        }.andExpect {
            status { isOk() }
        }
    }

    @Test
    fun `refresh token cannot authenticate actuator health request`() {
        val tokens = registerUser()

        val result = mockMvc.get("/actuator/health") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.refreshToken}")
        }.andExpect {
            status { isUnauthorized() }
            content { contentType(MediaType.APPLICATION_JSON) }
        }
            .andReturn()

        assertApiError(
            result = result,
            expectedCode = "authentication_required",
            expectedMessage = "Token is invalid or of wrong type"
        )
    }

    @Test
    fun `refresh endpoint rotates refresh token and returns a usable access token`() {
        val originalTokens = registerUser()

        val refreshedTokens = refresh(originalTokens.refreshToken)

        assertNotEquals(originalTokens.refreshToken, refreshedTokens.refreshToken)

        mockMvc.get("/actuator/health") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${refreshedTokens.accessToken}")
        }.andExpect {
            status { isOk() }
        }

        val revokedTokenResult = mockMvc.post("/api/auth/refresh") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(RefreshTokenRequest(originalTokens.refreshToken))
        }.andExpect {
            status { isUnauthorized() }
            content { contentType(MediaType.APPLICATION_JSON) }
        }
            .andReturn()

        assertApiError(
            result = revokedTokenResult,
            expectedCode = "token_revoked",
            expectedMessage = "Token has been already revoked"
        )
    }

    @Test
    fun `invalid bearer token is rejected before reaching actuator health`() {
        val result = mockMvc.get("/actuator/health") {
            header(HttpHeaders.AUTHORIZATION, "Bearer not-a-valid-jwt")
        }.andExpect {
            status { isUnauthorized() }
            content { contentType(MediaType.APPLICATION_JSON) }
        }
            .andReturn()

        assertApiError(
            result = result,
            expectedCode = "authentication_required",
            expectedMessage = "Token is invalid or of wrong type"
        )
    }

    @Test
    fun `duplicate username registration returns api error response`() {
        val suffix = UUID.randomUUID().toString().take(8)
        val username = "duplicate-username-$suffix"

        mockMvc.post("/api/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                RegisterUserRequest(
                    email = "$username@dockistry.io",
                    username = username,
                    password = "password-123"
                )
            )
        }.andExpect {
            status { isOk() }
        }

        val result = mockMvc.post("/api/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                RegisterUserRequest(
                    email = "$username-other@dockistry.io",
                    username = username,
                    password = "password-123"
                )
            )
        }.andExpect {
            status { isConflict() }
            content { contentType(MediaType.APPLICATION_JSON) }
        }
            .andReturn()

        assertApiError(
            result = result,
            expectedCode = "user_already_exists",
            expectedMessage = "User with username = $username already exists"
        )
    }

    @Test
    fun `duplicate unverified email registration updates user and returns ok`() {
        val suffix = UUID.randomUUID().toString().take(8)
        val email = "duplicate-unverified-email-$suffix@dockistry.io"
        val originalUsername = "dup-unverified-$suffix"
        val updatedUsername = "dup-unverified-new-$suffix"

        mockMvc.post("/api/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                RegisterUserRequest(
                    email = email,
                    username = originalUsername,
                    password = "password-123"
                )
            )
        }.andExpect {
            status { isOk() }
        }

        mockMvc.post("/api/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                RegisterUserRequest(
                    email = email,
                    username = updatedUsername,
                    password = "password-123"
                )
            )
        }.andExpect {
            status { isOk() }
            content { contentType(MediaType.APPLICATION_JSON) }
        }
    }

    @Test
    fun `duplicate verified email registration returns api error response`() {
        val suffix = UUID.randomUUID().toString().take(8)
        val email = "duplicate-verified-email-$suffix@dockistry.io"

        AuthTestClient(
            mockMvc = mockMvc,
            objectMapper = objectMapper,
            emailSender = emailSender,
            emailSendJobProcessor = emailSendJobProcessor
        ).registerAndVerifyUser(
            email = email,
            username = "dup-verified-$suffix"
        )

        val request = RegisterUserRequest(
            email = email,
            username = "dup-verified-new-$suffix",
            password = "password-123"
        )

        val result = mockMvc.post("/api/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect {
            status { isConflict() }
            content { contentType(MediaType.APPLICATION_JSON) }
        }
            .andReturn()

        assertApiError(
            result = result,
            expectedCode = "user_already_exists",
            expectedMessage = "User with email = $email already exists"
        )
    }

    private fun registerUser(): AuthResponse {
        val suffix = UUID.randomUUID().toString().take(8)

        return AuthTestClient(
            mockMvc = mockMvc,
            objectMapper = objectMapper,
            emailSender = emailSender,
            emailSendJobProcessor = emailSendJobProcessor
        ).registerAndVerifyUser(
            email = "jwt-$suffix@dockistry.io",
            username = "jwt-$suffix"
        )
    }

    private fun refresh(refreshToken: String): AuthResponse {
        val result = mockMvc.post("/api/auth/refresh") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(RefreshTokenRequest(refreshToken))
        }.andExpect {
            status { isOk() }
        }
            .andReturn()

        val tokensResponse = objectMapper.readValue<ApiResponse<AuthResponse>>(result.response.contentAsString)
        assertNotNull(tokensResponse.data)

        val tokens = tokensResponse.data
        assertTrue(tokens.accessToken.isNotBlank())
        assertTrue(tokens.refreshToken.isNotBlank())
        return tokens
    }

    private fun assertApiError(
        result: MvcResult,
        expectedCode: String,
        expectedMessage: String
    ) {
        val response = objectMapper.readValue<ApiResponse<Unit>>(result.response.contentAsString)

        assertNull(response.data)
        assertEquals(expectedCode, response.errorCode)
        assertEquals(expectedMessage, response.errorMessage)
    }

    private data class RegisterUserRequest(
        val email: String,
        val username: String,
        val password: String
    )

    private data class RefreshTokenRequest(
        val refreshToken: String
    )
}
