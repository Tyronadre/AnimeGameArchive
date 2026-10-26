package de.tyro.genshinapp.service.importer

import de.tyro.genshinapp.service.catalog.ArtifactCatalogService
import de.tyro.genshinapp.service.catalog.CharacterCatalogService
import de.tyro.genshinapp.service.catalog.MaterialCatalogService
import de.tyro.genshinapp.service.catalog.WeaponCatalogService
import de.tyro.genshinapp.service.catalog.HoyowikiCatalogStore
import de.tyro.genshinapp.configuration.GenshinContentProperties
import de.tyro.genshinapp.model.FightProperty
import de.tyro.genshinapp.model.MaterialCategory
import jakarta.annotation.PreDestroy
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.time.Instant

/** Checks saved catalogs after startup; unchanged categories need only a one-entry request. */
@Service
class HoyolabCatalogStartupSynchronizer(
    private val source: HoyolabWikiDataSource,
    private val properties: GenshinContentProperties,
    private val characterCatalogService: CharacterCatalogService,
    private val weaponCatalogService: WeaponCatalogService,
    private val artifactCatalogService: ArtifactCatalogService,
    private val materialCatalogService: MaterialCatalogService,
    private val entryMapper: HoyolabCatalogEntryMapper,
    private val catalogStore: HoyowikiCatalogStore,
    private val sourceSynchronizer: HoyolabSourceSynchronizer? = null,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val started = AtomicBoolean()
    private val background = Executors.newSingleThreadExecutor { task ->
        Thread(task, "hoyowiki-catalog-refresh").apply { isDaemon = true }
    }

    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        if (!properties.hoyolabWikiEnabled || !properties.hoyolabCatalogSyncEnabled) return
        if (!started.compareAndSet(false, true)) return
        background.submit {
            runCatching { synchronizeNow() }
                .onSuccess { logger.info("HoYoWiki catalogue check completed; {} entries changed", it) }
                .onFailure { logger.warn("HoYoWiki catalogue check failed; keeping stored data", it) }
        }
    }

    @PreDestroy
    fun close() { background.shutdownNow() }

    /** force=true also refreshes unchanged categories, for explicit/manual refreshes. */
    @Synchronized
    fun synchronizeNow(force: Boolean = false): Int {
        materialCatalogService.refreshIdentityCatalog()
        val menus = listOf(HoyolabWikiMenu.MATERIALS, HoyolabWikiMenu.CHARACTERS,
            HoyolabWikiMenu.WEAPONS, HoyolabWikiMenu.ARTIFACTS, HoyolabWikiMenu.ENEMIES)
        val probes = Executors.newFixedThreadPool(4).use { executor ->
            val pending = menus.associateWith { menu ->
                executor.submit(Callable { runCatching { source.probeCatalog(menu) } })
            }
            pending.mapValues { (_, future) -> future.get() }
        }
        val changes = menus.sumOf { menu ->
            runCatching {
                val probe = probes.getValue(menu).getOrThrow()
                val now = Instant.now()
                val saved = catalogStore.checkpoint(menu)
                catalogStore.recordCheck(menu, probe.firstPageId, now)
                val stale = saved?.lastFullSyncAt?.let {
                    !it.plus(properties.hoyolabCatalogFullRefreshInterval).isAfter(now)
                } ?: true
                if (!force && !stale && saved?.total == probe.total && saved.firstPageId == probe.firstPageId) {
                    0
                } else {
                    val entries = source.listEntries(menu)
                    check(entries.isNotEmpty()) { "HoYoWiki returned an empty $menu catalogue" }
                    val changed = when (menu) {
                        HoyolabWikiMenu.MATERIALS -> materialCatalogService.synchronizeHoyolabMaterialList(
                            entries.mapNotNull(entryMapper::toMaterialSummary))
                        HoyolabWikiMenu.CHARACTERS -> characterCatalogService.synchronizeHoyolabCharacterList(
                            entries.mapNotNull(entryMapper::toCharacterSummary))
                        HoyolabWikiMenu.WEAPONS -> weaponCatalogService.synchronizeHoyolabWeaponList(
                            entries.mapNotNull(entryMapper::toWeaponSummary))
                        HoyolabWikiMenu.ARTIFACTS -> artifactCatalogService.synchronizeHoyolabArtifactList(entries)
                        else -> 0
                    }
                    catalogStore.recordCatalog(menu, entries, Instant.now(), live = true)
                    changed
                }
            }.onFailure { logger.warn("Could not refresh {} catalogue; keeping its previous checkpoint", menu, it) }
                .getOrDefault(0)
        }
        return changes + (sourceSynchronizer?.synchronize(force) ?: 0)
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
