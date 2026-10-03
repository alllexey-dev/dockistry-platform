package io.dockistry.platform.iam.application

import io.dockistry.platform.iam.infrastructure.PermissionQueryRepository
import io.dockistry.platform.iam.infrastructure.PrincipalRepository
import io.dockistry.platform.shared.exception.InternalDataIntegrityException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class PermissionCheckService(
    private val permissionQueryRepository: PermissionQueryRepository,
    private val principalRepository: PrincipalRepository
) {
    @Transactional
    fun hasPermission(key: String, principalId: UUID, scopeId: UUID): Boolean {
        return permissionQueryRepository.existsEffectivePermission(
            principalId, key, scopeId
        ).orElseThrow { missingPrincipalException(principalId) }
    }

    @Transactional
    fun hasGlobalPermission(key: String, principalId: UUID): Boolean {
        return permissionQueryRepository.existsEffectivePermission(
            principalId, key
        ).orElseThrow { missingPrincipalException(principalId) }
    }

    @Transactional
    fun hasPermissionExcludingBindings(
        key: String,
        principalId: UUID,
        scopeId: UUID,
        excludedBindingIds: Collection<UUID>
    ): Boolean {
        if (excludedBindingIds.isEmpty()) {
            return hasPermission(key, principalId, scopeId)
        }

        return permissionQueryRepository.existsEffectivePermissionExcludingBindings(
            principalId = principalId,
            permissionKey = key,
            scopeId = scopeId,
            excludedBindingIds = excludedBindingIds
        ).orElseThrow { missingPrincipalException(principalId) }
    }

    @Transactional
    fun findPrincipalIdsWithoutPermissionExcludingBindings(
        key: String,
        principalIds: Collection<UUID>,
        scopeId: UUID,
        excludedBindingIds: Collection<UUID>
    ): List<UUID> {
        if (principalIds.isEmpty()) return emptyList()

        // Missing principals would otherwise be indistinguishable from those with permission.
        val existingIds = principalRepository.findExistingIds(principalIds).toSet()
        principalIds.firstOrNull { it !in existingIds }?.let {
            throw missingPrincipalException(it)
        }

        return permissionQueryRepository
            .findPrincipalIdsWithoutEffectivePermissionExcludingBindings(
                principalIds = principalIds,
                permissionKey = key,
                scopeId = scopeId,
                excludedBindingIds = excludedBindingIds
            )
    }

    private fun missingPrincipalException(principalId: UUID) = InternalDataIntegrityException(
        "Principal $principalId was passed for permission check, but it couldn't be found in the DB"
    )
}
