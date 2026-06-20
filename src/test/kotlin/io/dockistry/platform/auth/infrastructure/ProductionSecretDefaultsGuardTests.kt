package io.dockistry.platform.auth.infrastructure

import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProductionSecretDefaultsGuardTests {

    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(TestConfig::class.java)
        .withPropertyValues(
            "jwt.secret=prod-jwt-secret",
            "app.auth.email-verification.link-path=/api/auth/email-verification/link",
            "app.auth.email-verification.hash-secret-base64=$PROD_EMAIL_VERIFICATION_HASH_SECRET_BASE64",
            "app.auth.email-verification.link-code-expiration=86400000",
            "app.auth.email-verification.number-code-expiration=900000",
            "app.auth.email-verification.resend-cooldown=60000",
            "app.auth.email-verification.rate-limit=5",
            "app.auth.email-verification.rate-limit-window=3600000",
            "app.auth.email-verification.rate-limit-throttle-interval=43200000"
        )

    @Test
    fun `prod profile rejects default jwt secret`() {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=prod",
                "jwt.secret=secret"
            )
            .run { context ->
                val startupFailure = assertNotNull(context.startupFailure)

                assertTrue(startupFailure.containsMessage("jwt.secret must be overridden"))
            }
    }

    @Test
    fun `prod profile allows non-default jwt secret`() {
        contextRunner
            .withPropertyValues("spring.profiles.active=prod")
            .run { context ->
                assertNull(context.startupFailure)
            }
    }

    @Test
    fun `non-prod profile allows default jwt secret`() {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=dev",
                "jwt.secret=secret"
            )
            .run { context ->
                assertNull(context.startupFailure)
            }
    }

    @Test
    fun `prod profile rejects default email verification hash secret`() {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=prod",
                "app.auth.email-verification.hash-secret-base64=$DEFAULT_EMAIL_VERIFICATION_HASH_SECRET_BASE64"
            )
            .run { context ->
                val startupFailure = assertNotNull(context.startupFailure)

                assertTrue(
                    startupFailure.containsMessage(
                        "app.auth.email-verification.hash-secret-base64 must be overridden"
                    )
                )
            }
    }

    @Test
    fun `prod profile allows non-default email verification hash secret`() {
        contextRunner
            .withPropertyValues("spring.profiles.active=prod")
            .run { context ->
                assertNull(context.startupFailure)
            }
    }

    @Test
    fun `non-prod profile allows default email verification hash secret`() {
        contextRunner
            .withPropertyValues(
                "spring.profiles.active=dev",
                "app.auth.email-verification.hash-secret-base64=$DEFAULT_EMAIL_VERIFICATION_HASH_SECRET_BASE64"
            )
            .run { context ->
                assertNull(context.startupFailure)
            }
    }

    private fun Throwable.containsMessage(expected: String): Boolean {
        var current: Throwable? = this

        while (current != null) {
            if (current.message?.contains(expected) == true) {
                return true
            }

            current = current.cause
        }

        return false
    }

    @Configuration
    @EnableConfigurationProperties(JwtConfig::class, EmailVerificationProperties::class)
    @Import(ProductionSecretDefaultsGuard::class)
    private class TestConfig

    private companion object {
        const val DEFAULT_EMAIL_VERIFICATION_HASH_SECRET_BASE64 = "MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDA="
        const val PROD_EMAIL_VERIFICATION_HASH_SECRET_BASE64 = "cHJvZHVjdGlvbi1lbWFpbC12ZXJpZmljYXRpb24taGFzaC1rZXk="
    }
}
