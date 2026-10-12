package io.dockistry.platform.secrets.application

import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.projects.application.ProjectAccessGuard
import io.dockistry.platform.secrets.application.ports.SecretEncryptor
import io.dockistry.platform.secrets.domain.ManagedServiceSecretReferenceEntity
import io.dockistry.platform.secrets.domain.ProjectSecretDelegation
import io.dockistry.platform.secrets.domain.Secret
import io.dockistry.platform.secrets.domain.SecretVersion
import io.dockistry.platform.secrets.domain.InvalidSecretDescriptionException
import io.dockistry.platform.secrets.domain.InvalidSecretNameException
import io.dockistry.platform.secrets.domain.SecretAlreadyExistsException
import io.dockistry.platform.secrets.domain.SecretMetadataVersionConflictException
import io.dockistry.platform.secrets.domain.SecretValidator
import io.dockistry.platform.secrets.domain.SecretValueContext
import io.dockistry.platform.secrets.domain.SecretVersionIdGenerator
import io.dockistry.platform.secrets.domain.SecretVersionNotFoundException
import io.dockistry.platform.secrets.domain.SecretWarningType
import io.dockistry.platform.secrets.domain.ServiceSecretWarning
import io.dockistry.platform.secrets.domain.dto.SecretDelegationDto
import io.dockistry.platform.secrets.domain.dto.SecretDto
import io.dockistry.platform.secrets.domain.dto.SecretUsageDto
import io.dockistry.platform.secrets.domain.dto.SecretVersionDto
import io.dockistry.platform.secrets.infrastructure.ManagedServiceSecretReferenceRepository
import io.dockistry.platform.secrets.infrastructure.ProjectSecretDelegationRepository
import io.dockistry.platform.secrets.infrastructure.SecretRepository
import io.dockistry.platform.secrets.infrastructure.SecretVersionRepository
import io.dockistry.platform.secrets.infrastructure.ServiceSecretWarningRepository
import io.dockistry.platform.shared.domain.ResourceDescriptionNormalizer
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
    private val secretVersionRepository: SecretVersionRepository,
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
        val validValues = SecretValidator.validateValues(values)

        val now = clock.instant()
        val secret = Secret(
            name = name,
            description = description,
            ownerPrincipal = ownerPrincipal,
            createdAt = now,
            updatedAt = now
        )
        val versionId = SecretVersionIdGenerator.generate()
        val version = SecretVersion(
            secret = secret,
            encryptedValues = encryptValues(secret.id, versionId, validValues),
            createdAt = now,
            id = versionId
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
                throw secretAlreadyExists()
            }
        }
    }

    /**
     * Replaces name and description. A stale [expectedVersion] is accepted only when the
     * Secret already has the requested metadata.
     */
    fun update(
        secretId: String,
        name: String,
        description: String?,
        expectedVersion: Long,
        principal: Principal
    ): SecretDto {
        val normalizedName = ResourceNameNormalizer.normalizeAndValidate(
            name,
            ::InvalidSecretNameException
        )
        val normalizedDescription = ResourceDescriptionNormalizer.normalizeAndValidate(
            description,
            ::InvalidSecretDescriptionException
        )

        return try {
            transactionTemplate.execute {
                updateMetadata(
                    secretId = secretId,
                    name = normalizedName,
                    description = normalizedDescription,
                    expectedVersion = expectedVersion,
                    principal = principal
                )
            }
        } catch (exception: DataIntegrityViolationException) {
            // A concurrent rename can win the unique name after the pre-check. The failed
            // transaction is already rolled back, so the recheck runs in a fresh one.
            val nameTaken = transactionTemplate.execute {
                isNameTakenByAnotherSecret(principal, normalizedName, secretId)
            }
            if (nameTaken) throw secretAlreadyExists()

            throw exception
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

    /**
     * Owner revoke is authorized by ownership alone, so revoking `project.secrets.use` or any other
     * project permission can't stop the owner from withdrawing their Secret.
     */
    @Transactional
    fun revoke(secretId: String, projectId: UUID, principal: Principal) {
        secretAccessGuard.requireOwner(secretId, principal)
        revokeDelegation(secretId, projectId)
    }

    /** Removes anyone's delegation with the same consequences as [revoke]; the Secret stays with its owner. */
    @Transactional
    fun forceRevoke(secretId: String, projectId: UUID, principal: Principal) {
        projectAccessGuard.requireSecretsForceRevocable(projectId, principal)
        revokeDelegation(secretId, projectId)
    }

    @Transactional
    fun rotate(
        secretId: String,
        newPayload: Map<String, String>,
        principal: Principal
    ): SecretVersionDto {
        val validValues = SecretValidator.validateValues(newPayload)
        // Serializes with version and logical Secret deletion that decide what is latest.
        val secret = secretAccessGuard.requireOwnerForUpdate(
            secretId = secretId,
            principal = principal
        )

        val projectIdsWithDelegation =
            projectSecretDelegationRepository.findBySecretId(secretId)
                .map { it.project.id }.toSet()

        val secretReferences = referenceRepository.findLatestBySecretId(secretId)
            .filter { it.service.project.id in projectIdsWithDelegation }

        createWarnings(secretReferences, SecretWarningType.OUTDATED)

        val newVersionId = SecretVersionIdGenerator.generate()
        val newVersion = SecretVersion(
            secret = secret,
            encryptedValues = encryptValues(secretId, newVersionId, validValues),
            createdAt = clock.instant(),
            id = newVersionId
        )
        secret.addVersion(newVersion)

        return newVersion.toVersionDto()
    }

    /**
     * Physically deletes one version. Pinned references to it are removed by FK cascade, so
     * they get `DELETED` warnings first; deleting the current latest also warns every latest
     * reference, which silently starts resolving to the previous version or to nothing.
     */
    @Transactional
    fun deleteVersion(secretId: String, versionId: String, principal: Principal) {
        lockForDestructiveChange(secretId, principal)

        val version = secretVersionRepository.findById(versionId)
            .filter { it.secret.id == secretId }
            .orElseThrow {
                SecretVersionNotFoundException(
                    "Version $versionId of Secret $secretId couldn't be found"
                )
            }
        val isLatest = secretVersionRepository.findLatestBySecretId(secretId).id == version.id

        val pinnedReferences =
            referenceRepository.findPinnedBySecretIdAndVersion(secretId, version.id)
        val latestReferences =
            if (isLatest) referenceRepository.findLatestBySecretId(secretId) else emptyList()
        createWarnings(pinnedReferences + latestReferences, SecretWarningType.DELETED)

        secretVersionRepository.deleteBySecretIdAndId(secretId, version.id)
    }

    /**
     * Deletes the logical Secret with all versions and delegations regardless of
     * configuration and runtime dependencies. Every canonical reference is removed by FK
     * cascade, so each one gets a `DELETED` warning first.
     */
    @Transactional
    fun delete(secretId: String, principal: Principal) {
        lockForDestructiveChange(secretId, principal)

        createWarnings(referenceRepository.findBySecretId(secretId), SecretWarningType.DELETED)

        projectSecretDelegationRepository.deleteBySecretId(secretId)
        secretRepository.deleteLogicalSecret(secretId)
    }

    @Transactional(readOnly = true)
    fun list(ownerPrincipal: Principal): List<SecretDto> =
        secretRepository.listByOwnerPrincipal(ownerPrincipal).map(::toDto)

    @Transactional(readOnly = true)
    fun get(secretId: String, principal: Principal): SecretDto =
        toDto(secretAccessGuard.requireOwner(secretId, principal))

    @Transactional(readOnly = true)
    fun listVersions(secretId: String, principal: Principal): List<SecretVersionDto> =
        secretAccessGuard.requireOwner(secretId, principal).versions
            .toVersionDtos()

    @Transactional(readOnly = true)
    fun listDelegations(secretId: String, principal: Principal): List<SecretDelegationDto> {
        secretAccessGuard.requireOwner(secretId, principal)
        return projectSecretDelegationRepository.findBySecretIdWithProject(secretId).map {
            SecretDelegationDto(
                projectId = it.project.id,
                projectName = it.project.name,
                createdAt = it.createdAt
            )
        }
    }

    /**
     * Lists every current configuration dependency on the Secret, including references left in
     * projects whose delegation was revoked: they still block the next deploy there.
     */
    @Transactional(readOnly = true)
    fun listUsage(secretId: String, principal: Principal): List<SecretUsageDto> {
        secretAccessGuard.requireOwner(secretId, principal)
        return referenceRepository.findUsageBySecretIds(listOf(secretId)).map { it.toUsageDto() }
    }

    /*
     * The FK cascade must not remove a reference that got no DELETED warning, so the set of
     * references read afterwards has to be final until commit:
     * - owner principal lock blocks a concurrent delegation, which would open a new project
     *   for references;
     * - Secret row lock serializes with rotation and other deletions of this Secret;
     * - delegation row locks wait for in-flight configuration replacements, which hold them
     *   while inserting references, and make later ones fail on the missing version/delegation.
     * NO KEY UPDATE keeps the Secret row compatible with FK KEY SHARE of reference inserts:
     * otherwise a replacement holding a delegation lock would deadlock with this flow.
     */
    private fun lockForDestructiveChange(secretId: String, principal: Principal) {
        secretDelegationPermissionGuard.lockPrincipal(principal.id)
        secretAccessGuard.requireOwnerForUpdate(secretId, principal)
        projectSecretDelegationRepository.findBySecretIdForNoKeyUpdate(secretId)
    }

    /*
     * The delegation row lock is the mutex shared with configuration replacement and deploy
     * begin: they either commit references first and get a warning here, or see the delegation
     * gone afterwards. A delegation that is already gone makes the revoke a no-op.
     */
    private fun revokeDelegation(secretId: String, projectId: UUID) {
        projectSecretDelegationRepository.findByProjectAndSecretForNoKeyUpdateBatch(
            projectId = projectId,
            secretIds = listOf(secretId)
        ).singleOrNull() ?: return

        createWarnings(
            referenceRepository.findByProjectAndSecret(projectId = projectId, secretId = secretId),
            SecretWarningType.DELEGATION_REVOKED
        )

        projectSecretDelegationRepository.deleteByProjectAndSecret(
            projectId = projectId,
            secretId = secretId
        )
    }

    private fun updateMetadata(
        secretId: String,
        name: String,
        description: String?,
        expectedVersion: Long,
        principal: Principal
    ): SecretDto {
        val secret = secretAccessGuard.requireOwnerForUpdate(secretId, principal)

        if (secret.name == name && secret.description == description) {
            return toDto(secret)
        }
        if (secret.version != expectedVersion) {
            throw SecretMetadataVersionConflictException(
                "Secret $secretId has been modified concurrently"
            )
        }
        if (isNameTakenByAnotherSecret(principal, name, secretId)) {
            throw secretAlreadyExists()
        }

        secret.updateMetadata(name, description, clock.instant())
        secretRepository.flush()

        return toDto(secret)
    }

    private fun isNameTakenByAnotherSecret(
        principal: Principal,
        name: String,
        secretId: String
    ): Boolean = secretRepository.existsByOwnerPrincipalIdAndNameAndIdNot(
        ownerPrincipalId = principal.id,
        name = name,
        id = secretId
    )

    private fun secretAlreadyExists() =
        SecretAlreadyExistsException("Secret with the same name already exists for this owner")

    private fun createWarnings(
        references: List<ManagedServiceSecretReferenceEntity>,
        type: SecretWarningType
    ) {
        val now = clock.instant()
        references.forEach { reference ->
            serviceSecretWarningRepository.saveOrRefreshActive(
                ServiceSecretWarning(
                    service = reference.service,
                    secretId = reference.secret.id,
                    secretKey = reference.secretKey,
                    secretVersion = reference.secretVersion,
                    warningType = type,
                    envName = reference.envName,
                    createdAt = now
                )
            )
        }
    }

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
        val version = existing.versions.singleOrNull() ?: return false
        return version.encryptedValues.keys == values.keys &&
            version.encryptedValues.all { (key, encrypted) ->
                val context = SecretValueContext(existing.id, version.id, key)
                secretEncryptor.decrypt(encrypted, context) == values[key]
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

    private fun encryptValues(
        secretId: String,
        versionId: String,
        values: Map<String, String>
    ): Map<String, String> =
        values.mapValues { (key, value) ->
            secretEncryptor.encrypt(value, SecretValueContext(secretId, versionId, key))
        }
}
