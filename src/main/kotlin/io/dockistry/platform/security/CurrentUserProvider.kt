package io.dockistry.platform.security

import io.dockistry.platform.exception.AuthenticatedUserNotFoundException
import io.dockistry.platform.model.User
import io.dockistry.platform.repository.UserRepository
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
