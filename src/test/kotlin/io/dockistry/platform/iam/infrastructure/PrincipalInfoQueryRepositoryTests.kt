package io.dockistry.platform.iam.infrastructure

import io.dockistry.platform.TestcontainersConfig
import io.dockistry.platform.iam.domain.PrincipalType
import io.dockistry.platform.testsupport.IamProjectFixtures
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@Testcontainers
@Import(TestcontainersConfig::class)
@Transactional
class PrincipalInfoQueryRepositoryTests {

    @Autowired
    private lateinit var principalInfoQueryRepository: PrincipalInfoQueryRepository

    @Autowired
    private lateinit var entityManager: EntityManager

    @Test
    fun `get info by id returns user principal info with username as display name`() {
        val fixtures = fixtures()
        val user = fixtures.user()
        fixtures.flush()

        val infoDto = principalInfoQueryRepository.getInfoById(user.principal.id)
        assertNotNull(infoDto)
        assertEquals(user.username, infoDto.name)
        assertEquals(user.id, infoDto.id)
        assertEquals(PrincipalType.USER, infoDto.type)
    }

    @Test
    fun `get info by id returns group principal info with group name as display name`() {
        val fixtures = fixtures()
        val group = fixtures.group()
        fixtures.flush()

        val infoDto = principalInfoQueryRepository.getInfoById(group.principal.id)
        assertNotNull(infoDto)
        assertEquals(group.name, infoDto.name)
        assertEquals(group.id, infoDto.id)
        assertEquals(PrincipalType.GROUP, infoDto.type)
    }

    @Test
    fun `get info by ids returns principal info map for mixed user and group principals`() {
        val fixtures = fixtures()
        val user = fixtures.user()
        val group = fixtures.group()
        fixtures.flush()

        val infoDtos = principalInfoQueryRepository.getInfoByIds(
            listOf(user.principal.id, group.principal.id)
        )

        assertEquals(setOf(user.id, group.id), infoDtos.keys)

        val userInfo = infoDtos.getValue(user.id)
        assertEquals(user.username, userInfo.name)
        assertEquals(PrincipalType.USER, userInfo.type)

        val groupInfo = infoDtos.getValue(group.id)
        assertEquals(group.name, groupInfo.name)
        assertEquals(PrincipalType.GROUP, groupInfo.type)
    }

    @Test
    fun `get info by id returns null when principal does not exist`() {
        val infoDto = principalInfoQueryRepository.getInfoById(UUID.randomUUID())

        assertNull(infoDto)
    }

    @Test
    fun `get info by ids omits principals that do not exist`() {
        val fixtures = fixtures()
        val user = fixtures.user()
        fixtures.flush()

        val missingPrincipalId = UUID.randomUUID()

        val infoDtos = principalInfoQueryRepository.getInfoByIds(
            listOf(user.principal.id, missingPrincipalId)
        )

        assertEquals(setOf(user.id), infoDtos.keys)
        assertEquals(user.username, infoDtos.getValue(user.id).name)
    }

    @Test
    fun `get info by ids returns empty map without querying invalid empty in clause`() {
        val infoDtos = principalInfoQueryRepository.getInfoByIds(emptyList())

        assertTrue(infoDtos.isEmpty())
    }

    private fun fixtures() = IamProjectFixtures(entityManager)
}
