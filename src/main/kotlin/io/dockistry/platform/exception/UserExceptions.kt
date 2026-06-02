package io.dockistry.platform.exception

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


class AuthenticatedUserNotFoundException(
    message: String = "Authenticated user doesn't exist",
    code: String = "authenticated_user_not_found"
) : UnauthorizedException(message, code)
