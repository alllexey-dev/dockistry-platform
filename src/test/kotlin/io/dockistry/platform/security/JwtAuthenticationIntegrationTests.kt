package io.dockistry.platform.security

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import java.util.UUID
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
class JwtAuthenticationIntegrationTests {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun `access token allows authenticated actuator health request`() {
        val tokens = registerUser()

        mockMvc.perform(
            get("/actuator/health")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.accessToken}")
        ).andExpect(status().isOk)
    }

    @Test
    fun `refresh token cannot authenticate actuator health request`() {
        val tokens = registerUser()

        mockMvc.perform(
            get("/actuator/health")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${tokens.refreshToken}")
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `refresh endpoint rotates refresh token and returns a usable access token`() {
        val originalTokens = registerUser()

        val refreshedTokens = refresh(originalTokens.refreshToken)

        assertNotEquals(originalTokens.refreshToken, refreshedTokens.refreshToken)

        mockMvc.perform(
            get("/actuator/health")
                .header(HttpHeaders.AUTHORIZATION, "Bearer ${refreshedTokens.accessToken}")
        ).andExpect(status().isOk)

        mockMvc.perform(
            post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(RefreshTokenRequest(originalTokens.refreshToken)))
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `invalid bearer token is rejected before reaching actuator health`() {
        mockMvc.perform(
            get("/actuator/health")
                .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-valid-jwt")
        ).andExpect(status().isUnauthorized)
    }

    private fun registerUser(): Tokens {
        val suffix = UUID.randomUUID()

        val result = mockMvc.perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        RegisterUserRequest(
                            email = "jwt-$suffix@dockistry.io",
                            username = "jwt-$suffix",
                            password = "password-123"
                        )
                    )
                )
        ).andExpect(status().isOk)
            .andReturn()

        val tokens = objectMapper.readValue<Tokens>(result.response.contentAsString)
        assertTrue(tokens.accessToken.isNotBlank())
        assertTrue(tokens.refreshToken.isNotBlank())
        return tokens
    }

    private fun refresh(refreshToken: String): Tokens {
        val result = mockMvc.perform(
            post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(RefreshTokenRequest(refreshToken)))
        ).andExpect(status().isOk)
            .andReturn()

        val tokens = objectMapper.readValue<Tokens>(result.response.contentAsString)
        assertTrue(tokens.accessToken.isNotBlank())
        assertTrue(tokens.refreshToken.isNotBlank())
        return tokens
    }

    private data class Tokens(
        val accessToken: String = "",
        val refreshToken: String = ""
    )

    private data class RegisterUserRequest(
        val email: String,
        val username: String,
        val password: String
    )

    private data class RefreshTokenRequest(
        val refreshToken: String
    )
}
