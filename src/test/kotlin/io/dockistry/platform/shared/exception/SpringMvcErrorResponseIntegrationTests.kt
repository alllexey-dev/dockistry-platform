package io.dockistry.platform.shared.exception

import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.auth.domain.dto.AuthResponse
import io.dockistry.platform.shared.api.ApiResponse
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class)
@Testcontainers
class SpringMvcErrorResponseIntegrationTests {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun `missing request parameter returns api error response`() {
        val result = mockMvc.get("/api/util/suggest_slug") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${registerUserAndGetAccessToken()}")
        }.andExpect {
            status { isBadRequest() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
        }.andReturn()

        assertApiErrorResponse(
            result = result,
            expectedCode = "bad_request",
            expectedMessage = "Required parameter 'toSlug' is not present."
        )
    }

    @Test
    fun `malformed json request body returns api error response`() {
        val result = mockMvc.post("/api/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = "{"
        }.andExpect {
            status { isBadRequest() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
        }.andReturn()

        assertApiErrorResponse(
            result = result,
            expectedCode = "bad_request"
        )
    }

    @Test
    fun `unsupported http method returns api error response`() {
        val result = mockMvc.post("/api/util/suggest_slug") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${registerUserAndGetAccessToken()}")
            param("toSlug", "Cafe Deploy")
        }.andExpect {
            status { isMethodNotAllowed() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            header { string(HttpHeaders.ALLOW, "GET") }
        }.andReturn()

        assertApiErrorResponse(
            result = result,
            expectedCode = "method_not_allowed"
        )
    }

    @Test
    fun `unsupported media type returns api error response`() {
        val result = mockMvc.post("/api/auth/register") {
            contentType = MediaType.TEXT_PLAIN
            content = objectMapper.writeValueAsString(
                RegisterUserRequest(
                    email = "unsupported-media-${UUID.randomUUID()}@dockistry.io",
                    username = "unsupported-media-${UUID.randomUUID()}",
                    password = "password-123"
                )
            )
        }.andExpect {
            status { isUnsupportedMediaType() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
        }.andReturn()

        assertApiErrorResponse(
            result = result,
            expectedCode = "unsupported_media_type"
        )
    }

    private fun assertApiErrorResponse(
        result: MvcResult,
        expectedCode: String,
        expectedMessage: String? = null
    ) {
        val response = objectMapper.readValue<ApiResponse<Unit>>(result.response.contentAsString)

        assertNull(response.data)
        assertEquals(expectedCode, response.errorCode)

        val errorMessage = assertNotNull(response.errorMessage)
        assertTrue(errorMessage.isNotBlank())
        if (expectedMessage != null) {
            assertEquals(expectedMessage, errorMessage)
        }

        val rawResponse = objectMapper.readValue<Map<String, Any?>>(result.response.contentAsString)
        assertTrue(rawResponse.containsKey("data"))
        assertTrue(rawResponse.containsKey("errorMessage"))
        assertTrue(rawResponse.containsKey("errorCode"))
        assertFalse(rawResponse.containsKey("title"))
        assertFalse(rawResponse.containsKey("detail"))
        assertFalse(rawResponse.containsKey("status"))
        assertFalse(rawResponse.containsKey("instance"))
    }

    private fun registerUserAndGetAccessToken(): String {
        val suffix = UUID.randomUUID()

        val result = mockMvc.post("/api/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                RegisterUserRequest(
                    email = "mvc-errors-$suffix@dockistry.io",
                    username = "mvc-errors-$suffix",
                    password = "password-123"
                )
            )
        }.andExpect {
            status { isOk() }
        }.andReturn()

        val response = objectMapper.readValue<ApiResponse<AuthResponse>>(result.response.contentAsString)
        val tokens = assertNotNull(response.data)

        assertTrue(tokens.accessToken.isNotBlank())
        return tokens.accessToken
    }

    private data class RegisterUserRequest(
        val email: String,
        val username: String,
        val password: String
    )
}
