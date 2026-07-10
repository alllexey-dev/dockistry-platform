package io.dockistry.platform.services.domain

import io.dockistry.platform.shared.exception.BadRequestException
import io.dockistry.platform.shared.exception.ConflictException
import io.dockistry.platform.shared.exception.NotFoundException

class ManagedServiceNotFoundException(
    message: String
) : NotFoundException(message, "managed_service_not_found")

class ManagedServiceAlreadyExistsException(
    message: String
) : ConflictException(message, "managed_service_already_exists")

class ManagedServiceVersionConflictException(
    message: String
) : ConflictException(message, "managed_service_version_conflict")

class InvalidManagedServiceNameException(
    message: String
) : BadRequestException(message, "invalid_managed_service_name")

class InvalidManagedServiceImageRefException(
    message: String
) : BadRequestException(message, "invalid_managed_service_image_ref")

class InvalidManagedServiceConfigException(
    message: String
) : BadRequestException(message, "invalid_managed_service_config")
