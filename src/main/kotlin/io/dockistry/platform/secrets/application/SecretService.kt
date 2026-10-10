package io.dockistry.platform.secrets.application

import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.projects.application.ProjectAccessGuard
import io.dockistry.platform.secrets.application.ports.SecretEncryptor
import io.dockistry.platform.secrets.domain.ProjectSecretDelegation
import io.dockistry.platform.secrets.domain.Secret
import io.dockistry.platform.secrets.domain.SecretVersion
import io.dockistry.platform.secrets.domain.SecretAlreadyExistsException
import io.dockistry.platform.secrets.domain.SecretValidator
import io.dockistry.platform.secrets.domain.SecretWarningType
import io.dockistry.platform.secrets.domain.ServiceSecretWarning
import io.dockistry.platform.secrets.domain.dto.SecretDto
import io.dockistry.platform.secrets.infrastructure.ManagedServiceSecretReferenceRepository
import io.dockistry.platform.secrets.infrastructure.ProjectSecretDelegationRepository
import io.dockistry.platform.secrets.infrastructure.SecretRepository
import io.dockistry.platform.secrets.infrastructure.ServiceSecretWarningRepository
import io.dockistry.platform.shared.domain.ResourceNameNormalizer
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
    private val clock: Clock,
    private val referenceRepository: ManagedServiceSecretReferenceRepository,
    private val serviceSecretWarningRepository: ServiceSecretWarningRepository,
    private val secretDelegationPermissionGuard: SecretDelegationPermissionGuard
) {
    fun create(
        name: String,
        description: String?,
        values: Map<String, String>,
        ownerPrincipal: Principal
    ): String {
        val payload = encryptPayload(values)

        val now = clock.instant()
        val secret = Secret(
            name = name,
            description = description,
            ownerPrincipal = ownerPrincipal,
            createdAt = now,
            updatedAt = now
        )
        val version = SecretVersion(
            secret = secret,
            encryptedValues = payload,
            createdAt = now
        )
        secret.addVersion(version)

        return try {
            secretRepository.saveAndFlush(secret).id
        } catch (exception: DataIntegrityViolationException) {
            val existing = secretRepository.findByOwnerAndName(ownerPrincipal, secret.name)
                ?: throw exception
            if (isSameLogicalSecret(existing, secret) && hasSingleVersionWith(existing, values)) {
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
                secretDelegationPermissionGuard.lockPrincipal(ownerPrincipal.id)

                secretAccessGuard.requireOwner(secretId, ownerPrincipal)
                val project = projectAccessGuard.requireSecretsDelegable(projectId, ownerPrincipal)
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

    @Transactional
    fun revoke(secretId: String, projectId: UUID, principal: Principal) {
        secretAccessGuard.requireOwner(secretId, principal)

        projectSecretDelegationRepository.findByProjectAndSecretForNoKeyUpdateBatch(
            projectId = projectId,
            secretIds = listOf(secretId)
        ).singleOrNull() ?: return

        val now = clock.instant()

        referenceRepository.findByProjectAndSecret(
            projectId = projectId,
            secretId = secretId
        ).forEach { referenceEntity ->
            serviceSecretWarningRepository.saveIdempotent(
                ServiceSecretWarning(
                    service = referenceEntity.service,
                    secretId = secretId,
                    secretKey = referenceEntity.secretKey,
                    secretVersion = referenceEntity.secretVersion,
                    warningType = SecretWarningType.DELEGATION_REVOKED,
                    envName = referenceEntity.envName,
                    createdAt = now,
                    resolvedAt = null
                )
            )
        }

        projectSecretDelegationRepository.deleteByProjectAndSecret(
            projectId = projectId,
            secretId = secretId
        )
    }

    @Transactional
    fun rotate(
        secretId: String,
        newPayload: Map<String, String>,
        principal: Principal
    ): String {
        val secret = secretAccessGuard.requireOwner(
            secretId = secretId,
            principal = principal
        )
        val payload = encryptPayload(newPayload)

        val projectIdsWithDelegation =
            projectSecretDelegationRepository.findBySecretId(secretId)
                .map { it.project.id }.toSet()

        val secretReferences = referenceRepository.findLatestBySecretId(secretId)
            .filter { it.service.project.id in projectIdsWithDelegation }

        secretReferences.forEach { referenceEntity ->
            serviceSecretWarningRepository.saveIdempotent(
                ServiceSecretWarning(
                    service = referenceEntity.service,
                    secretId = secretId,
                    secretKey = referenceEntity.secretKey,
                    secretVersion = referenceEntity.secretVersion,
                    warningType = SecretWarningType.OUTDATED,
                    envName = referenceEntity.envName,
                    createdAt = clock.instant()
                )
            )
        }

        val newVersion = SecretVersion(
            secret = secret,
            encryptedValues = payload,
            createdAt = clock.instant()
        )
        secret.addVersion(newVersion)

        return newVersion.id
    }

    @Transactional(readOnly = true)
    fun list(ownerPrincipal: Principal): List<SecretDto> =
        secretRepository.listByOwnerPrincipal(ownerPrincipal).map(::toDto)

    private fun isSameLogicalSecret(existing: Secret, requested: Secret): Boolean =
        existing.name == ResourceNameNormalizer.normalize(requested.name) &&
            existing.description == requested.description &&
            existing.ownerPrincipal.id == requested.ownerPrincipal.id

    /*
     * Ciphertext can't be compared: real encryption uses a random nonce, so the same
     * plaintext encrypts differently on every call. Retried creates are rare, so
     * decrypting the existing version in memory is cheap enough.
     */
    private fun hasSingleVersionWith(existing: Secret, values: Map<String, String>): Boolean {
        val encryptedValues = existing.versions.singleOrNull()?.encryptedValues ?: return false
        return encryptedValues.keys == values.keys &&
            encryptedValues.all { (key, encrypted) ->
                secretEncryptor.decrypt(encrypted) == values[key]
            }
    }

    private fun toDto(secret: Secret): SecretDto = SecretDto(
        id = secret.id,
        name = secret.name,
        description = secret.description,
        version = secret.version,
        createdAt = secret.createdAt,
        updatedAt = secret.updatedAt,
        versionsIds = secret.versions.map { it.id }
    )

    private fun encryptPayload(values: Map<String, String>): Map<String, String> =
        SecretValidator.validateValues(values)
            .mapValues { (_, value) -> secretEncryptor.encrypt(value) }
}
