package io.dockistry.platform.auth.infrastructure

import org.springframework.beans.factory.InitializingBean
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.stereotype.Component

@Component
class ProductionSecretDefaultsGuard(
    private val jwtConfig: JwtConfig,
    private val environment: Environment
) : InitializingBean {

    override fun afterPropertiesSet() {
        if (!environment.acceptsProfiles(Profiles.of(PRODUCTION_PROFILE))) {
            return
        }

        require(jwtConfig.secret != DEFAULT_JWT_SECRET) {
            "jwt.secret must be overridden when the prod profile is active"
        }
    }

    private companion object {
        const val PRODUCTION_PROFILE = "prod"
        const val DEFAULT_JWT_SECRET = "secret"
    }
}
