package io.dockistry.platform.shared.domain

object SlugValidator {
    const val PATTERN = "^[a-z0-9]+(?:-[a-z0-9]+)*$"

    private val regex = Regex(PATTERN)

    fun validate(
        slug: String,
        maxLength: Int? = null,
        exceptionFactory: (String) -> RuntimeException
    ): String {
        if (!slug.matches(regex) || (maxLength != null && slug.length > maxLength)) {
            throw exceptionFactory(slug)
        }

        return slug
    }
}
