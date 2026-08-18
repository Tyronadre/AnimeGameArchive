package de.tyro.genshinapp.configuration

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import javax.sql.DataSource

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class LegacyGameCharacterSchemaCleanup(
    private val dataSource: DataSource,
    private val jdbcTemplate: JdbcTemplate,
) : ApplicationRunner {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var catalogSchemaPrepared = false

    override fun run(args: ApplicationArguments) {
        removeLegacyColumns()
    }

    @Synchronized
    fun prepareForCatalogAccess() {
        if (catalogSchemaPrepared) return
        removeLegacyColumns()
        catalogSchemaPrepared = true
    }

    private fun removeLegacyColumns() {
        val existingColumns = gameCharacterColumns()
        LEGACY_JSON_COLUMNS.filter(existingColumns::contains).forEach { column ->
            jdbcTemplate.execute("ALTER TABLE game_character DROP COLUMN $column")
            logger.info("Removed legacy game_character.{} JSON column", column)
        }
    }

    private fun gameCharacterColumns(): Set<String> =
        dataSource.connection.use { connection ->
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

    private companion object {
        private val LEGACY_JSON_COLUMNS = listOf(
            "image_urls_json",
            "remote_image_urls_json",
            "ascension_costs_json",
            "talent_costs_json",
            "talents_json",
        )
    }
}
