package io.dockistry.platform.security

import io.dockistry.platform.model.User
import io.dockistry.platform.repository.UserRepository
import io.dockistry.platform.service.JwtTokenProvider
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

@Component
class JwtAuthenticationFilter(
    private val tokenProvider: JwtTokenProvider,
    private val userRepository: UserRepository,
    private val authenticationEntryPoint: AuthenticationEntryPoint
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val token = getTokenFromRequest(request)

        if (token == null) {
            filterChain.doFilter(request, response)
            return
        }

        if (tokenProvider.validateToken(token) && tokenProvider.isAccessToken(token)) {
            var user: User?

            val userId = try {
                UUID.fromString(tokenProvider.getUserIdFromToken(token))
            } catch (_: RuntimeException) {
                return responseUnauthorized(request, response, "Couldn't parse user ID")
            }
            user = userRepository.findUserById(userId)

            if (user != null) {
                val authentication = UsernamePasswordAuthenticationToken(
                    user.username, null, emptySet()
                )
                SecurityContextHolder.getContext().authentication = authentication
            } else {
                return responseUnauthorized(request, response, "User doesn't exist")
            }

        } else {
            SecurityContextHolder.clearContext()
            return responseUnauthorized(request, response, "Token is invalid or of wrong type")
        }
        filterChain.doFilter(request, response)
    }

    private fun responseUnauthorized(
        request: HttpServletRequest,
        response: HttpServletResponse,
        message: String
    ) {
        authenticationEntryPoint.commence(request, response, BadCredentialsException(message))
    }

    fun getTokenFromRequest(request: HttpServletRequest): String? {
        val token = request.getHeader("Authorization")
        return if (token != null && token.startsWith("Bearer ")) {
            token.substring(7)
        } else null
    }
}
