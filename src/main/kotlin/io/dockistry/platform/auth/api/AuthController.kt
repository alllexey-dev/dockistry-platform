package io.dockistry.platform.auth.api

import io.dockistry.platform.auth.application.RegistrationService
import io.dockistry.platform.auth.application.TokenManagementService
import io.dockistry.platform.auth.domain.dto.AuthResponse
import io.dockistry.platform.auth.domain.dto.LoginRequest
import io.dockistry.platform.auth.domain.dto.RefreshRequest
import io.dockistry.platform.auth.domain.dto.RegisterRequest
import io.dockistry.platform.auth.domain.dto.toAuthResponse
import io.dockistry.platform.iam.application.UserService
import io.dockistry.platform.shared.api.ApiResponse
import jakarta.validation.Valid
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authenticationManager: AuthenticationManager,
    private val tokenManagementService: TokenManagementService,
    private val registrationService: RegistrationService,
    private val userService: UserService
) {

    @PostMapping("/login")
    fun login(@RequestBody @Valid loginRequest: LoginRequest): ApiResponse<AuthResponse> {
        return authenticateAndGenerateToken(loginRequest.username, loginRequest.password)
    }

    @PostMapping("/register")
    fun register(@RequestBody @Valid registerRequest: RegisterRequest): ApiResponse<String> {
        registrationService.registerUser(registerRequest)
        return ApiResponse("An email with verification code will be sent to your email address soon.")
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
        userService.hasVerifiedEmail(userDetails.username)
        val tokenPair = tokenManagementService.issueTokenPair(userDetails.username)

        return ApiResponse(tokenPair.toAuthResponse())
    }
}