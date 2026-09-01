package io.dockistry.platform.secrets.application

import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.projects.application.ProjectAccessGuard
import io.dockistry.platform.secrets.application.ports.SecretEncryptor
import io.dockistry.platform.secrets.domain.ProjectSecretDelegation
import io.dockistry.platform.secrets.domain.Secret
import io.dockistry.platform.secrets.domain.SecretVersion
import io.dockistry.platform.secrets.domain.SecretAlreadyExistsException
import io.dockistry.platform.secrets.domain.dto.SecretDto
import io.dockistry.platform.secrets.infrastructure.ProjectSecretDelegationRepository
import io.dockistry.platform.secrets.infrastructure.SecretRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.util.UUID

@Service
class SecretService(
    private val secretRepository: SecretRepository,
    private val projectSecretDelegationRepository: ProjectSecretDelegationRepository,
    private val secretEncryptor: SecretEncryptor,
    private val projectAccessGuard: ProjectAccessGuard,
    private val secretAccessGuard: SecretAccessGuard,
    private val transactionTemplate: TransactionTemplate,
    private val clock: Clock
) {
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
            createdAt = now,
            updatedAt = now
        )
        secret.addVersion(
            SecretVersion(
                secret = secret,
                encryptedValues = encryptPayload(values),
                createdAt = now
            )
        )

        return try {
            secretRepository.saveAndFlush(secret).id
        } catch (exception: DataIntegrityViolationException) {
            val existing = secretRepository.getByOwnerAndName(ownerPrincipal, secret.name)
            if (existing != null && isSameSecret(existing, secret)) {
                existing.id
            } else {
                throw SecretAlreadyExistsException(
                    "Secret with the same name already exists for this owner"
                )
            }
        }
    }

    fun delegate(
        secretId: String,
        projectId: UUID,
        ownerPrincipal: Principal
    ): UUID {
        return try {
            transactionTemplate.execute {
                secretAccessGuard.requireOwner(secretId, ownerPrincipal)
                val project = projectAccessGuard.requireUpdatable(projectId, ownerPrincipal)
                val secret = secretRepository.findById(secretId).orElseThrow()
                val now = clock.instant()

                projectSecretDelegationRepository.saveAndFlush(
                    ProjectSecretDelegation(
                        secret = secret,
                        project = project,
                        createdAt = now,
                        updatedAt = now
                    )
                ).id
            }
        } catch (exception: DataIntegrityViolationException) {
            transactionTemplate.execute {
                projectSecretDelegationRepository
                    .getByProjectAndSecret(projectId, secretId)
                    ?.id
                    ?: throw exception
            }
        }
    }

    @Transactional(readOnly = true)
    fun list(ownerPrincipal: Principal): List<SecretDto> =
        secretRepository.listByOwnerPrincipal(ownerPrincipal).map(::toDto)

    private fun isSameSecret(existing: Secret, requested: Secret): Boolean =
        existing.name == requested.name &&
            existing.description == requested.description &&
            existing.ownerPrincipal.id == requested.ownerPrincipal.id &&
            existing.encryptedValues == requested.encryptedValues &&
            existing.version == requested.version

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
