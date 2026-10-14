package io.dockistry.platform.secrets.application

import io.dockistry.platform.secrets.domain.ServiceSecretWarning
import io.dockistry.platform.secrets.domain.dto.SecretWarningDto
import io.dockistry.platform.secrets.infrastructure.ServiceSecretWarningRepository
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.UUID

@Service
class SecretWarningService(
    private val warningRepository: ServiceSecretWarningRepository,
    private val clock: Clock
) {
    fun getActiveWarnings(serviceId: UUID): List<SecretWarningDto> {
        return getActiveWarnings(listOf(serviceId))[serviceId].orEmpty()
    }

    fun getActiveWarnings(serviceIds: List<UUID>): Map<UUID, List<SecretWarningDto>> {
        if (serviceIds.isEmpty()) return emptyMap()

        return warningRepository.findActiveByServiceIds(serviceIds)
            .groupBy { it.service.id }
            .mapValues { (_, warnings) ->
                warnings.map { warning ->
                    SecretWarningDto(
                        id = warning.id,
                        warningType = warning.warningType,
                        secretId = warning.secretId,
                        secretKey = warning.secretKey,
                        secretVersion = warning.secretVersion,
                        envName = warning.envName,
                        createdAt = warning.createdAt
                    )
                }
            }
    }

    fun getActiveWarningEntities(serviceId: UUID): List<ServiceSecretWarning> {
        return warningRepository.findActiveByServiceIds(listOf(serviceId))
    }

    fun resolveAccepted(warningIds: List<UUID>) {
        if (warningIds.isEmpty()) return

        warningRepository.saveResolvedBulk(
            warningIds = warningIds,
            now = clock.instant()
        )
    }
}
