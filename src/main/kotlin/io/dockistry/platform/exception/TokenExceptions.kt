package io.dockistry.platform.exception

sealed class TokenException(
    message: String
) : RuntimeException(message)

class InvalidTokenException(
    message: String
) : TokenException(message)

class TokenNotFoundException : TokenException("couldn't find token with such id")

class TokenRevokedException : TokenException("token has been already revoked")
