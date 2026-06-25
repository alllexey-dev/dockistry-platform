package io.dockistry.platform.setup.api

import io.dockistry.platform.auth.application.TokenManagementService
import io.dockistry.platform.auth.domain.dto.AuthResponse
import io.dockistry.platform.auth.domain.dto.RegisterRequest
import io.dockistry.platform.auth.domain.dto.toAuthResponse
import io.dockistry.platform.setup.application.InitialAdminService
import io.dockistry.platform.shared.api.ApiResponse
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/setup")
class SetupController(
    private val initialAdminService: InitialAdminService,
    private val tokenManagementService: TokenManagementService
) {

    @PostMapping("/initial-admin")
    fun initialAdminSetup(
        @RequestBody registerRequest: RegisterRequest
    ): ApiResponse<AuthResponse> {
        val user = initialAdminService.initializeAdmin(
            username = registerRequest.username,
            email = registerRequest.email,
            password = registerRequest.password
        )

        return ApiResponse(
            tokenManagementService.issueTokenPair(user.id).toAuthResponse()
        )
    }

}