package io.dockistry.platform.shared.exception

sealed class ServiceException(
    message: String,
    val code: String
) : RuntimeException(message)

open class BadRequestException(message: String, code: String) : ServiceException(message, code)
open class UnauthorizedException(message: String, code: String) : ServiceException(message, code)
open class ForbiddenException(message: String, code: String) : ServiceException(message, code)
open class NotFoundException(message: String, code: String) : ServiceException(message, code)
open class ConflictException(message: String, code: String) : ServiceException(message, code)
open class ServiceUnavailableException(message: String, code: String) : ServiceException(message, code)
