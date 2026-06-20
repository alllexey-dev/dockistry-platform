package io.dockistry.platform.security

import io.dockistry.platform.TestEmailSenderConfig
import io.dockistry.platform.iam.application.CurrentUserProvider
import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.auth.domain.dto.AuthResponse
import io.dockistry.platform.email.application.EmailSendJobProcessor
import io.dockistry.platform.testsupport.AuthTestClient
import io.dockistry.platform.testsupport.InMemoryRawEmailSender
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
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

@SpringBootTest
@AutoConfigureMockMvc
@Import(
    CurrentUserProviderTests.CurrentUserTestController::class,
    TestcontainersConfig::class,
    TestEmailSenderConfig::class
)
@Testcontainers
class CurrentUserProviderTests {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var emailSender: InMemoryRawEmailSender

    @Autowired
    private lateinit var emailSendJobProcessor: EmailSendJobProcessor

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

    private fun registerUser(username: String): AuthResponse {
        return AuthTestClient(
            mockMvc = mockMvc,
            objectMapper = objectMapper,
            emailSender = emailSender,
            emailSendJobProcessor = emailSendJobProcessor
        ).registerAndVerifyUser(
            email = "$username@dockistry.io",
            username = username
        )
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

}
