package io.dockistry.platform.auth.api

import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.shared.api.ApiResponse
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.post
import org.testcontainers.junit.jupiter.Testcontainers
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class)
@Testcontainers
class AuthValidationIntegrationTests {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun `register rejects invalid dto fields`() {
        val result = mockMvc.post("/api/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                RegisterUserRequest(
                    email = "not-an-email",
                    username = "ab",
                    password = "short"
                )
            )
        }.andExpect {
            status { isBadRequest() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
        }.andReturn()

        assertValidationError(
            result = result,
            expectedMessages = listOf(
                "Username must consist of 3-32 English letters, digits, dots, underscores, dashes",
                "Password must be 8-128 printable ASCII characters"
            )
        )
    }

    @Test
    fun `login rejects invalid dto fields`() {
        val result = mockMvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                LoginUserRequest(
                    username = "invalid username",
                    password = "short"
                )
            )
        }.andExpect {
            status { isBadRequest() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
        }.andReturn()

        assertValidationError(
            result = result,
            expectedMessages = listOf(
                "Username must consist of 3-32 English letters, digits, dots, underscores, dashes",
                "Password must be 8-128 printable ASCII characters"
            )
        )
    }

    @Test
    fun `refresh rejects blank refresh token`() {
        val result = mockMvc.post("/api/auth/refresh") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(RefreshTokenRequest(refreshToken = " "))
        }.andExpect {
            status { isBadRequest() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
        }.andReturn()

        assertValidationError(
            result = result,
            expectedMessages = listOf("must not be blank")
        )
    }

    private fun assertValidationError(
        result: MvcResult,
        expectedMessages: List<String>
    ) {
        val response = objectMapper.readValue<ApiResponse<Unit>>(result.response.contentAsString)

        assertNull(response.data)
        assertEquals("validation_error", response.errorCode)

        val errorMessage = assertNotNull(response.errorMessage)
        assertTrue(errorMessage.isNotBlank())
        expectedMessages.forEach {
            assertTrue(
                errorMessage.contains(it),
                "Expected validation response to contain '$it', but was '$errorMessage'"
            )
        }
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
}
