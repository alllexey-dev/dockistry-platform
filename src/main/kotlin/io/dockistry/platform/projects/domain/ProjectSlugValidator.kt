package io.dockistry.platform.projects.domain

object ProjectSlugValidator {
    const val PATTERN = "^[a-z0-9]+(?:-[a-z0-9]+)*$"

    fun validate(slug: String): String {
        if (!slug.matches(Regex(PATTERN))) {
            throw InvalidProjectSlugException("Project slug '$slug' is invalid")
        }
        return slug
    }
}
