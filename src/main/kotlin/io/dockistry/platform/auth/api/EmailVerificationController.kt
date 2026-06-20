package io.dockistry.platform.auth.api

import io.dockistry.platform.auth.application.EmailVerificationFlowService
import io.dockistry.platform.auth.application.TokenManagementService
import io.dockistry.platform.auth.domain.EmailVerificationChallengeReason
import io.dockistry.platform.auth.domain.dto.AuthResponse
import io.dockistry.platform.auth.domain.dto.ResendEmailVerificationRequest
import io.dockistry.platform.auth.domain.dto.VerifyEmailByNumberCodeRequest
import io.dockistry.platform.auth.domain.dto.toAuthResponse
import io.dockistry.platform.shared.api.ApiResponse
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth/email-verification")
class EmailVerificationController(
    private val emailVerificationFlowService: EmailVerificationFlowService,
    private val tokenManagementService: TokenManagementService,
) {
    @PostMapping("/code")
    fun verifyNumberCode(@RequestBody @Valid payload: VerifyEmailByNumberCodeRequest): ApiResponse<AuthResponse> {
        val userId = emailVerificationFlowService.verifyNumberCode(
            email = payload.email,
            numberCode = payload.numberCode
        )
        val tokenPair = tokenManagementService.issueTokenPair(userId)
        return ApiResponse(tokenPair.toAuthResponse())
    }

    @PostMapping("/link/{code}")
    fun verifyLinkCode(@PathVariable code: String): ApiResponse<AuthResponse> {
        val userId = emailVerificationFlowService.verifyLinkCode(code)
        val tokenPair = tokenManagementService.issueTokenPair(userId)
        return ApiResponse(tokenPair.toAuthResponse())
    }

    @PostMapping("/resend")
    fun resendCode(@RequestBody @Valid payload: ResendEmailVerificationRequest): ApiResponse<String> {
        emailVerificationFlowService.requestVerificationCodes(
            payload.email,
            EmailVerificationChallengeReason.RESEND
        )
        return ApiResponse("If you're eligible for resend, verification codes have been sent.")
    }
}
