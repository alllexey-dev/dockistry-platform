package io.dockistry.platform.secrets.application

import io.dockistry.platform.secrets.domain.InvalidSecretReferenceException
import io.dockistry.platform.secrets.domain.ManagedServiceSecretReferenceEntity
import io.dockistry.platform.secrets.domain.SecretPayload
import io.dockistry.platform.secrets.domain.SecretVersion
import io.dockistry.platform.secrets.domain.toDto
import io.dockistry.platform.secrets.infrastructure.ManagedServiceSecretReferenceRepository
import io.dockistry.platform.secrets.infrastructure.ProjectSecretDelegationRepository
import io.dockistry.platform.secrets.infrastructure.SecretVersionRepository
import io.dockistry.platform.services.domain.ManagedService
import io.dockistry.platform.services.domain.ManagedServiceConfiguration
import io.dockistry.platform.services.domain.ManagedServiceSecretReference
import io.dockistry.platform.shared.exception.InternalDataIntegrityException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class ManagedServiceSecretReferenceService(
    private val referenceRepository: ManagedServiceSecretReferenceRepository,
    private val versionRepository: SecretVersionRepository,
    private val projectSecretDelegationRepository: ProjectSecretDelegationRepository
) {
    @Transactional
    fun replace(service: ManagedService, configuration: ManagedServiceConfiguration) {
        val references = configuration.secretReferences
        lockSecretsAndCheckDelegation(service, references)

        referenceRepository.deleteByService(service)
        referenceRepository.flush()

        val versionByReference = validateAndFetchVersions(references)

        versionByReference.forEach { (reference, version) ->
            referenceRepository.saveAndFlush(
                ManagedServiceSecretReferenceEntity(
                    service = service,
                    secret = version.secret,
                    secretKey = reference.key,
                    secretVersion = reference.secretVersion,
                    envName = reference.envName
                )
            )
        }
    }

    fun validateAndFetchPayload(
        references: List<ManagedServiceSecretReference>
    ): List<SecretPayload> {
        return validateAndFetchVersions(references).map { (reference, version) ->
            SecretPayload(
                envName = reference.envName,
                encryptedPayload = version.encryptedValues[reference.key]
                    ?: throw InternalDataIntegrityException(
                        "Key ${reference.key} was found " +
                                "in ${reference.secretId} secret (${version.id} version) earlier, " +
                                "but now it's missing"
                    )
            )
        }
    }

    /*
     * Reasons for creating such method:
     * - ManagedServiceSecretReferenceService.replace needs Secret entity (included in SecretVersion entity)
     * - ManagedServiceDeploymentLifecycleService.begin needs payload (can be extracted from SecretVersion)
     * - both need to make sure all references are valid.
     *
     * Therefore, mapping each reference to a SecretVersion is the best trade-off here.
     */
    private fun validateAndFetchVersions(
        references: List<ManagedServiceSecretReference>
    ): Map<ManagedServiceSecretReference, SecretVersion> {
        val pinnedVersionIds = references.mapNotNull { it.secretVersion }
        val latestSecretIds = references.filter { it.secretVersion == null }.map { it.secretId }

        val pinnedVersionById =
            versionRepository.findByIdIn(pinnedVersionIds).associateBy { it.id }
        val latestVersionBySecretId =
            versionRepository.findLatestBySecretIdIn(latestSecretIds)
                .associateBy { it.secret.id }

        return references.associateWith { reference ->
            validateAndGetSecretVersion(
                reference = reference,
                latestVersionBySecretId = latestVersionBySecretId,
                pinnedVersionById = pinnedVersionById
            )
        }
    }

    fun lockSecretsAndCheckDelegation(
        service: ManagedService,
        references: List<ManagedServiceSecretReference>
    ) {
        val lockedSecrets = projectSecretDelegationRepository.findByProjectAndSecretForNoKeyUpdateBatch(
            projectId = service.project.id,
            secretIds = references.map { it.secretId }
        ).map { it.secret.id }.toSet()
        val missingSecrets = references.map { it.secretId }.filter { it !in lockedSecrets }

        if (!missingSecrets.isEmpty()) {
            throw InvalidSecretReferenceException(
                "Following Secrets couldn't be found or are not delegated to this project: " +
                        missingSecrets.joinToString(separator = ", ")
            )
        }
    }

    fun getReferences(serviceId: UUID): List<ManagedServiceSecretReference> {
        return referenceRepository.findByServiceId(serviceId).map { it.toDto() }
    }

    fun getReferences(serviceIds: Collection<UUID>): Map<UUID, List<ManagedServiceSecretReference>> {
        return referenceRepository.findByServiceIdIn(serviceIds).groupBy { it.service.id }.mapValues {
            it.value.map { entity -> entity.toDto() }
        }
    }

    private fun validateAndGetSecretVersion(
        reference: ManagedServiceSecretReference,
        latestVersionBySecretId: Map<String, SecretVersion>,
        pinnedVersionById: Map<String, SecretVersion>,
    ): SecretVersion {
        val versionEntity = if (reference.secretVersion == null) {
            latestVersionBySecretId[reference.secretId]
                ?: throw InvalidSecretReferenceException(
                    "Secret ${reference.secretId} couldn't be resolved"
                )
        } else {
            pinnedVersionById[reference.secretVersion]?.takeIf { it.secret.id == reference.secretId }
                ?: throw InvalidSecretReferenceException(
                    "Secret ${reference.secretId} at version ${reference.secretVersion} " +
                        "couldn't be resolved"
                )
        }

        if (versionEntity.encryptedValues[reference.key] == null) {
            throw InvalidSecretReferenceException(
                "Key '${reference.key}' couldn't be found in Secret ${reference.secretId}" +
                    (reference.secretVersion?.let { " at version $it" } ?: "")
            )
        }

        return versionEntity
    }
}
