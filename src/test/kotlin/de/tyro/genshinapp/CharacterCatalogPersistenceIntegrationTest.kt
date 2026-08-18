package de.tyro.genshinapp

import de.tyro.genshinapp.configuration.LegacyGameCharacterSchemaCleanup
import de.tyro.genshinapp.service.CharacterCatalogStore
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest(
    properties = [
        "spring.docker.compose.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:character-catalog;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false",
    ],
)
class CharacterCatalogPersistenceIntegrationTest @Autowired constructor(
    private val characterCatalogStore: CharacterCatalogStore,
    private val jdbcTemplate: JdbcTemplate,
    private val legacySchemaCleanup: LegacyGameCharacterSchemaCleanup,
) {
    @Test
    fun `character catalog is persisted and reconstructed without json`() {
        val amberId = assertNotNull(
            jdbcTemplate.queryForObject(
                "SELECT id FROM game_character WHERE catalog_key = ?",
                Long::class.java,
                "amber",
            ),
        )
        fun relatedRows(table: String): Long = assertNotNull(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM $table WHERE character_id = ?",
                Long::class.java,
                amberId,
            ),
        )

        assertTrue(relatedRows("game_character_image") > 0)
        assertTrue(relatedRows("game_character_material_cost") > 0)
        assertTrue(relatedRows("game_character_talent") > 0)
        assertTrue(joinedRows("game_character_talent_attribute", amberId) > 0)
        assertTrue(joinedRows("game_character_talent_attribute_value", amberId) > 0)

        val storedAmber = assertNotNull(characterCatalogStore.findCharacter("amber"))
        assertTrue(storedAmber.imageUrls.isNotEmpty())
        assertTrue(storedAmber.ascensionCosts.isNotEmpty())
        assertTrue(storedAmber.talentCosts.isNotEmpty())
        assertTrue(storedAmber.talents.isNotEmpty())
        assertTrue(storedAmber.talents.any { it.attributes.isNotEmpty() })

        jdbcTemplate.execute("ALTER TABLE game_character ADD COLUMN talents_json CLOB")
        legacySchemaCleanup.run(DefaultApplicationArguments())
        assertFalse(gameCharacterColumns().contains("talents_json"))
    }

    private fun joinedRows(table: String, characterId: Long): Long {
        val joins = if (table == "game_character_talent_attribute") {
            "JOIN game_character_talent talent ON talent.id = child.talent_id"
        } else {
            """
            JOIN game_character_talent_attribute attribute
                ON attribute.id = child.attribute_id
            JOIN game_character_talent talent ON talent.id = attribute.talent_id
            """.trimIndent()
        }
        return assertNotNull(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM $table child $joins WHERE talent.character_id = ?",
                Long::class.java,
                characterId,
            ),
        )
    }

    private fun gameCharacterColumns(): Set<String> =
        requireNotNull(jdbcTemplate.dataSource).connection.use { connection ->
            connection.metaData.getColumns(connection.catalog, null, "%", "%").use { columns ->
                buildSet {
                    while (columns.next()) {
                        if (columns.getString("TABLE_NAME").equals("game_character", true)) {
                            add(columns.getString("COLUMN_NAME").lowercase())
                        }
                    }
                }
            }
        }
}
