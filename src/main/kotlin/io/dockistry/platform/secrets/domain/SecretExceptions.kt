package io.dockistry.platform.secrets.domain

import io.dockistry.platform.shared.exception.BadRequestException

class InvalidSecretNameException(
    message: String
) : BadRequestException(message, "invalid_secret_name")
