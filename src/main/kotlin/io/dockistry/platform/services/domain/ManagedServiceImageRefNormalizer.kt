package io.dockistry.platform.services.domain

object ManagedServiceImageRefNormalizer {
    private val repositoryComponentRegex = Regex("^[a-z0-9]+(?:(?:[._]|__|-+)[a-z0-9]+)*$")
    private val tagRegex = Regex(
        "^[A-Za-z0-9_][A-Za-z0-9_.-]{0,${MAX_TAG_LENGTH - 1}}$"
    )

    fun normalize(imageRef: String): ManagedServiceImageRef {
        val trimmedImageRef = imageRef.trim()
        if (trimmedImageRef.isBlank()) {
            throw invalid(imageRef)
        }
        if (trimmedImageRef.contains("@")) {
            throw InvalidManagedServiceImageRefException(
                "Image digests are not supported for managed services yet"
            )
        }

        val lastSlashIndex = trimmedImageRef.lastIndexOf('/')
        val lastColonIndex = trimmedImageRef.lastIndexOf(':')
        val hasTag = lastColonIndex > lastSlashIndex

        val rawRepository = if (hasTag) {
            trimmedImageRef.substring(0, lastColonIndex)
        } else {
            trimmedImageRef
        }
        val tag = if (hasTag) {
            trimmedImageRef.substring(lastColonIndex + 1)
        } else {
            DEFAULT_TAG
        }

        val repository = rawRepository.lowercase()
        validateRepository(repository, imageRef)
        validateTag(tag, imageRef)

        return ManagedServiceImageRef(repository, tag)
    }

    private fun validateRepository(repository: String, imageRef: String) {
        if (repository.isBlank() || repository.length > MAX_REPOSITORY_LENGTH) {
            throw invalid(imageRef)
        }

        val firstComponent = repository.substringBefore("/")
        if (
            firstComponent == "localhost" ||
            firstComponent.contains(".") ||
            firstComponent.contains(":")
        ) {
            throw InvalidManagedServiceImageRefException(
                "Custom image registries are not supported for managed services yet"
            )
        }

        if (
            repository.split("/").any {
                it.isBlank() || !it.matches(repositoryComponentRegex)
            }
        ) {
            throw invalid(imageRef)
        }
    }

    private fun validateTag(tag: String, imageRef: String) {
        if (tag.isBlank() || !tag.matches(tagRegex)) {
            throw invalid(imageRef)
        }
    }

    private fun invalid(imageRef: String): InvalidManagedServiceImageRefException {
        return InvalidManagedServiceImageRefException(
            "Managed service image ref '$imageRef' is invalid"
        )
    }

    const val MAX_REPOSITORY_LENGTH = 255
    const val MAX_TAG_LENGTH = 128

    private const val DEFAULT_TAG = "latest"
}
