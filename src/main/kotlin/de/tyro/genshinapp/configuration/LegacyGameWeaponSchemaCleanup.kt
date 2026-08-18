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
class LegacyGameWeaponSchemaCleanup(
    private val dataSource: DataSource,
    private val jdbcTemplate: JdbcTemplate,
) : ApplicationRunner {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var catalogSchemaPrepared = false

    override fun run(args: ApplicationArguments) {
        replaceLegacyStorage()
    }

    @Synchronized
    fun prepareForCatalogAccess() {
        if (catalogSchemaPrepared) return
        replaceLegacyStorage()
        catalogSchemaPrepared = true
    }

    private fun replaceLegacyStorage() {
        val legacyColumns = LEGACY_COLUMNS.filter(gameWeaponColumns()::contains)
        if (legacyColumns.isEmpty()) return

        REPLACEMENT_TABLES.forEach { table ->
            jdbcTemplate.execute("DELETE FROM $table")
        }
        jdbcTemplate.update(
            "UPDATE game_weapon SET hoyolab_data_version = 0, hoyolab_page_version = NULL",
        )
        legacyColumns.forEach { column ->
            jdbcTemplate.execute("ALTER TABLE game_weapon DROP COLUMN $column")
        }
        logger.info(
            "Deleted legacy weapon storage; relational images and materials will be reloaded",
        )
    }

    private fun gameWeaponColumns(): Set<String> =
        dataSource.connection.use { connection ->
            connection.metaData.getColumns(connection.catalog, null, "%", "%").use { columns ->
                buildSet {
                    while (columns.next()) {
                        if (columns.getString("TABLE_NAME").equals("game_weapon", true)) {
                            add(columns.getString("COLUMN_NAME").lowercase())
                        }
                    }
                }
            }
        }

    private companion object {
        private val LEGACY_COLUMNS = listOf(
            "image_url",
            "remote_image_url",
            "hoyolab_icon_url",
            "full_image_url",
            "gallery_images_json",
            "hoyolab_ascension_json",
            "ascension_costs_json",
        )
        private val REPLACEMENT_TABLES = listOf(
            "game_weapon_material_cost",
            "game_weapon_progression",
            "game_weapon_image",
        )
    }
}
