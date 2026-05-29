package io.dockistry.platform.exception

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(UserAlreadyExistsException::class)
    fun handleUserAlreadyExists(exception: UserAlreadyExistsException) : ResponseEntity<ApiError> {
        return ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(ApiError(exception.message ?: "User already exists"))
    }

    @ExceptionHandler(BadCredentialsException::class)
    fun handleUserAlreadyExists(exception: BadCredentialsException) : ResponseEntity<ApiError> {
        return ResponseEntity
            .status(HttpStatus.UNAUTHORIZED)
            .body(ApiError(exception.message ?: "Invalid username/password"))
    }

    @ExceptionHandler(TokenException::class)
    fun handleInvalidToken(exception: TokenException): ResponseEntity<ApiError> {
        return ResponseEntity
            .status(HttpStatus.UNAUTHORIZED)
            .body(ApiError(exception.message ?: "Invalid token"))
    }

    @ExceptionHandler(UserNotFoundException::class)
    fun handleInvalidToken(exception: UserNotFoundException): ResponseEntity<ApiError> {
        return ResponseEntity
            .status(HttpStatus.UNAUTHORIZED)
            .body(ApiError(exception.message ?: "User doesn't exist anymore"))
    }
}

data class ApiError(
    val message: String
)
