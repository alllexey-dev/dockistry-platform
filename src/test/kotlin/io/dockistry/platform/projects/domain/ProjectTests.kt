package io.dockistry.platform.projects.domain

import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.iam.domain.PrincipalType
import io.dockistry.platform.iam.domain.Scope
import io.dockistry.platform.iam.domain.ScopeType
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProjectTests {
    @Test
    fun `accepts maximum slug length and rejects longer values`() {
        val maximumSlug = "p".repeat(Project.MAX_SLUG_LENGTH)

        assertEquals(maximumSlug, project(maximumSlug).slug)
        assertFailsWith<InvalidProjectSlugException> {
            project("p".repeat(Project.MAX_SLUG_LENGTH + 1))
        }
    }

    private fun project(slug: String): Project {
        return Project(
            name = "Project",
            slug = slug,
            ownerPrincipal = Principal(PrincipalType.USER),
            scope = Scope(ScopeType.PROJECT),
            createdAt = Instant.EPOCH
        )
    }
}
