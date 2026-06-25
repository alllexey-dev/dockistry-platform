package io.dockistry.platform.iam.domain

import io.dockistry.platform.shared.exception.BadRequestException
import io.dockistry.platform.shared.exception.UnauthorizedException

class AuthenticatedUserNotFoundException(
    message: String = "Authenticated user doesn't exist",
    code: String = "authenticated_user_not_found"
) : UnauthorizedException(message, code)

class InvalidUsernameException : BadRequestException(
    message = "Username must consist of 3-32 English letters, digits, dots, underscores, dashes",
    code = "username_validation_failed"
)

class InvalidEmailException : BadRequestException(
    message = "Provided email is not a valid email",
    code = "email_validation_failed"
)

class InvalidPasswordException : BadRequestException(
    message = "Password must be 8-128 printable ASCII characters",
    code = "password_validation_failed"
)
