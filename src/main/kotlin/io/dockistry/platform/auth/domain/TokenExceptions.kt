package io.dockistry.platform.auth.domain

import io.dockistry.platform.shared.exception.UnauthorizedException

class InvalidTokenException(
    message: String = "The token is invalid",
    code: String = "invalid_token"
) : UnauthorizedException(message, code)

class TokenNotFoundException(
    message: String = "Token with provided ID not found",
    code: String = "token_not_found"
) : UnauthorizedException(message, code)

class TokenRevokedException(
    message: String = "Token has been already revoked",
    code: String = "token_revoked"
) : UnauthorizedException(message, code)
