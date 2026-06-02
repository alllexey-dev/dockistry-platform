package io.dockistry.platform.exception

sealed class ServiceException(
    message: String,
    val code: String
) : RuntimeException(message)

sealed class BadRequestException(message: String, code: String) : ServiceException(message, code)
sealed class UnauthorizedException(message: String, code: String) : ServiceException(message, code)
sealed class ForbiddenException(message: String, code: String) : ServiceException(message, code)
sealed class NotFoundException(message: String, code: String) : ServiceException(message, code)
sealed class ConflictException(message: String, code: String) : ServiceException(message, code)
