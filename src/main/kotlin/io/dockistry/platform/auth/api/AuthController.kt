package io.dockistry.platform.auth.api

import io.dockistry.platform.auth.application.TokenManagementService
import io.dockistry.platform.auth.domain.dto.AuthResponse
import io.dockistry.platform.auth.domain.dto.LoginRequest
import io.dockistry.platform.auth.domain.dto.RefreshRequest
import io.dockistry.platform.auth.domain.dto.RegisterRequest
import io.dockistry.platform.auth.domain.dto.TokenPair
import io.dockistry.platform.iam.application.CurrentUserProvider
import io.dockistry.platform.iam.application.UserService
import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.iam.domain.PrincipalType
import io.dockistry.platform.iam.domain.User
import io.dockistry.platform.shared.api.ApiResponse
import jakarta.validation.Valid
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val userService: UserService,
    private val authenticationManager: AuthenticationManager,
    private val passwordEncoder: PasswordEncoder,
    private val tokenManagementService: TokenManagementService,
    private val currentUserProvider: CurrentUserProvider
) {

    @PostMapping("/login")
    fun login(@RequestBody @Valid loginRequest: LoginRequest): ApiResponse<AuthResponse> {
        return authenticateAndGenerateToken(loginRequest.username, loginRequest.password)
    }

    @PostMapping("/register")
    fun register(@RequestBody @Valid registerRequest: RegisterRequest): ApiResponse<AuthResponse> {
        userService.insertUser(
            User(
                email = registerRequest.email,
                username = registerRequest.username,
                passwordHash = requireNotNull(
                    passwordEncoder.encode(registerRequest.password)
                ) { "PasswordEncoder returned null for non-null password" }
            ).apply {
                principal = Principal(PrincipalType.USER)
            }
        )
        return authenticateAndGenerateToken(registerRequest.username, registerRequest.password)
    }

    @PostMapping("/refresh")
    fun refreshToken(@RequestBody @Valid refreshRequest: RefreshRequest): ApiResponse<AuthResponse> {
        val tokenPair = tokenManagementService.refreshToken(refreshRequest.refreshToken)
        return ApiResponse(tokenPair.toAuthResponse())
    }

    fun authenticateAndGenerateToken(username: String, password: String): ApiResponse<AuthResponse> {
        val authentication = authenticationManager.authenticate(
            UsernamePasswordAuthenticationToken(username, password)
        )

        val userDetails = authentication.principal as UserDetails
        val tokenPair = tokenManagementService.issueTokenPair(userDetails.username)

        return ApiResponse(tokenPair.toAuthResponse())
    }

    private fun TokenPair.toAuthResponse(): AuthResponse {
        return AuthResponse(
            refreshToken = refreshToken,
            refreshExpiresAt = refreshExpiresAt,
            accessToken = accessToken,
            accessExpiresAt = accessExpiresAt
        )
    }
}