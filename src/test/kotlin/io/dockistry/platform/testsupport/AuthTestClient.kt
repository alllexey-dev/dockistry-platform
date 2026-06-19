package io.dockistry.platform.testsupport

import io.dockistry.platform.auth.domain.dto.AuthResponse
import io.dockistry.platform.auth.domain.dto.VerifyEmailByNumberCodeRequest
import io.dockistry.platform.email.application.EmailSendJobProcessor
import io.dockistry.platform.shared.api.ApiResponse
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AuthTestClient(
    private val mockMvc: MockMvc,
    private val objectMapper: ObjectMapper,
    private val emailSender: InMemoryRawEmailSender,
    private val emailSendJobProcessor: EmailSendJobProcessor
) {
    fun registerAndVerifyUser(
        email: String,
        username: String,
        password: String = "password-123"
    ): AuthResponse {
        emailSender.clear()

        mockMvc.post("/api/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                RegisterUserRequest(
                    email = email,
                    username = username,
                    password = password
                )
            )
        }.andExpect {
            status { isOk() }
        }

        emailSendJobProcessor.processEmailSendJobs()

        val verificationEmail = requireNotNull(emailSender.latestTo(email)) {
            "Verification email was not sent to $email"
        }

        val numberCode = VerificationEmailParser.numberCode(verificationEmail)

        val verificationResult = mockMvc.post("/api/auth/email-verification/code") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                VerifyEmailByNumberCodeRequest(
                    email = email,
                    numberCode = numberCode
                )
            )
        }.andExpect {
            status { isOk() }
        }.andReturn()

        val response = objectMapper.readValue<ApiResponse<AuthResponse>>(
            verificationResult.response.contentAsString
        )
        val tokens = assertNotNull(response.data)

        assertTrue(tokens.accessToken.isNotBlank())
        assertTrue(tokens.refreshToken.isNotBlank())

        return tokens
    }

    private data class RegisterUserRequest(
        val email: String,
        val username: String,
        val password: String
    )
}
