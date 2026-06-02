package io.dockistry.platform.exception

import io.dockistry.platform.dto.ApiResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(ServiceException::class)
    fun handleServiceException(exception: ServiceException): ResponseEntity<ApiResponse<Nothing>> {
        val status = when (exception) {
            is ConflictException -> HttpStatus.CONFLICT
            is ForbiddenException -> HttpStatus.FORBIDDEN
            is UnauthorizedException -> HttpStatus.UNAUTHORIZED
            is NotFoundException -> HttpStatus.NOT_FOUND
            is BadRequestException -> HttpStatus.BAD_REQUEST
        }

        return ResponseEntity
            .status(status)
            .body(
                ApiResponse(
                    errorMessage = exception.message ?: "Service error",
                    errorCode = exception.code
                )
            )
    }

    /* This handler is added for catching exceptions from AuthenticationManager
    when authorizing through Spring Security's built-in username/password auth manager.
     */
    @ExceptionHandler(BadCredentialsException::class)
    fun handleInvalidUsernameOrPassword(exception: BadCredentialsException): ResponseEntity<ApiResponse<Nothing>> {
        return ResponseEntity
            .status(HttpStatus.UNAUTHORIZED)
            .body(
                ApiResponse(
                    errorMessage = exception.message ?: "Invalid username/password",
                    errorCode = "invalid_username_or_password"
                )
            )
    }
}
