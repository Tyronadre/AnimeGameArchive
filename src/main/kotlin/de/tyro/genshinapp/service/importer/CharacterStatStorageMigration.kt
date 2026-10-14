package de.tyro.genshinapp.service.importer

import com.fasterxml.jackson.databind.ObjectMapper
import de.tyro.genshinapp.service.catalog.CharacterCatalogService
import de.tyro.genshinapp.service.catalog.CharacterBaseStatCorrections
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/** Upgrade old installations before the bundled snapshot is applied, including offline installations. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 40)
class CharacterStatStorageMigration(
    private val jdbc: JdbcTemplate,
    private val characters: CharacterCatalogService,
    private val mapper: ObjectMapper,
) : ApplicationRunner {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        val hasLegacyTable = requireNotNull(jdbc.dataSource).connection.use { connection ->
            connection.metaData.getTables(connection.catalog, null, "%", arrayOf("TABLE")).use { tables ->
                var found = false
                while (tables.next()) {
                    if (tables.getString("TABLE_NAME").equals("game_character_progression", true)) found = true
                }
                found
            }
        }
        if (!hasLegacyTable) return

        val rows = jdbc.query(
            """
            SELECT c.catalog_key, p.* FROM game_character_progression p
            JOIN game_character c ON c.id = p.character_id
            WHERE NOT EXISTS (SELECT 1 FROM game_character_base_stat b WHERE b.character_id = c.id)
            ORDER BY c.catalog_key, p.character_level
            """.trimIndent(),
        ) { result, _ ->
            val key = result.getString("catalog_key")
            val row = mapper.createObjectNode().put("key", "Lv.${result.getInt("character_level")}")
            val combat = row.putArray("combatList")
            mapOf("Base HP" to "hp", "Base ATK" to "attack", "Base DEF" to "defense", "Ascension Stat" to "secondary_stat")
                .forEach { (label, column) ->
                    val values = combat.addObject().put("key", label).putArray("values")
                    listOf("before", "after").forEach { side ->
                        values.add(result.getObject("${column}_${side}_ascension")?.toString() ?: "-")
                    }
                }
            key to row
        }
        val parser = HoyolabCharacterStatsParser(HoyolabPageParser(mapper))
        rows.groupBy({ it.first }, { it.second }).forEach { (key, progressions) ->
            val character = characters.findExactCharacter(key) ?: return@forEach
            val stats = parser.parse(progressions, character.ascensionStatType)
            if (stats == null) {
                logger.warn("Legacy stats for {} are incomplete; a HoYoWiki page refresh is needed", key)
            } else {
                characters.saveHoyolabEnrichment(character.copy(
                    stats = CharacterBaseStatCorrections.fillMissing(key, stats),
                ))
            }
        }
    }
}
