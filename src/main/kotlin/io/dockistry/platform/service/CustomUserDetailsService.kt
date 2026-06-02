package io.dockistry.platform.service

import io.dockistry.platform.repository.UserRepository
import io.dockistry.platform.security.CustomUserDetails
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.stereotype.Service

@Service
class CustomUserDetailsService(
    private val userRepository: UserRepository
) : UserDetailsService {
    override fun loadUserByUsername(username: String): UserDetails {
        return CustomUserDetails(
            userRepository.findUserByUsername(username) ?: throw UsernameNotFoundException("User $username not found")
        )
    }
}
