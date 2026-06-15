package io.dockistry.platform.shared.exception

import io.dockistry.platform.shared.api.ApiResponse
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

@RestControllerAdvice
class GlobalExceptionHandler : ResponseEntityExceptionHandler() {

    companion object {
        private val handlerLogger: Logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)
    }

    override fun createResponseEntity(
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest
    ): ResponseEntity<Any> {
        handlerLogger.warn("Request error with body (status {}): {}", statusCode, body)

        val problem = body as? ProblemDetail
        val status = HttpStatus.resolve(statusCode.value())

        val apiResponse = ApiResponse<Nothing>(
            errorMessage = problem?.detail
                ?: problem?.title
                ?: status?.reasonPhrase
                ?: "Unexpected request error",
            errorCode = errorCodeFor(problem, statusCode)
        )

        return ResponseEntity(apiResponse, headers, statusCode)
    }

    private fun errorCodeFor(problem: ProblemDetail?, statusCode: HttpStatusCode): String {
        return problem?.title
            ?.trim()
            ?.lowercase()
            ?.replace(Regex("\\s+"), "_")
            ?: "http_${statusCode.value()}"
    }

    @ExceptionHandler(ServiceException::class)
    fun handleServiceException(exception: ServiceException): ResponseEntity<ApiResponse<Nothing>> {
        val status = when (exception) {
            is ConflictException -> HttpStatus.CONFLICT
            is ForbiddenException -> HttpStatus.FORBIDDEN
            is UnauthorizedException -> HttpStatus.UNAUTHORIZED
            is NotFoundException -> HttpStatus.NOT_FOUND
            is BadRequestException -> HttpStatus.BAD_REQUEST
        }

        handlerLogger.warn("Service error (status {}): {}", status, exception.message, exception)

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
        handlerLogger.warn("Invalid username/password error: {}", exception.message, exception)

        return ResponseEntity
            .status(HttpStatus.UNAUTHORIZED)
            .body(
                ApiResponse(
                    errorMessage = exception.message ?: "Invalid username/password",
                    errorCode = "invalid_username_or_password"
                )
            )
    }

    @ExceptionHandler(RuntimeException::class)
    fun handleGenericRuntimeException(exception: RuntimeException): ResponseEntity<ApiResponse<Nothing>> {
        handlerLogger.error("Runtime error: {}", exception.message, exception)

        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(
                ApiResponse(
                    errorMessage = "Internal server error",
                    errorCode = "internal_server_error"
                )
            )
    }
}
