package io.dockistry.platform.secrets.domain

import io.dockistry.platform.shared.exception.BadRequestException
import io.dockistry.platform.shared.exception.ConflictException
import io.dockistry.platform.shared.exception.NotFoundException

class InvalidSecretNameException(
    message: String
) : BadRequestException(message, "invalid_secret_name")

class SecretNotFoundException(
    message: String
) : NotFoundException(message, "secret_not_found")

class SecretAlreadyExistsException(
    message: String
) : ConflictException(message, "secret_already_exists")
