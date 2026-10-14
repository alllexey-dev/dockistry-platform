package io.dockistry.platform.projects.domain

import io.dockistry.platform.iam.domain.Principal
import io.dockistry.platform.iam.domain.PrincipalType
import io.dockistry.platform.iam.domain.Scope
import io.dockistry.platform.iam.domain.ScopeType
import io.dockistry.platform.shared.domain.ResourceNameNormalizer
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ResourceNameNormalizerTests {
    @Test
    fun `normalizes unicode and compacts whitespace`() {
        val name = ResourceNameNormalizer.normalizeAndValidate(
            "  Cafe\u0301\n\tDeploy  ",
            ::InvalidProjectNameException
        )

        assertEquals("Café Deploy", name)
    }

    @Test
    fun `rejects blank name after cleanup`() {
        assertFailsWith<InvalidProjectNameException> {
            ResourceNameNormalizer.normalizeAndValidate(
                " \n\t ",
                ::InvalidProjectNameException
            )
        }
    }

    @Test
    fun `rejects invisible formatting characters`() {
        assertFailsWith<InvalidProjectNameException> {
            ResourceNameNormalizer.normalizeAndValidate(
                "project\u200Bname",
                ::InvalidProjectNameException
            )
        }
    }

    @Test
    fun `project stores normalized name on construction and assignment`() {
        val project = Project(
            name = "  Cafe\u0301\nDeploy  ",
            slug = "cafe-deploy",
            ownerPrincipal = Principal(PrincipalType.USER),
            createdAt = Instant.EPOCH,
            scope = Scope(ScopeType.PROJECT)
        )

        assertEquals("Café Deploy", project.name)

        project.name = "  Next\tName  "

        assertEquals("Next Name", project.name)
    }
}
