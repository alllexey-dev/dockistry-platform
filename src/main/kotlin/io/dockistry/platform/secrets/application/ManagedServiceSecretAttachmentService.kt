package io.dockistry.platform.secrets.application

import io.dockistry.platform.secrets.domain.ManagedServiceSecretAttachment
import io.dockistry.platform.secrets.domain.SecretKeyNotFoundException
import io.dockistry.platform.secrets.domain.SecretNotFoundException
import io.dockistry.platform.secrets.domain.SecretValidator
import io.dockistry.platform.secrets.infrastructure.ManagedServiceSecretAttachmentRepository
import io.dockistry.platform.secrets.infrastructure.SecretRepository
import io.dockistry.platform.services.domain.ManagedService
import io.dockistry.platform.services.domain.ManagedServiceSecretReference
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ManagedServiceSecretAttachmentService(
    private val attachmentRepository: ManagedServiceSecretAttachmentRepository,
    private val secretRepository: SecretRepository
) {
    @Transactional
    fun replace(service: ManagedService, references: List<ManagedServiceSecretReference>) {
        attachmentRepository.deleteByService(service)
        attachmentRepository.flush()

        references
            .distinctBy { Triple(it.secretId, it.key, it.secretVersion) }
            .forEach { reference ->
                SecretValidator.validateId(reference.secretId)
                val secret = secretRepository.findById(reference.secretId).orElseThrow {
                    SecretNotFoundException("Secret with provided ID couldn't be found")
                }

                if (reference.key !in secret.encryptedValues.keys) {
                    throw SecretKeyNotFoundException(
                        "Secret key '${reference.key}' couldn't be found"
                    )
                }

                // The attachment is only a dependency index. Configuration remains
                // the source of truth for the environment variable.
                attachmentRepository.saveAndFlush(
                    ManagedServiceSecretAttachment(
                        service = service,
                        secret = secret,
                        secretKey = reference.key,
                        secretVersion = reference.secretVersion
                    )
                )
            }
    }
}
