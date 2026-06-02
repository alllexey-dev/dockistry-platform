package io.dockistry.platform.controller

import io.dockistry.platform.dto.ApiResponse
import io.dockistry.platform.model.Principal
import io.dockistry.platform.model.PrincipalType
import io.dockistry.platform.model.User
import io.dockistry.platform.service.TokenManagementService
import io.dockistry.platform.service.UserService
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
    private val tokenManagementService: TokenManagementService
) {

    @PostMapping("/login")
    fun login(@RequestBody loginRequest: LoginRequest): ApiResponse<AuthResponse> {
        return authenticateAndGenerateToken(loginRequest.username, loginRequest.password)
    }

    @PostMapping("/register")
    fun register(@RequestBody registerRequest: RegisterRequest): ApiResponse<AuthResponse> {
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
    fun refreshToken(@RequestBody refreshRequest: RefreshRequest): ApiResponse<AuthResponse> {
        val tokenPair = tokenManagementService.refreshToken(refreshRequest.refreshToken)
        return ApiResponse(
            AuthResponse(
                refreshToken = tokenPair.refreshToken,
                accessToken = tokenPair.accessToken
            )
        )
    }

    fun authenticateAndGenerateToken(username: String, password: String): ApiResponse<AuthResponse> {
        val authentication = authenticationManager.authenticate(
            UsernamePasswordAuthenticationToken(username, password)
        )

        val userDetails = authentication.principal as UserDetails
        val tokenPair = tokenManagementService.issueTokenPair(userDetails.username)

        return ApiResponse(
            AuthResponse(
                refreshToken = tokenPair.refreshToken,
                accessToken = tokenPair.accessToken
            )
        )
    }
}

data class LoginRequest(
    val username: String,
    val password: String
)

data class RegisterRequest(
    val email: String,
    val username: String,
    val password: String
)

data class RefreshRequest(
    val refreshToken: String
)

data class AuthResponse(
    val accessToken: String,
    val refreshToken: String
)
