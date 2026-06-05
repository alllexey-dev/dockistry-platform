package io.dockistry.platform.auth.domain

import io.dockistry.platform.shared.exception.ConflictException
import io.dockistry.platform.shared.exception.UnauthorizedException

class UserAlreadyExistsException(
    field: String,
    value: String
) : ConflictException(
    message = "User with $field = $value already exists",
    code = "user_already_exists"
)

class UserForAuthenticationNotFoundException(
    message: String = "User not found for authentication",
    code: String = "user_for_authentication_not_found"
) : UnauthorizedException(message, code)