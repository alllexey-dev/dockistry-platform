package io.dockistry.platform.iam.application

import io.dockistry.platform.iam.infrastructure.PermissionQueryRepository
import io.dockistry.platform.shared.exception.InternalDataIntegrityException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class PermissionCheckService(
    private val permissionQueryRepository: PermissionQueryRepository,
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

    private fun missingPrincipalException(principalId: UUID) = InternalDataIntegrityException(
        "Principal $principalId was passed for permission check, but it couldn't be found in the DB"
    )
}
