package io.dockistry.platform.security

import io.dockistry.platform.TestClockConfig
import io.dockistry.platform.TestEmailSenderConfig
import io.dockistry.platform.email.application.EmailSendJobProcessor
import io.dockistry.platform.testsupport.MutableClock
import io.dockistry.platform.TestcontainersConfig
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
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration


@SpringBootTest(
    properties = [
        "jwt.accessExpiration=3000"
    ]
)
@AutoConfigureMockMvc
@Import(
    TestClockConfig::class,
    TestcontainersConfig::class,
    TestEmailSenderConfig::class
)
@Testcontainers
class JwtAccessTokenExpirationIntegrationTests {

    @Autowired
    private lateinit var clock: MutableClock

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var emailSender: InMemoryRawEmailSender

    @Autowired
    private lateinit var emailSendJobProcessor: EmailSendJobProcessor

    @Test
    fun `access token allows private ping before expiry and rejects it after expiry`() {
        val user = registerUserAndGetAccessToken()

        mockMvc.get("/api/private/ping") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${user.accessToken}")
        }.andExpect {
            status { isOk() }
            content { string("pong to ${user.username}") }
        }

        clock.advance(4.seconds.toJavaDuration())

        val expiredTokenResult = mockMvc.get("/api/private/ping") {
            header(HttpHeaders.AUTHORIZATION, "Bearer ${user.accessToken}")
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

    private fun registerUserAndGetAccessToken(): RegisteredAccessToken {
        val suffix = UUID.randomUUID().toString().take(8)
        val username = "jwt-expiry-$suffix"

        val tokens = AuthTestClient(
            mockMvc = mockMvc,
            objectMapper = objectMapper,
            emailSender = emailSender,
            emailSendJobProcessor = emailSendJobProcessor
        ).registerAndVerifyUser(
            email = "jwt-expiry-$suffix@dockistry.io",
            username = username
        )

        return RegisteredAccessToken(username, tokens.accessToken)
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

    private data class RegisteredAccessToken(
        val username: String,
        val accessToken: String
    )

}
