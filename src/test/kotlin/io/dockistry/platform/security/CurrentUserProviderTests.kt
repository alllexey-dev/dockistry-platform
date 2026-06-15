package io.dockistry.platform.security

import io.dockistry.platform.shared.api.ApiResponse
import io.dockistry.platform.iam.application.CurrentUserProvider
import io.dockistry.platform.TestcontainersConfig
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.testcontainers.junit.jupiter.Testcontainers
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@SpringBootTest
@AutoConfigureMockMvc
@Import(
    CurrentUserProviderTests.CurrentUserTestController::class,
    TestcontainersConfig::class
)
@Testcontainers
class CurrentUserProviderTests {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun `current user provider correctly returns current user on authenticated request`() {
        val username = "username-${UUID.randomUUID().toString().take(8)}"
        val tokens = registerUser(username)

        val mvcResult = mockMvc.get("/my_username") {
            header("Authorization", "Bearer ${tokens.accessToken}")
        }
            .andExpect { status { isOk() } }
            .andReturn()

        val responseBody = mvcResult.response.contentAsString

        assertEquals(username, responseBody)
    }

    private fun registerUser(username: String): Tokens {
        val mvcResult = mockMvc.post("/api/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                RegisterUserRequest(
                    email = "$username@dockistry.io",
                    username = username,
                    password = "password-123"
                )
            )
        }
            .andExpect {
                status { isOk() }
                content { contentType(MediaType.APPLICATION_JSON) }
            }
            .andReturn()

        val apiResponse = objectMapper.readValue<ApiResponse<Tokens>>(mvcResult.response.contentAsString)
        assertNotNull(apiResponse.data)
        return apiResponse.data
    }

    @RestController
    class CurrentUserTestController(
        private val userProvider: CurrentUserProvider
    ) {
        @GetMapping("/my_username")
        fun getUsername(): String {
            return userProvider.getUser().username
        }
    }

    private data class Tokens(
        val refreshToken: String,
        val accessToken: String
    )

    private data class RegisterUserRequest(
        val email: String,
        val username: String,
        val password: String
    )
}
