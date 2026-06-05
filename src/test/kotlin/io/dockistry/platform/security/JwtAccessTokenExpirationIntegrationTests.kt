package io.dockistry.platform.security

import io.dockistry.platform.TestClockConfig
import io.dockistry.platform.shared.api.ApiResponse
import io.dockistry.platform.testsupport.MutableClock
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
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration


@SpringBootTest(
    properties = [
        "jwt.accessExpiration=3000"
    ]
)
@AutoConfigureMockMvc
@Import(TestClockConfig::class)
class JwtAccessTokenExpirationIntegrationTests {

    @Autowired
    private lateinit var clock: MutableClock

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun `access token allows health before expiry and rejects it after expiry`() {
        val accessToken = registerUserAndGetAccessToken()

        mockMvc.get("/actuator/health") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
        }.andExpect {
            status { isOk() }
        }

        clock.advance(4.seconds.toJavaDuration())

        val expiredTokenResult = mockMvc.get("/actuator/health") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
        }.andExpect {
            status { isUnauthorized() }
            content { contentType(MediaType.APPLICATION_JSON) }
        }
            .andReturn()

        assertApiError(
            result = expiredTokenResult,
            expectedCode = "authentication_required",
            expectedMessage = "Token is invalid or of wrong type"
        )
    }

    private fun registerUserAndGetAccessToken(): String {
        val suffix = UUID.randomUUID()

        val result = mockMvc.post("/api/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                RegisterUserRequest(
                    email = "jwt-expiry-$suffix@dockistry.io",
                    username = "jwt-expiry-$suffix",
                    password = "password-123"
                )
            )
        }.andExpect {
            status { isOk() }
        }
            .andReturn()

        val tokensResponse = objectMapper.readValue<ApiResponse<Tokens>>(result.response.contentAsString)
        assertNotNull(tokensResponse.data)

        val accessToken = tokensResponse.data.accessToken
        assertTrue(accessToken.isNotBlank())
        return accessToken
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

    private data class Tokens(
        val accessToken: String = "",
        val refreshToken: String = ""
    )

    private data class RegisterUserRequest(
        val email: String,
        val username: String,
        val password: String
    )
}
