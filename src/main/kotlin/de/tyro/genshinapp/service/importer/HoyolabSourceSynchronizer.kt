package de.tyro.genshinapp.service.importer

import com.fasterxml.jackson.databind.JsonNode
import de.tyro.genshinapp.configuration.GenshinContentProperties
import de.tyro.genshinapp.model.*
import de.tyro.genshinapp.service.catalog.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@Service
class HoyolabSourceSynchronizer(
    private val source: HoyolabWikiDataSource,
    private val catalog: HoyowikiCatalogStore,
    private val materials: MaterialCatalogService,
    private val store: HoyowikiSourceStore,
    private val parser: HoyolabSourceParser,
    private val properties: GenshinContentProperties,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Bundled pages use exactly the same projection as live pages, without making HTTP calls. */
    @Synchronized
    fun synchronize(force: Boolean = false, bundled: List<JsonNode>? = null, bundledAt: Instant = Instant.now()): Int {
        val now = Instant.now()
        val materialEntries = catalog.entries(HoyolabWikiMenu.MATERIALS).associateBy { it.entryPageId }
        val artifactIds = catalog.entries(HoyolabWikiMenu.ARTIFACTS).map { it.entryPageId }.toSet()
        val supplied = bundled?.associateBy(HoyolabSourceParser::pageId)
        supplied?.forEach { (id, page) ->
            if (catalog.sourcePage(id)?.queriedAt?.isAfter(bundledAt) != true) catalog.recordDetailQuery(id, page, bundledAt)
        }
        val pages = ConcurrentHashMap<Long, Result<JsonNode>>()
        fun page(id: Long, preferCached: Boolean = false, refresh: Boolean = false): JsonNode = pages.computeIfAbsent(id) {
            runCatching {
                val cached = catalog.sourcePage(id)
                val fresh = cached?.queriedAt?.plus(properties.hoyolabCatalogFullRefreshInterval)?.isAfter(now) == true
                cached?.detail?.takeIf { supplied != null && cached.queriedAt?.isAfter(bundledAt) == true }
                    ?: supplied?.get(id) ?: cached?.detail?.takeIf { preferCached || (!force && !refresh && fresh) || supplied != null }
                    ?: if (supplied != null) error("Bundled reference $id is missing")
                    else requireNotNull(source.fetchPage(id)) { "Missing wiki page $id" }.also {
                        catalog.recordDetailQuery(id, it, now)
                    }
            }
        }.getOrThrow()
        fun resolve(id: Long): Pair<MaterialDefinition?, String> {
            if (id in artifactIds) return null to "Artifact"
            val entry = materialEntries[id]
            val detail = if (entry == null || entry.filters.values.flatten().none { it in MATERIAL_TYPES }) page(id) else null
            if (detail != null && detail.path("menu_id").asText() != "9") return null to "Non-material"
            val english = entry ?: HoyolabWikiEntry(id, requireNotNull(detail).path("name").asText(),
                detail.path("icon_url").asText().takeIf(String::isNotBlank))
            require(english.name.isNotBlank()) { "Missing material name for $id" }
            val type = detail?.let(parser::materialType)
                ?: entry!!.filters.values.flatten().first { it in MATERIAL_TYPES }
            return materials.resolveSourceMaterial(english) to type
        }
        val updated = AtomicInteger()
        val failed = AtomicInteger()
        val skipped = AtomicInteger()
        val unresolved = AtomicInteger()
        val targets = (catalog.entries(HoyolabWikiMenu.ENEMIES).map { it.entryPageId to false } +
            materialEntries.values.filter(HoyolabSourceParser::isTalent).map { it.entryPageId to true })
        Executors.newFixedThreadPool(4).use { executor ->
            executor.invokeAll(targets.map { (id, talent) -> Callable {
                val state = catalog.sourcePage(id)
                val stale = state?.parsedAt?.plus(properties.hoyolabCatalogFullRefreshInterval)?.isAfter(now) != true
                if (!force && !stale && state?.parserVersion == HoyolabSourceParser.VERSION && state.error == null) return@Callable
                try {
                    val detail = page(id, !force && state?.parserVersion != HoyolabSourceParser.VERSION && state?.error == null,
                        refresh = stale || state?.error != null)
                    if (talent) {
                        val parsed = parser.talent(detail)
                        val material = requireNotNull(resolve(id).first)
                        store.talent(parsed, material)
                        if (parsed.schedule == null) unresolved.incrementAndGet()
                    } else {
                        val parsed = parser.enemy(detail)
                        val rewards = parsed.drops.mapNotNull { reference ->
                            val (material, type) = resolve(reference.hoyolabPageId.toLong())
                            if (material == null) return@mapNotNull null
                            val category = when {
                                MaterialCatalogMetadata.isElementalGem(material.name) -> MaterialCategory.GEM
                                parsed.type != MaterialSourceType.ENEMY && type == "Character Level-Up Material" ->
                                    if (parsed.type == MaterialSourceType.WEEKLY_BOSS) MaterialCategory.WEEKLY_BOSS else MaterialCategory.WORLD_BOSS
                                parsed.type == MaterialSourceType.ENEMY && type == "Character and Weapon Enhancement Material" -> MaterialCategory.ENEMY_DROP
                                else -> { unresolved.incrementAndGet(); return@mapNotNull null }
                            }
                            ResolvedSourceReward(material, category,
                                if (category == MaterialCategory.GEM) MaterialSourceRole.GEM else MaterialSourceRole.DROP)
                        }
                        store.enemy(parsed, rewards)
                    }
                    updated.incrementAndGet()
                } catch (error: Exception) {
                    if (error is UnresolvedSourceException) skipped.incrementAndGet() else failed.incrementAndGet()
                    catalog.sourceFailure(id, error.message ?: error.javaClass.simpleName)
                    logger.debug("Source page {} retained its previous links: {}", id, error.message)
                }
            } }).forEach { it.get() }
        }
        materials.reloadFromDatabase()
        logger.info("HoYoWiki sources: {} updated, {} unresolved rewards/schedules, {} skipped types, {} failed pages",
            updated.get(), unresolved.get(), skipped.get(), failed.get())
        return updated.get()
    }

    companion object {
        private val MATERIAL_TYPES = setOf("Character Level-Up Material", "Character and Weapon Enhancement Material",
            "Character Talent Material", "Weapon Ascension Material", "Common Currency", "Consumable", "Material")
    }
}
