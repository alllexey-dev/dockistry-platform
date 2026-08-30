package io.dockistry.platform.secrets.application

import io.dockistry.platform.iam.domain.Principal
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
    ) {
        val ownerId =
            secretRepository.getOwnerIdById(secretId)
                ?: throw notFound()

        if (ownerId != principal.id) throw notFound()
    }

    private fun notFound() =
        SecretNotFoundException("Secret with provided ID couldn't be found")
}
