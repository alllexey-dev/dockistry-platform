package io.dockistry.platform.security

import io.dockistry.platform.TestEmailSenderConfig
import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.auth.application.JwtTokenProvider
import io.dockistry.platform.auth.domain.dto.AuthResponse
import io.dockistry.platform.auth.infrastructure.RefreshTokenRepository
import io.dockistry.platform.email.application.EmailSendJobProcessor
import io.dockistry.platform.shared.api.ApiResponse
import io.dockistry.platform.testsupport.AuthTestClient
import io.dockistry.platform.testsupport.InMemoryRawEmailSender
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
import org.testcontainers.junit.jupiter.Testcontainers
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
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

    @Autowired
    private lateinit var tokenProvider: JwtTokenProvider

    @Autowired
    private lateinit var refreshTokenRepository: RefreshTokenRepository

    @Test
    fun `access token allows authenticated private ping request`() {
        val user = registerUser()

        mockMvc.get("/api/private/ping") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${user.tokens.accessToken}")
        }.andExpect {
            status { isOk() }
            content { string("pong to ${user.username}") }
        }
    }

    @Test
    fun `refresh token cannot authenticate private ping request`() {
        val user = registerUser()

        val result = mockMvc.get("/api/private/ping") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${user.tokens.refreshToken}")
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
        val user = registerUser()

        val refreshedTokens = refresh(user.tokens.refreshToken)

        assertNotEquals(user.tokens.refreshToken, refreshedTokens.refreshToken)

        mockMvc.get("/api/private/ping") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${refreshedTokens.accessToken}")
        }.andExpect {
            status { isOk() }
            content { string("pong to ${user.username}") }
        }

        val revokedTokenResult = mockMvc.post("/api/auth/refresh") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(RefreshTokenRequest(user.tokens.refreshToken))
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
    fun `invalid bearer token is rejected before reaching private ping`() {
        val result = mockMvc.get("/api/private/ping") {
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

    @Test
    fun `logout rejects invalid refresh token and access token`() {
        val user = registerUser()

        logoutExpectingUnauthorized("not-a-valid-refresh-token")
        logoutExpectingUnauthorized(user.tokens.accessToken)
    }

    @Test
    fun `logout revokes refresh token so it cannot be used to refresh`() {
        val user = registerUser()

        mockMvc.post("/api/auth/logout") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(RefreshTokenRequest(user.tokens.refreshToken))
        }.andExpect {
            status { isOk() }
            content { contentType(MediaType.APPLICATION_JSON) }
        }

        val revokedTokenResult = refreshExpectingUnauthorized(user.tokens.refreshToken)
        assertApiError(
            result = revokedTokenResult,
            expectedCode = "token_revoked",
            expectedMessage = "Token has been already revoked"
        )
    }

    @Test
    fun `logout all requires authentication`() {
        val result = mockMvc.post("/api/auth/logout-all") {
            contentType = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isUnauthorized() }
            content { contentType(MediaType.APPLICATION_JSON) }
        }.andReturn()

        assertApiError(
            result = result,
            expectedCode = "authentication_required",
            expectedMessage = "Full authentication is required to access this resource"
        )
    }

    @Test
    fun `logout all revokes every active refresh token for authenticated user`() {
        val user = registerUser()
        val tokenPairs = buildList {
            add(user.tokens)
            repeat(4) {
                add(login(user.username))
            }
        }

        tokenPairs.forEach {
            assertRefreshTokenActive(it.refreshToken)
        }

        mockMvc.post("/api/auth/logout-all") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${user.tokens.accessToken}")
        }.andExpect {
            status { isOk() }
            content { contentType(MediaType.APPLICATION_JSON) }
        }

        tokenPairs.forEach {
            val revokedTokenResult = refreshExpectingUnauthorized(it.refreshToken)
            assertApiError(
                result = revokedTokenResult,
                expectedCode = "token_revoked",
                expectedMessage = "Token has been already revoked"
            )
        }
    }

    private fun registerUser(): RegisteredUser {
        val suffix = UUID.randomUUID().toString().take(8)
        val username = "jwt-$suffix"

        val tokens = AuthTestClient(
            mockMvc = mockMvc,
            objectMapper = objectMapper,
            emailSender = emailSender,
            emailSendJobProcessor = emailSendJobProcessor
        ).registerAndVerifyUser(
            email = "jwt-$suffix@dockistry.io",
            username = username
        )

        return RegisteredUser(username, tokens)
    }

    private fun login(username: String): AuthResponse {
        val result = mockMvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                LoginUserRequest(
                    username = username,
                    password = "password-123"
                )
            )
        }.andExpect {
            status { isOk() }
        }.andReturn()

        return responseData(result)
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

    private fun refreshExpectingUnauthorized(refreshToken: String): MvcResult {
        return mockMvc.post("/api/auth/refresh") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(RefreshTokenRequest(refreshToken))
        }.andExpect {
            status { isUnauthorized() }
            content { contentType(MediaType.APPLICATION_JSON) }
        }.andReturn()
    }

    private fun logoutExpectingUnauthorized(refreshToken: String): MvcResult {
        return mockMvc.post("/api/auth/logout") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(RefreshTokenRequest(refreshToken))
        }.andExpect {
            status { isUnauthorized() }
            content { contentType(MediaType.APPLICATION_JSON) }
        }.andReturn()
    }

    private fun assertRefreshTokenActive(refreshToken: String) {
        assertTrue(tokenProvider.validateToken(refreshToken))
        assertTrue(tokenProvider.isRefreshToken(refreshToken))

        val tokenId = UUID.fromString(tokenProvider.getTokenIdFromToken(refreshToken))
        val tokenEntity = assertNotNull(refreshTokenRepository.findRefreshTokenById(tokenId))
        assertNull(tokenEntity.revokedAt)
    }

    private fun responseData(result: MvcResult): AuthResponse {
        val tokensResponse = objectMapper.readValue<ApiResponse<AuthResponse>>(result.response.contentAsString)
        val tokens = assertNotNull(tokensResponse.data)

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

    private data class LoginUserRequest(
        val username: String,
        val password: String
    )

    private data class RefreshTokenRequest(
        val refreshToken: String
    )

    private data class RegisteredUser(
        val username: String,
        val tokens: AuthResponse
    )
}
