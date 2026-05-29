package io.dockistry.platform.exception

sealed class UserException(
    message: String
) : RuntimeException(message)

class UserAlreadyExistsException(
    field: String,
    value: String
) : UserException("User with $field = $value already exists")

class UserNotFoundException(message: String) : UserException(message)
