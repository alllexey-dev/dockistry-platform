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

class InvalidSecretReferenceException(
    message: String
) : BadRequestException(message, "invalid_secret_reference")

class InvalidSecretReferenceFormatException(
    message: String
) : BadRequestException(message, "invalid_secret_reference_format")

class EmptySecretValuesException(
    message: String = "New secret version isn't allowed to have empty values"
) : BadRequestException(message, "empty_secret_values")
