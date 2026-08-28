package io.dockistry.platform.secrets.application

import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.secrets.application.ports.SecretEncryptor
import io.dockistry.platform.secrets.domain.Secret
import io.dockistry.platform.secrets.domain.dto.SecretDto
import io.dockistry.platform.secrets.infrastructure.SecretRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class SecretService(
    private val secretRepository: SecretRepository,
    private val secretEncryptor: SecretEncryptor,
    private val clock: Clock
) {
    @Transactional
    fun create(
        name: String,
        description: String?,
        values: Map<String, String>,
        ownerPrincipal: Principal
    ): String {
        val now = clock.instant()
        val secret = Secret(
            name = name,
            description = description,
            ownerPrincipal = ownerPrincipal,
            encryptedValues = encryptPayload(values),
            createdAt = now,
            updatedAt = now
        )

        return secretRepository.save(secret).id
    }

    @Transactional(readOnly = true)
    fun list(ownerPrincipal: Principal): List<SecretDto> =
        secretRepository.listByOwnerPrincipal(ownerPrincipal).map(::toDto)

    private fun toDto(secret: Secret): SecretDto = SecretDto(
        id = secret.id,
        name = secret.name,
        description = secret.description,
        version = secret.version,
        createdAt = secret.createdAt,
        updatedAt = secret.updatedAt,
        keys = secret.encryptedValues.keys
    )

    private fun encryptPayload(values: Map<String, String>): Map<String, String> =
        values.mapValues { (_, value) -> secretEncryptor.encrypt(value) }
}
