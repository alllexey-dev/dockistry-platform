package io.dockistry.platform.projects.api

import io.dockistry.platform.TestEmailSenderConfig
import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.email.application.EmailSendJobProcessor
import io.dockistry.platform.testsupport.AuthTestClient
import io.dockistry.platform.testsupport.InMemoryRawEmailSender
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
import org.testcontainers.junit.jupiter.Testcontainers
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig::class, TestEmailSenderConfig::class)
@Testcontainers
class SuggestSlugControllerTests {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var emailSender: InMemoryRawEmailSender

    @Autowired
    private lateinit var emailSendJobProcessor: EmailSendJobProcessor

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
        val suffix = UUID.randomUUID().toString().take(8)

        return AuthTestClient(
            mockMvc = mockMvc,
            objectMapper = objectMapper,
            emailSender = emailSender,
            emailSendJobProcessor = emailSendJobProcessor
        ).registerAndVerifyUser(
            email = "suggest-slug-$suffix@dockistry.io",
            username = "suggest-slug-$suffix"
        ).accessToken
    }
}
