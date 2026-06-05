package io.dockistry.platform.iam.application

import io.dockistry.platform.iam.domain.AuthenticatedUserNotFoundException
import io.dockistry.platform.iam.domain.User
import io.dockistry.platform.iam.infrastructure.UserRepository
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class CurrentUserProvider(
    private val userRepository: UserRepository
) {
    fun getUser(): User {
        val auth = SecurityContextHolder.getContext().authentication
            ?: throw AuthenticationCredentialsNotFoundException("Authentication required")

        if (!auth.isAuthenticated) {
            throw AuthenticationCredentialsNotFoundException("Authentication required")
        }

        val username = when (val principal = auth.principal) {
            is String -> principal
            else -> throw AuthenticationCredentialsNotFoundException("Unsupported principal type")
        }

        return userRepository.findUserByUsername(username) ?: throw AuthenticatedUserNotFoundException()
    }

    fun getId(): UUID {
        return getUser().id
    }
}
