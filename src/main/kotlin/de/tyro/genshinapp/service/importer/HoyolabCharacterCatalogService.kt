package de.tyro.genshinapp.service.importer

import de.tyro.genshinapp.service.catalog.ArtifactCatalogService
import de.tyro.genshinapp.service.catalog.CharacterCatalogService
import de.tyro.genshinapp.service.catalog.MaterialCatalogService
import de.tyro.genshinapp.service.catalog.WeaponCatalogService
import de.tyro.genshinapp.configuration.GenshinContentProperties
import de.tyro.genshinapp.model.FightProperty
import de.tyro.genshinapp.model.MaterialCategory
import de.tyro.genshinapp.startup.ApplicationStartupStatus
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs the official HoYoWiki catalogue projection once per application process. It is ordered
 * after the bundled snapshot so a fresh official catalogue list wins.
 */
@Service
@Order(Ordered.LOWEST_PRECEDENCE - 1)
class HoyolabCatalogStartupSynchronizer(
    private val source: HoyolabWikiDataSource,
    private val properties: GenshinContentProperties,
    private val characterCatalogService: CharacterCatalogService,
    private val weaponCatalogService: WeaponCatalogService,
    private val artifactCatalogService: ArtifactCatalogService,
    private val materialCatalogService: MaterialCatalogService,
    private val entryMapper: HoyolabCatalogEntryMapper,
) : ApplicationRunner {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val started = AtomicBoolean()

    override fun run(args: ApplicationArguments) {
        if (
            !properties.hoyolabWikiEnabled ||
            !properties.hoyolabCatalogSyncEnabled
        ) {
            logger.info("HoYoWiki catalogue synchronization is disabled")
            ApplicationStartupStatus.report(
                "Finalizing application…",
                "Live HoYoWiki catalogue refresh is disabled",
            )
            return
        }
        if (!started.compareAndSet(false, true)) return

        runCatching { synchronizeNow() }
            .onSuccess { changed ->
                logger.info("Synchronized HoYoWiki catalogue; {} entries changed", changed)
                ApplicationStartupStatus.report(
                    "Finalizing application…",
                    "$changed catalogue entries updated from HoYoWiki",
                )
            }
            .onFailure { error ->
                logger.warn(
                    "Could not synchronize the HoYoWiki catalogue; keeping stored data: {}",
                    error.message,
                )
                logger.debug("HoYoWiki catalogue synchronization failure", error)
                ApplicationStartupStatus.report(
                    "Finalizing application…",
                    "Using bundled and stored data because the live refresh failed",
                )
            }
    }

    fun synchronizeNow(): Int {
        materialCatalogService.refreshIdentityCatalog()
        ApplicationStartupStatus.report(
            "Refreshing HoYoWiki catalogue…",
            "Materials",
        )
        val materials = source.listEntries(HoyolabWikiMenu.MATERIALS)
            .mapNotNull(entryMapper::toMaterialSummary)
        if (materials.isEmpty()) {
            throw IllegalStateException("HoYoWiki returned an empty material catalogue")
        }
        val materialChanges = materialCatalogService.synchronizeHoyolabMaterialList(materials)

        ApplicationStartupStatus.report(
            "Refreshing HoYoWiki catalogue…",
            "Characters",
        )
        val characters = source.listEntries(HoyolabWikiMenu.CHARACTERS)
            .mapNotNull(entryMapper::toCharacterSummary)
        if (characters.isEmpty()) {
            throw IllegalStateException("HoYoWiki returned an empty character catalogue")
        }
        val characterChanges = characterCatalogService.synchronizeHoyolabCharacterList(characters)

        ApplicationStartupStatus.report(
            "Refreshing HoYoWiki catalogue…",
            "Weapons",
        )
        val weapons = source.listEntries(HoyolabWikiMenu.WEAPONS)
            .mapNotNull(entryMapper::toWeaponSummary)
        if (weapons.isEmpty()) {
            throw IllegalStateException("HoYoWiki returned an empty weapon catalogue")
        }
        val weaponChanges = weaponCatalogService.synchronizeHoyolabWeaponList(weapons)

        ApplicationStartupStatus.report(
            "Refreshing HoYoWiki catalogue…",
            "Artifacts",
        )
        val artifacts = source.listEntries(HoyolabWikiMenu.ARTIFACTS)
        if (artifacts.isEmpty()) {
            throw IllegalStateException("HoYoWiki returned an empty artifact catalogue")
        }
        val artifactChanges = artifactCatalogService.synchronizeHoyolabArtifactList(artifacts)
        return materialChanges + characterChanges + weaponChanges + artifactChanges
    }

}

@Component
class HoyolabCatalogEntryMapper {
    fun toCharacterSummary(entry: HoyolabWikiEntry): HoyolabCharacterSummary? {
        if (entry.name.isBlank()) return null
        val rarity = entry.firstFilter("character_rarity")
            ?.let { RARITY_PATTERN.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
        return HoyolabCharacterSummary(
            entryPageId = entry.entryPageId,
            name = entry.name,
            description = entry.description.toPlainText(),
            rarity = rarity,
            weapon = entry.firstFilter("character_weapon"),
            element = entry.firstFilter("character_vision"),
            region = entry.firstFilter("character_region"),
            ascensionStat = FightProperty.fromExternal(entry.firstFilter("character_property")),
        )
    }

    fun toWeaponSummary(entry: HoyolabWikiEntry): HoyolabWeaponSummary? {
        val iconUrl = entry.iconUrl?.let(::normalizeHoyolabImageUrl) ?: return null
        val rarity = entry.firstFilter("weapon_rarity")
            ?.let { RARITY_PATTERN.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
        return HoyolabWeaponSummary(
            entryPageId = entry.entryPageId,
            name = entry.name,
            iconUrl = iconUrl,
            description = entry.description.toPlainText(),
            rarity = rarity,
            weaponType = entry.firstFilter("weapon_type"),
            secondaryStatType = FightProperty.fromExternal(entry.firstFilter("weapon_property")),
        )
    }

    fun toMaterialSummary(entry: HoyolabWikiEntry): HoyolabMaterialSummary? {
        if (entry.name.isBlank()) return null
        val iconUrl = entry.iconUrl?.let(::normalizeHoyolabImageUrl)
        val type = entry.filters.values.flatten().firstOrNull {
            it.contains("Material", ignoreCase = true)
        }.orEmpty()
        return HoyolabMaterialSummary(
            entryPageId = entry.entryPageId,
            name = entry.name,
            iconUrl = iconUrl,
            category = when {
                type.contains("Weapon Ascension", ignoreCase = true) ->
                    MaterialCategory.WEAPON_ASCENSION
                type.contains("Talent", ignoreCase = true) -> MaterialCategory.TALENT_BOOK
                type.contains("Character and Weapon", ignoreCase = true) ->
                    MaterialCategory.ENEMY_DROP
                else -> MaterialCategory.OTHER
            },
        )
    }

    private fun String?.toPlainText(): String? = this?.takeIf(String::isNotBlank)
        ?.let { Jsoup.parse(it).body().wholeText().trim() }
        ?.takeIf(String::isNotBlank)

    private companion object {
        private val RARITY_PATTERN = Regex("([1-5])")
    }
}

data class HoyolabCharacterSummary(
    val entryPageId: Long,
    val name: String,
    val description: String? = null,
    val rarity: Int? = null,
    val weapon: String? = null,
    val element: String? = null,
    val region: String? = null,
    val ascensionStat: FightProperty? = null,
)

data class HoyolabWeaponSummary(
    val entryPageId: Long,
    val name: String,
    val iconUrl: String,
    val description: String? = null,
    val rarity: Int? = null,
    val weaponType: String? = null,
    val secondaryStatType: FightProperty? = null,
)

data class HoyolabMaterialSummary(
    val entryPageId: Long,
    val name: String,
    val iconUrl: String?,
    val category: MaterialCategory = MaterialCategory.OTHER,
)
