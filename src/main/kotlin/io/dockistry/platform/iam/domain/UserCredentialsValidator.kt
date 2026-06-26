package io.dockistry.platform.iam.domain

object UserCredentialsValidator {
    private val USERNAME_REGEX = "^[a-zA-Z0-9._-]{3,32}$".toRegex()

    private val EMAIL_REGEX =
        "^[a-zA-Z0-9_+&*-]+(?:\\.[a-zA-Z0-9_+&*-]+)*@(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,}$"
            .toRegex()

    // All printable characters from ASCII table
    private val PASSWORD_REGEX = "^[\\x20-\\x7E]{8,128}$".toRegex()

    fun validateUsername(username: String): String {
        if (!username.matches(USERNAME_REGEX)) {
            throw InvalidUsernameException()
        }
        return username
    }

    fun validateEmail(email: String): String {
        if (!email.matches(EMAIL_REGEX)) {
            throw InvalidEmailException()
        }
        return email
    }

    fun validatePassword(password: String): String {
        if (!password.matches(PASSWORD_REGEX)) {
            throw InvalidPasswordException()
        }
        return password
    }
}