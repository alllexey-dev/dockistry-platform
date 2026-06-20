package io.dockistry.platform.auth.infrastructure

import org.springframework.beans.factory.InitializingBean
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.stereotype.Component

@Component
class ProductionSecretDefaultsGuard(
    private val jwtConfig: JwtConfig,
    private val emailVerificationProperties: EmailVerificationProperties,
    private val environment: Environment
) : InitializingBean {

    override fun afterPropertiesSet() {
        if (!environment.acceptsProfiles(Profiles.of(PRODUCTION_PROFILE))) {
            return
        }

        require(jwtConfig.secret != DEFAULT_JWT_SECRET) {
            "jwt.secret must be overridden when the prod profile is active"
        }
        require(emailVerificationProperties.hashSecretBase64 != DEFAULT_EMAIL_VERIFICATION_HASH_SECRET_BASE64) {
            "app.auth.email-verification.hash-secret-base64 must be overridden when the prod profile is active"
        }
    }

    private companion object {
        const val PRODUCTION_PROFILE = "prod"
        const val DEFAULT_JWT_SECRET = "secret"
        const val DEFAULT_EMAIL_VERIFICATION_HASH_SECRET_BASE64 = "MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDA="
    }
}
