package io.dockistry.platform.auth.api

import io.dockistry.platform.TestClockConfig
import io.dockistry.platform.TestEmailSenderConfig
import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.auth.domain.dto.AuthResponse
import io.dockistry.platform.auth.domain.dto.LoginRequest
import io.dockistry.platform.auth.domain.dto.RegisterRequest
import io.dockistry.platform.auth.domain.dto.ResendEmailVerificationRequest
import io.dockistry.platform.auth.domain.dto.VerifyEmailByNumberCodeRequest
import io.dockistry.platform.auth.infrastructure.EmailVerificationProperties
import io.dockistry.platform.email.application.EmailSendJobProcessor
import io.dockistry.platform.email.config.EmailSendJobConfig
import io.dockistry.platform.shared.api.ApiResponse
import io.dockistry.platform.testsupport.InMemoryRawEmailSender
import io.dockistry.platform.testsupport.MutableClock
import io.dockistry.platform.testsupport.SentEmail
import io.dockistry.platform.testsupport.VerificationEmailParser
import org.junit.jupiter.api.BeforeEach
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.toJavaDuration

@SpringBootTest(
    properties = [
        "app.auth.email-verification.link-code-expiration=60000",
        "app.auth.email-verification.number-code-expiration=60000",
        "app.auth.email-verification.resend-cooldown=1000",
        "app.auth.email-verification.rate-limit=3",
        "app.auth.email-verification.rate-limit-window=10000",
        "app.auth.email-verification.rate-limit-throttle-interval=10000"
    ]
)
@AutoConfigureMockMvc
@Import(
    TestcontainersConfig::class,
    TestEmailSenderConfig::class,
    TestClockConfig::class
)
@Testcontainers
class EmailVerificationIntegrationTests {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var emailSender: InMemoryRawEmailSender

    @Autowired
    private lateinit var emailSendJobProcessor: EmailSendJobProcessor

    @Autowired
    private lateinit var clock: MutableClock

    @Autowired
    private lateinit var emailVerificationProperties: EmailVerificationProperties

    @Autowired
    private lateinit var emailSendJobConfig: EmailSendJobConfig

    @BeforeEach
    fun clearEmailSender() {
        emailSender.clear()
    }

    @Test
    fun `registration endpoint returns message about verification codes being sent`() {
        val user = newUser("registration-message")

        val response = register(user)

        assertEquals(
            "An email with verification code will be sent to your email address soon.",
            response.data
        )
        assertNull(response.errorCode)
        assertNull(response.errorMessage)
    }

    @Test
    fun `unverified registered user cannot log in`() {
        val user = newUser("unverified-login")
        register(user)

        val result = loginExpectingError(user.username, user.password)

        assertApiError(
            result = result,
            expectedCode = "user_has_not_verified_email",
            expectedMessage = "User hasn't verified email"
        )
    }

    @Test
    fun `expired verification codes do not verify email`() {
        val user = newUser("expired-codes")
        val verificationEmail = registerAndSendVerificationEmail(user)
        val numberCode = VerificationEmailParser.numberCode(verificationEmail)
        val linkCode = VerificationEmailParser.linkCode(
            verificationEmail,
            emailVerificationProperties.linkPath
        )

        advancePast(
            maxOf(
                emailVerificationProperties.linkCodeExpiration,
                emailVerificationProperties.numberCodeExpiration
            )
        )

        val numberCodeResult = verifyNumberCodeExpectingForbidden(user.email, numberCode)
        assertApiError(
            result = numberCodeResult,
            expectedCode = "email_or_verification_code_invalid",
            expectedMessage = "Email or verification code is invalid"
        )

        val linkCodeResult = verifyLinkCodeExpectingForbidden(linkCode)
        assertApiError(
            result = linkCodeResult,
            expectedCode = "verification_link_invalid",
            expectedMessage = "Verification link is invalid"
        )
    }

    @Test
    fun `number code verification returns tokens that allow private ping`() {
        val user = newUser("number-code")
        val verificationEmail = registerAndSendVerificationEmail(user)
        val numberCode = VerificationEmailParser.numberCode(verificationEmail)

        val tokens = verifyNumberCode(user.email, numberCode)

        assertPrivatePing(tokens.accessToken, user.username)
    }

    @Test
    fun `link code verification returns tokens that allow private ping`() {
        val user = newUser("link-code")
        val verificationEmail = registerAndSendVerificationEmail(user)
        val linkCode = VerificationEmailParser.linkCode(
            verificationEmail,
            emailVerificationProperties.linkPath
        )

        val tokens = verifyLinkCode(linkCode)

        assertPrivatePing(tokens.accessToken, user.username)
    }

    @Test
    fun `resend under cooldown does not send new codes but resend after cooldown does`() {
        val user = newUser("resend-cooldown")
        val firstEmail = registerAndSendVerificationEmail(user)

        resendCodes(user.email)
        processEmailJobs()
        assertEquals(listOf(firstEmail), emailSender.allTo(user.email))

        advancePast(emailVerificationProperties.resendCooldown)

        resendCodes(user.email)
        processEmailJobs()

        val sentEmails = emailSender.allTo(user.email)
        assertEquals(2, sentEmails.size)
        assertNotEquals(firstEmail.text, sentEmails.last().text)
    }

    @Test
    fun `rate limited user does not get new verification codes until throttle window resets`() {
        val user = newUser("rate-limit-window")
        registerAndSendVerificationEmail(user)
        requestCodesUntilRateLimitIsReached(user.email)
        val emailsBeforeThrottle = emailSender.allTo(user.email)

        advancePast(emailVerificationProperties.resendCooldown)
        resendCodes(user.email)
        processEmailJobs()
        assertEquals(emailsBeforeThrottle, emailSender.allTo(user.email))

        advancePast(emailVerificationProperties.resendCooldown)
        resendCodes(user.email)
        processEmailJobs()
        assertEquals(emailsBeforeThrottle, emailSender.allTo(user.email))

        advancePast(
            maxOf(
                emailVerificationProperties.rateLimitThrottleInterval,
                emailVerificationProperties.rateLimitWindow
            )
        )
        resendCodes(user.email)
        processEmailJobs()

        val emailsAfterWindowReset = emailSender.allTo(user.email)
        assertEquals(emailsBeforeThrottle.size + 1, emailsAfterWindowReset.size)
        assertNotEquals(emailsBeforeThrottle.last().text, emailsAfterWindowReset.last().text)
    }

    @Test
    fun `rate limited user can still verify email with latest verification code`() {
        val user = newUser("rate-limit-latest-code")
        registerAndSendVerificationEmail(user)
        requestCodesUntilRateLimitIsReached(user.email)
        val latestEmailBeforeThrottle = latestEmailTo(user.email)

        advancePast(emailVerificationProperties.resendCooldown)
        resendCodes(user.email)
        processEmailJobs()
        assertEquals(latestEmailBeforeThrottle, latestEmailTo(user.email))

        val tokens = verifyNumberCode(
            email = user.email,
            numberCode = VerificationEmailParser.numberCode(latestEmailBeforeThrottle)
        )

        assertPrivatePing(tokens.accessToken, user.username)
    }

    @Test
    fun `duplicate unverified registration updates username and password before verification`() {
        val suffix = UUID.randomUUID().toString().take(8)
        val email = "duplicate-unverified-$suffix@dockistry.io"
        val oldUser = TestUser(
            email = email,
            username = "old-$suffix",
            password = "old-password-123"
        )
        val updatedUser = TestUser(
            email = email,
            username = "new-$suffix",
            password = "new-password-123"
        )

        val verificationEmail = registerAndSendVerificationEmail(oldUser)

        register(updatedUser)
        processEmailJobs()
        assertEquals(listOf(verificationEmail), emailSender.allTo(email))

        verifyNumberCode(
            email = email,
            numberCode = VerificationEmailParser.numberCode(verificationEmail)
        )

        loginExpectingError(oldUser.username, oldUser.password)
            .also {
                assertApiError(
                    result = it,
                    expectedCode = "invalid_username_or_password"
                )
            }

        val tokens = login(updatedUser.username, updatedUser.password)
        assertPrivatePing(tokens.accessToken, updatedUser.username)
    }

    @Test
    fun `duplicate unverified registration with taken username returns conflict`() {
        val suffix = UUID.randomUUID().toString().take(8)
        val takenUsername = "taken-$suffix"
        val email = "duplicate-unverified-taken-$suffix@dockistry.io"

        register(
            TestUser(
                email = "taken-$suffix@dockistry.io",
                username = takenUsername,
                password = "password-123"
            )
        )
        register(
            TestUser(
                email = email,
                username = "original-$suffix",
                password = "password-123"
            )
        )

        val result = mockMvc.post("/api/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                RegisterRequest(
                    email = email,
                    username = takenUsername,
                    password = "password-123"
                )
            )
        }.andExpect {
            status { isConflict() }
            content { contentType(MediaType.APPLICATION_JSON) }
        }.andReturn()

        assertApiError(
            result = result,
            expectedCode = "user_already_exists",
            expectedMessage = "User with username = $takenUsername already exists"
        )
    }

    @Test
    fun `failed verification email send does not leave active challenge that blocks retry`() {
        val user = newUser("email-send-failure")

        register(user)
        emailSender.failWith(RuntimeException("SMTP unavailable"))
        processEmailJobs()
        assertNull(emailSender.latestTo(user.email))

        emailSender.resumeSending()
        advancePast(emailSendJobConfig.retryInterval)
        processEmailJobs()

        val verificationEmail = latestEmailTo(user.email)
        val tokens = verifyNumberCode(
            email = user.email,
            numberCode = VerificationEmailParser.numberCode(verificationEmail)
        )
        assertPrivatePing(tokens.accessToken, user.username)
    }

    private fun registerAndSendVerificationEmail(user: TestUser): SentEmail {
        register(user)
        processEmailJobs()
        return latestEmailTo(user.email)
    }

    private fun requestCodesUntilRateLimitIsReached(email: String) {
        repeat(emailVerificationProperties.rateLimit - 1) {
            advancePast(emailVerificationProperties.resendCooldown)
            resendCodes(email)
            processEmailJobs()
        }

        assertEquals(emailVerificationProperties.rateLimit, emailSender.allTo(email).size)
    }

    private fun register(user: TestUser): ApiResponse<String> {
        val result = mockMvc.post("/api/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                RegisterRequest(
                    email = user.email,
                    username = user.username,
                    password = user.password
                )
            )
        }.andExpect {
            status { isOk() }
            content { contentType(MediaType.APPLICATION_JSON) }
        }.andReturn()

        return objectMapper.readValue(result.response.contentAsString)
    }

    private fun resendCodes(email: String): ApiResponse<String> {
        val result = mockMvc.post("/api/auth/email-verification/resend") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                ResendEmailVerificationRequest(email)
            )
        }.andExpect {
            status { isOk() }
            content { contentType(MediaType.APPLICATION_JSON) }
        }.andReturn()

        return objectMapper.readValue(result.response.contentAsString)
    }

    private fun verifyNumberCode(email: String, numberCode: String): AuthResponse {
        val result = mockMvc.post("/api/auth/email-verification/code") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                VerifyEmailByNumberCodeRequest(
                    email = email,
                    numberCode = numberCode
                )
            )
        }.andExpect {
            status { isOk() }
            content { contentType(MediaType.APPLICATION_JSON) }
        }.andReturn()

        return responseData(result)
    }

    private fun verifyLinkCode(linkCode: String): AuthResponse {
        val result = mockMvc.post(verificationLinkPath(linkCode)) {
            contentType = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            content { contentType(MediaType.APPLICATION_JSON) }
        }.andReturn()

        return responseData(result)
    }

    private fun verifyNumberCodeExpectingForbidden(email: String, numberCode: String): MvcResult {
        return mockMvc.post("/api/auth/email-verification/code") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                VerifyEmailByNumberCodeRequest(
                    email = email,
                    numberCode = numberCode
                )
            )
        }.andExpect {
            status { isForbidden() }
            content { contentType(MediaType.APPLICATION_JSON) }
        }.andReturn()
    }

    private fun verifyLinkCodeExpectingForbidden(linkCode: String): MvcResult {
        return mockMvc.post(verificationLinkPath(linkCode)) {
            contentType = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isForbidden() }
            content { contentType(MediaType.APPLICATION_JSON) }
        }.andReturn()
    }

    private fun verificationLinkPath(linkCode: String): String {
        return "${emailVerificationProperties.linkPath}/$linkCode"
    }

    private fun login(username: String, password: String): AuthResponse {
        val result = mockMvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                LoginRequest(
                    username = username,
                    password = password
                )
            )
        }.andExpect {
            status { isOk() }
            content { contentType(MediaType.APPLICATION_JSON) }
        }.andReturn()

        return responseData(result)
    }

    private fun loginExpectingError(username: String, password: String): MvcResult {
        return mockMvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                LoginRequest(
                    username = username,
                    password = password
                )
            )
        }.andExpect {
            status { is4xxClientError() }
            content { contentType(MediaType.APPLICATION_JSON) }
        }.andReturn()
    }

    private fun assertPrivatePing(accessToken: String, username: String) {
        mockMvc.get("/api/private/ping") {
            header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
        }.andExpect {
            status { isOk() }
            content { string("pong to $username") }
        }
    }

    private fun assertApiError(
        result: MvcResult,
        expectedCode: String,
        expectedMessage: String? = null
    ) {
        val response = objectMapper.readValue<ApiResponse<Unit>>(result.response.contentAsString)

        assertNull(response.data)
        assertEquals(expectedCode, response.errorCode)
        if (expectedMessage != null) {
            assertEquals(expectedMessage, response.errorMessage)
        } else {
            assertEquals(response.errorMessage?.isNotBlank(), true)
        }
    }

    private fun responseData(result: MvcResult): AuthResponse {
        val response = objectMapper.readValue<ApiResponse<AuthResponse>>(result.response.contentAsString)
        return assertNotNull(response.data)
    }

    private fun processEmailJobs() {
        emailSendJobProcessor.processEmailSendJobs()
    }

    private fun advancePast(durationMillis: Long) {
        clock.advance((durationMillis + 1).milliseconds.toJavaDuration())
    }

    private fun latestEmailTo(email: String): SentEmail {
        return requireNotNull(emailSender.latestTo(email)) {
            "Verification email was not sent to $email"
        }
    }

    private fun newUser(prefix: String): TestUser {
        val suffix = UUID.randomUUID().toString().take(8)
        return TestUser(
            email = "$prefix-$suffix@dockistry.io",
            username = "$prefix-$suffix",
            password = "password-123"
        )
    }

    private data class TestUser(
        val email: String,
        val username: String,
        val password: String
    )
}
