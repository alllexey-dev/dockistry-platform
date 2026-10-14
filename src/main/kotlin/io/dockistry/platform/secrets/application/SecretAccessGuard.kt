package io.dockistry.platform.secrets.application

import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.secrets.domain.Secret
import io.dockistry.platform.secrets.domain.SecretNotFoundException
import io.dockistry.platform.secrets.infrastructure.SecretRepository
import org.springframework.stereotype.Service

@Service
class SecretAccessGuard(
    private val secretRepository: SecretRepository,
) {
    fun requireOwner(
        secretId: String,
        principal: Principal,
    ): Secret {
        val secret = secretRepository.findById(secretId).orElseThrow { notFound() }

        if (secret.ownerPrincipal.id != principal.id) throw notFound()

        return secret
    }

    /** Locks the logical Secret row until the caller's transaction ends, then checks owner. */
    fun requireOwnerForUpdate(
        secretId: String,
        principal: Principal,
    ): Secret {
        val secret = secretRepository.findByIdForNoKeyUpdate(secretId) ?: throw notFound()

        if (secret.ownerPrincipal.id != principal.id) throw notFound()

        return secret
    }

    private fun notFound() =
        SecretNotFoundException("Secret with provided ID couldn't be found")
}
