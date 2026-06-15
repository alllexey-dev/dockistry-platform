package io.dockistry.platform.projects.api

import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.auth.domain.dto.AuthResponse
import io.dockistry.platform.shared.api.ApiResponse
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.testcontainers.junit.jupiter.Testcontainers
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import java.util.UUID
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class)
@Testcontainers
class SuggestSlugControllerTests {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun `suggest slug returns slugified value`() {
        val accessToken = registerUserAndGetAccessToken()

        mockMvc.get("/api/util/suggest_slug") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
            param("toSlug", "Cafe Deploy")
        }.andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.data") { value("cafe-deploy") }
            jsonPath("$.errorMessage") { value(nullValue()) }
            jsonPath("$.errorCode") { value(nullValue()) }
        }
    }

    @Test
    fun `suggest slug returns api error response when required parameter is missing`() {
        val accessToken = registerUserAndGetAccessToken()

        mockMvc.get("/api/util/suggest_slug") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
        }.andExpect {
            status { isBadRequest() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.data") { value(nullValue()) }
            jsonPath("$.errorMessage") { value("Required parameter 'toSlug' is not present.") }
            jsonPath("$.errorCode") { value("bad_request") }
        }
    }

    @Test
    fun `suggest slug requires authentication`() {
        mockMvc.get("/api/util/suggest_slug") {
            param("toSlug", "Cafe Deploy")
        }.andExpect {
            status { isUnauthorized() }
            content { contentTypeCompatibleWith(MediaType.APPLICATION_JSON) }
            jsonPath("$.data") { value(nullValue()) }
            jsonPath("$.errorMessage") { value("Full authentication is required to access this resource") }
            jsonPath("$.errorCode") { value("authentication_required") }
        }
    }

    private fun registerUserAndGetAccessToken(): String {
        val suffix = UUID.randomUUID()

        val result = mockMvc.post("/api/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                RegisterUserRequest(
                    email = "suggest-slug-$suffix@dockistry.io",
                    username = "suggest-slug-$suffix",
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
