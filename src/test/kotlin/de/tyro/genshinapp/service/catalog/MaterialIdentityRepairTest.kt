package de.tyro.genshinapp.service.catalog

import de.tyro.genshinapp.entity.*
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.AfterEach
import org.mockito.Mockito.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import kotlin.test.*

@DataJpaTest(properties = ["spring.jpa.show-sql=false", "spring.docker.compose.enabled=false"])
@Import(MaterialIdentityRepair::class, MaterialIdentityRepairTest.Config::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MaterialIdentityRepairTest @Autowired constructor(
    private val repair: MaterialIdentityRepair,
    private val entityManager: EntityManager,
    transactionManager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(transactionManager)

    @TestConfiguration
    class Config {
        @Bean fun identities(): MaterialIdentityCatalog = mock(MaterialIdentityCatalog::class.java).also {
            `when`(it.gameIdForName(anyString())).thenReturn(null)
            `when`(it.gameIdForName("Teachings of Gold")).thenReturn(104316)
        }
    }

    private fun seed(canonicalPresent: Boolean = true, duplicates: Boolean = false, conflict: Boolean = false) {
        transactions.executeWithoutResult {
            val page = HoyowikiEntry().apply { pageId = 200; menuId = "9"; name = "Teachings of Gold" }
            entityManager.persist(page)
            val alias = Material().apply { gameId = 1500000200; name = "HoYoWiki material 200" }
            val canonical = Material().apply { gameId = 104316; name = page.name; hoyowikiEntry = page }
            entityManager.persist(alias)
            if (canonicalPresent) entityManager.persist(canonical)
            val character = GameCharacter().apply { key = "yanfei"; name = "Yanfei" }
            val weapon = GameWeapon().apply { key = "testweapon"; name = "Test weapon" }
            val source = MaterialSource().apply { catalogKey = "testsource"; name = "Test source"; sourceType = "TALENT_DOMAIN" }
            entityManager.persist(character); entityManager.persist(weapon); entityManager.persist(source)
            fun characterCost(material: Material, type: GameCharacterCostType, quantity: Long) {
                entityManager.persist(GameCharacterMaterialCost().apply {
                    this.character = character; this.material = material; costType = type; level = 2; amount = quantity
                })
            }
            fun weaponCost(material: Material) {
                entityManager.persist(GameWeaponMaterialCost().apply { this.weapon = weapon; this.material = material; phase = 1; amount = 2 })
            }
            fun sourceLink(material: Material) {
                entityManager.persist(MaterialSourceMaterial().apply { this.source = source; this.material = material; sourceRole = "DROP" })
            }
            characterCost(alias, GameCharacterCostType.ASCENSION, 12500)
            characterCost(alias, GameCharacterCostType.TALENT, 3)
            weaponCost(alias); sourceLink(alias)
            if (duplicates || conflict) characterCost(canonical, GameCharacterCostType.ASCENSION, if (conflict) 999 else 12500)
            if (duplicates) { weaponCost(canonical); sourceLink(canonical) }
            // Truly unknown pages remain intact until their identity can be established.
            entityManager.persist(Material().apply { gameId = 1500999999; name = "HoYoWiki material 999999" })
        }
    }

    @Test
    fun `repair redirects all costs and source memberships and is idempotent`() {
        seed()
        assertEquals(MaterialIdentityRepairResult(1, 4), repair.repair())
        assertRepaired()
        assertEquals(MaterialIdentityRepairResult(), repair.repair())
    }

    @Test
    fun `identical duplicate references are merged without doubling quantities`() {
        seed(duplicates = true)
        assertEquals(MaterialIdentityRepairResult(1, 1, 3), repair.repair())
        assertRepaired()
    }

    @Test
    fun `conflicting amounts retain the alias and its references`() {
        seed(conflict = true)
        assertEquals(MaterialIdentityRepairResult(conflictingMaterialIds = listOf(1500000200)), repair.repair())
        transactions.executeWithoutResult {
            assertEquals(1L, entityManager.createQuery("select count(m) from Material m where m.gameId = 1500000200", java.lang.Long::class.java).singleResult.toLong())
            assertEquals(2L, entityManager.createQuery("select count(c) from GameCharacterMaterialCost c where c.material.gameId = 1500000200", java.lang.Long::class.java).singleResult.toLong())
        }
    }

    @Test
    fun `a missing canonical material is created from a verified wiki identity`() {
        seed(canonicalPresent = false)
        assertEquals(MaterialIdentityRepairResult(1, 4), repair.repair())
        assertRepaired()
    }

    private fun assertRepaired() {
        transactions.executeWithoutResult {
            val costs = entityManager.createQuery("select c from GameCharacterMaterialCost c", GameCharacterMaterialCost::class.java).resultList
            assertEquals(listOf(3L, 12500L), costs.map { it.amount }.sorted())
            assertTrue(costs.all { it.material.gameId == 104316 && it.material.hoyowikiEntry?.pageId == 200L })
            assertEquals(104316, entityManager.createQuery("select w from GameWeaponMaterialCost w", GameWeaponMaterialCost::class.java).singleResult.material.gameId)
            assertEquals(104316, entityManager.createQuery("select s from MaterialSourceMaterial s", MaterialSourceMaterial::class.java).singleResult.material.gameId)
            val ids = entityManager.createQuery("select m.gameId from Material m", Int::class.javaObjectType).resultList
            assertFalse(1500000200 in ids)
            assertTrue(1500999999 in ids)
        }
    }

    @AfterEach
    fun cleanFixtures() {
        transactions.executeWithoutResult {
            listOf("GameCharacterMaterialCost", "GameWeaponMaterialCost", "MaterialSourceMaterial", "GameCharacter", "GameWeapon", "MaterialSource", "Material", "HoyowikiEntry")
                .forEach { entityManager.createQuery("delete from $it").executeUpdate() }
        }
    }
}
