package io.dockistry.platform.security

import io.dockistry.platform.model.User
import io.dockistry.platform.repository.UserRepository
import io.dockistry.platform.service.JwtTokenProvider
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

@Component
class JwtAuthenticationFilter(
    val tokenProvider: JwtTokenProvider,
    val userRepository: UserRepository
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

            try {
                user = userRepository.findUserById(
                    UUID.fromString(tokenProvider.getUserIdFromToken(token))
                )
            } catch (_: RuntimeException) {
                return responseUnauthorized(response, "couldn't parse user ID")
            }

            if (user != null) {
                val authentication = UsernamePasswordAuthenticationToken(
                    user.username, null, emptySet()
                )
                SecurityContextHolder.getContext().authentication = authentication
            } else {
                return responseUnauthorized(response, "user doesn't exist")
            }

        } else {
            SecurityContextHolder.clearContext()
            return responseUnauthorized(response, "token is invalid or of wrong type")
        }
        filterChain.doFilter(request, response)
    }

    fun responseUnauthorized(
        response: HttpServletResponse,
        message: String
    ) {

        response.status = HttpServletResponse.SC_UNAUTHORIZED
        response.contentType = "application/json"
        response.writer.write("""
            {"message":"$message"}
            """.trimIndent())
    }

    fun getTokenFromRequest(request: HttpServletRequest): String? {
        val token = request.getHeader("Authorization")
        return if (token != null && token.startsWith("Bearer ")) {
            token.substring(7)
        } else null
    }
}
