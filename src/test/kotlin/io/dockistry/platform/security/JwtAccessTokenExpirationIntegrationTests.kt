package io.dockistry.platform.security

import io.dockistry.platform.TestClockConfig
import io.dockistry.platform.testsupport.MutableClock
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import java.util.UUID
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

        mockMvc.perform(
            get("/actuator/health")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
        ).andExpect(status().isOk)

        clock.advance(4.seconds.toJavaDuration())

        mockMvc.perform(
            get("/actuator/health")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
        ).andExpect(status().isUnauthorized)
    }

    private fun registerUserAndGetAccessToken(): String {
        val suffix = UUID.randomUUID()

        val result = mockMvc.perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        RegisterUserRequest(
                            email = "jwt-expiry-$suffix@dockistry.io",
                            username = "jwt-expiry-$suffix",
                            password = "password-123"
                        )
                    )
                )
        ).andExpect(status().isOk)
            .andReturn()

        val accessToken = objectMapper.readValue<Tokens>(result.response.contentAsString).accessToken
        assertTrue(accessToken.isNotBlank())
        return accessToken
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
