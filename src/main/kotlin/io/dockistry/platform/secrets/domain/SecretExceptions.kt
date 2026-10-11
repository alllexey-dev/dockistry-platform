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

class SecretDelegationActiveException(
    message: String
) : ConflictException(message, "secret_delegation_active")

class InvalidSecretDescriptionException(
    message: String
) : BadRequestException(message, "invalid_secret_description")

class InvalidSecretReferenceException(
    message: String
) : BadRequestException(message, "invalid_secret_reference")

class InvalidSecretReferenceFormatException(
    message: String
) : BadRequestException(message, "invalid_secret_reference_format")

class SecretVersionNotFoundException(
    message: String
) : NotFoundException(message, "secret_version_not_found")

class SecretHasNoVersionsException(
    message: String
) : ConflictException(message, "secret_has_no_versions")

class InvalidSecretValuesException(
    message: String
) : BadRequestException(message, "invalid_secret_values")

class EmptySecretValuesException(
    message: String = "New secret version isn't allowed to have empty values"
) : BadRequestException(message, "empty_secret_values")

class SecretWarningsConfirmationRequiredException(
    message: String
) : ConflictException(message, "secret_warnings_confirmation_required")
