package io.dockistry.platform.secrets.application

import io.dockistry.platform.secrets.infrastructure.SecretDelegationLock
import org.springframework.stereotype.Service
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

@Service
class SecretDelegationPermissionGuard(private val secretDelegationLock: SecretDelegationLock) {
    fun lockPrincipal(principalId: UUID) {
        secretDelegationLock.lock(advisoryLockKey(principalId))
    }

    private fun advisoryLockKey(id: UUID): Long {
        val hashArray = MessageDigest.getInstance("SHA-256")
            .digest(("secret-delegation-permission: $id").toByteArray())

        return ByteBuffer.wrap(hashArray).long
    }
}
