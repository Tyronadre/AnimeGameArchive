package de.tyro.genshinapp.service.importer

import de.tyro.genshinapp.service.catalog.WeaponCatalogService
import de.tyro.genshinapp.service.catalog.WeaponDefinition
import de.tyro.genshinapp.service.catalog.WeaponHoyolabAscension
import de.tyro.genshinapp.service.catalog.MaterialCatalogService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import de.tyro.genshinapp.configuration.GenshinContentProperties
import de.tyro.genshinapp.model.FightProperty
import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.model.WeaponImageType
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class HoyolabWeaponGalleryService(
    private val objectMapper: ObjectMapper,
    private val properties: GenshinContentProperties,
    private val weaponCatalogService: WeaponCatalogService,
    private val materialCatalogService: MaterialCatalogService,
    private val source: HoyolabWikiDataSource = HttpHoyolabWikiDataSource(objectMapper, properties),
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val lookupLocks = ConcurrentHashMap<String, Any>()
    private val failedAt = ConcurrentHashMap<String, Long>()

    /**
     * Lazily imports the useful, structured parts of a HoYoWiki weapon page. The data-version
     * marker makes parser changes refresh previously persisted entries once on their next visit.
     */
    fun enrich(key: String): WeaponDefinition? {
        val normalizedKey = GoodKeyNormalizer.normalize(key)
        val existing = weaponCatalogService.find(normalizedKey) ?: return null
        if (existing.hasCurrentHoyolabData || !properties.hoyolabWikiEnabled) return existing
        val lastFailure = failedAt[normalizedKey]
        if (lastFailure != null && System.currentTimeMillis() - lastFailure < FAILURE_COOLDOWN_MS) {
            return existing
        }

        return synchronized(lookupLocks.computeIfAbsent(normalizedKey) { Any() }) {
            val current = weaponCatalogService.find(normalizedKey) ?: return@synchronized null
            if (current.hasCurrentHoyolabData) return@synchronized current

            val page = runCatching { lookupPage(current) }
                .onFailure {
                    logger.warn("Could not load the HoYoWiki page for {}", current.name, it)
                }
                .getOrNull()
            if (page == null) {
                failedAt[normalizedKey] = System.currentTimeMillis()
                return@synchronized current
            }

            failedAt.remove(normalizedKey)
            savePage(current, page)
        }
    }

    /** Imports a page captured in the build-time HoYoWiki snapshot. */
    fun importPage(page: JsonNode): WeaponDefinition? {
        val name = page.path("name").asText().trim().takeIf(String::isNotBlank) ?: return null
        val current = weaponCatalogService.find(GoodKeyNormalizer.normalize(name)) ?: return null
        val entryId = page.path("id").asText().toLongOrNull()
            ?: current.hoyolabEntryId
            ?: return null
        return parsePage(entryId, page)?.let { savePage(current, it) }
    }

    private fun savePage(current: WeaponDefinition, page: WeaponPageResult): WeaponDefinition {
        materialCatalogService.synchronizeHoyolabReferences(page.materialReferences)
        return weaponCatalogService.saveEnrichment(
            current.copy(
                name = page.name ?: current.name,
                rarity = page.rarity ?: current.rarity,
                weaponType = page.weaponType ?: current.weaponType,
                secondaryStatType = page.secondaryStatType ?: current.secondaryStatType,
                baseAttack = page.ascension.firstOrNull()?.attackAfterAscension
                    ?: current.baseAttack,
                baseSecondaryStat = page.ascension.firstOrNull()?.secondaryStat
                    ?: current.baseSecondaryStat,
                description = page.description ?: current.description,
                region = page.region ?: current.region,
                obtainMethod = page.obtainMethod ?: current.obtainMethod,
                releaseVersion = page.releaseVersion ?: current.releaseVersion,
                passiveName = page.passiveName ?: current.passiveName,
                passiveDescription = page.passiveDescription ?: current.passiveDescription,
                story = page.story ?: current.story,
                hoyolabEntryId = page.entryId,
                hoyolabPageVersion = page.pageVersion ?: current.hoyolabPageVersion,
                hoyolabDataVersion = CURRENT_DATA_VERSION,
                remoteImageUrls = current.remoteImageUrls + page.remoteImageUrls,
                hoyolabAscension = page.ascension.ifEmpty { current.hoyolabAscension },
                ascensionCosts = page.ascensionCosts.ifEmpty { current.ascensionCosts },
            ),
        )
    }

    private fun lookupPage(weapon: WeaponDefinition): WeaponPageResult? {
        val entryIds = buildList {
            weapon.hoyolabEntryId?.let(::add)
            addAll(findWeaponEntryIds(weapon.name.replace("\"", "")))
        }.distinct()
        return entryIds.firstNotNullOfOrNull(::loadPage)
    }

    private fun loadPage(requestedEntryId: Long): WeaponPageResult? {
        val page = source.fetchPage(requestedEntryId) ?: return null
        return parsePage(requestedEntryId, page)
    }

    private fun parsePage(requestedEntryId: Long, page: JsonNode): WeaponPageResult? {
        val parser = HoyolabPageParser(objectMapper)
        val baseInfo = componentData(page, BASE_INFO_COMPONENT_ID)
        val baseInfoItems = baseInfo?.path("list")
            ?.takeIf(JsonNode::isArray)
            ?.toList()
            .orEmpty()
        val baseValues = baseInfoItems.associate { item ->
            item.path("key").asText() to item.path("value").plainValues().joinToString("\n")
        }
        val passive = baseInfoItems.firstOrNull { item ->
            item.path("key").asText() !in KNOWN_BASE_INFO_KEYS
        }

        val galleryImages = componentData(page, GALLERY_COMPONENT_ID)
            ?.path("list")
            ?.takeIf(JsonNode::isArray)
            ?.mapNotNull(::toGalleryImage)
            .orEmpty()
        val ascendedImage = galleryImages.firstOrNull {
            it.label.equals("Awakened", ignoreCase = true)
        } ?: galleryImages.firstOrNull {
            it.description?.contains("after", ignoreCase = true) == true
        } ?: galleryImages.lastOrNull()
        val unascendedImage = galleryImages.firstOrNull {
            it.label.equals("Original", ignoreCase = true)
        } ?: galleryImages.firstOrNull {
            it.description?.contains("before", ignoreCase = true) == true
        } ?: galleryImages.firstOrNull { it !== ascendedImage }
        val remoteImageUrls = buildMap {
            ascendedImage?.url?.let { put(WeaponImageType.FULL_ASCENDED, it) }
            unascendedImage?.url?.let { put(WeaponImageType.FULL_UNASCENDED, it) }
        }

        val ascensionRows = componentData(page, ASCENSION_COMPONENT_ID)
            ?.path("list")
            ?.takeIf(JsonNode::isArray)
            ?.toList()
            .orEmpty()
        val ascension = ascensionRows
            .mapNotNull(::toAscensionRow)
            .sortedBy(WeaponHoyolabAscension::level)
        val materialReferences = ascensionRows.flatMap { row ->
            parser.materialReferences(row.path("materials"))
        }
        val ascensionCosts = ascensionRows.mapIndexedNotNull { index, row ->
            if (index == 0) return@mapIndexedNotNull null
            val costs = parser.materialReferences(row.path("materials"))
                .map(materialCatalogService::canonicalCost)
            costs.takeIf { it.isNotEmpty() }?.let { index to it }
        }.toMap()
        val story = componentData(page, STORY_COMPONENT_ID)
            ?.path("list")
            ?.takeIf(JsonNode::isArray)
            ?.mapNotNull { it.path("desc").asText().toPlainText() }
            ?.joinToString("\n\n")
            ?.takeIf(String::isNotBlank)

        val filters = page.path("filter_values")
        val rarity = filterValue(filters, "weapon_rarity")
            ?.let { RARITY_PATTERN.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
        val entryId = page.path("id").asText().toLongOrNull() ?: requestedEntryId

        return WeaponPageResult(
            entryId = entryId,
            name = page.optionalPlainText("name") ?: baseValues["Name"],
            description = page.optionalPlainText("desc"),
            pageVersion = page.path("version").asText().takeIf(String::isNotBlank),
            rarity = rarity,
            region = baseValues["Region"].nonBlank(),
            obtainMethod = baseValues["Source"].nonBlank(),
            weaponType = baseValues["Type"].nonBlank()
                ?: filterValue(filters, "weapon_type"),
            secondaryStatType = FightProperty.fromExternal(
                baseValues["Secondary Attributes"].nonBlank()
                    ?: filterValue(filters, "weapon_property"),
            ),
            releaseVersion = baseValues["Version Released"].nonBlank(),
            passiveName = passive?.path("key")?.asText()?.nonBlank(),
            passiveDescription = passive?.path("value")?.plainValues()?.joinToString("\n")?.nonBlank(),
            story = story,
            remoteImageUrls = remoteImageUrls,
            ascension = ascension,
            ascensionCosts = ascensionCosts,
            materialReferences = materialReferences,
        )
    }

    private fun componentData(page: JsonNode, componentId: String): JsonNode? {
        val component = page.descendants()
            .firstOrNull { it.path("component_id").asText() == componentId }
            ?: return null
        val data = component.path("data")
        return if (data.isTextual) {
            data.asText().takeIf(String::isNotBlank)?.let(objectMapper::readTree)
        } else {
            data.takeUnless(JsonNode::isMissingNode)
        }
    }

    private fun toAscensionRow(node: JsonNode): WeaponHoyolabAscension? {
        val level = LEVEL_PATTERN.find(node.path("key").asText())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: return null
        val combatRows = node.path("combatList").takeIf(JsonNode::isArray)?.toList().orEmpty()
        val headings = combatRows.firstOrNull()?.path("values")?.textValues().orEmpty()
        val values = combatRows.getOrNull(1)?.path("values")?.textValues().orEmpty()
        val stats = headings.mapIndexedNotNull { index, heading ->
            values.getOrNull(index)?.let { heading to it.toNumber() }
        }.toMap()
        return WeaponHoyolabAscension(
            level = level,
            attackBeforeAscension = stats[ATTACK_BEFORE_ASCENSION],
            attackAfterAscension = stats[ATTACK_AFTER_ASCENSION],
            secondaryStat = stats.entries.firstOrNull { (heading, _) ->
                heading != ATTACK_BEFORE_ASCENSION && heading != ATTACK_AFTER_ASCENSION
            }?.value,
        )
    }

    private fun findWeaponEntryIds(name: String): List<Long> {
        return source.search(name)
            .filter { hit -> hit.name.equals(name, ignoreCase = true) }
            .sortedByDescending { hit -> HoyolabWikiMenu.WEAPONS.id in hit.menuIds }
            .map(HoyolabWikiSearchHit::entryPageId)
            .distinct()
    }

    private fun filterValue(filters: JsonNode, key: String): String? {
        val filter = filters.path(key)
        return filter.path("value_types")
            .takeIf(JsonNode::isArray)
            ?.firstOrNull()
            ?.path("enum_string")
            ?.asText()
            ?.nonBlank()
            ?: filter.path("values").plainValues().firstOrNull()
    }

    private fun toGalleryImage(node: JsonNode): ImportedWeaponImage? {
        val url = normalizeHoyolabImageUrl(node.path("img").asText()) ?: return null
        val label = node.path("key").asText().ifBlank { "Gallery" }
        val description = node.path("imgDesc").asText().toPlainText()
        return ImportedWeaponImage(label, url, description)
    }

    private fun JsonNode.optionalPlainText(field: String): String? =
        path(field).asText().toPlainText()

    private fun JsonNode.plainValues(): List<String> =
        takeIf(JsonNode::isArray)
            ?.mapNotNull { value -> value.asText().toPlainText() }
            .orEmpty()

    private fun JsonNode.textValues(): List<String> =
        takeIf(JsonNode::isArray)?.map(JsonNode::asText).orEmpty()

    private fun String.toPlainText(): String? = takeIf(String::isNotBlank)
        ?.let { Jsoup.parse(it).body().wholeText() }
        ?.replace(HORIZONTAL_WHITESPACE, " ")
        ?.replace(EXCESS_NEWLINES, "\n\n")
        ?.trim()
        ?.takeIf(String::isNotBlank)

    private fun String?.nonBlank(): String? = this?.takeIf(String::isNotBlank)

    private fun String.toNumber(): Double? =
        replace(",", "")
            .replace("%", "")
            .replace("％", "")
            .trim()
            .takeUnless { it == "-" }
            ?.toDoubleOrNull()

    private fun JsonNode.descendants(): Sequence<JsonNode> = sequence {
        yield(this@descendants)
        for (child in this@descendants) {
            yieldAll(child.descendants())
        }
    }

    private val WeaponDefinition.hasCurrentHoyolabData: Boolean
        get() = hoyolabDataVersion >= CURRENT_DATA_VERSION

    private data class WeaponPageResult(
        val entryId: Long,
        val name: String?,
        val description: String?,
        val pageVersion: String?,
        val rarity: Int?,
        val region: String?,
        val obtainMethod: String?,
        val weaponType: String?,
        val secondaryStatType: FightProperty?,
        val releaseVersion: String?,
        val passiveName: String?,
        val passiveDescription: String?,
        val story: String?,
        val remoteImageUrls: Map<WeaponImageType, String>,
        val ascension: List<WeaponHoyolabAscension>,
        val ascensionCosts: Map<Int, List<de.tyro.genshinapp.model.MaterialCost>>,
        val materialReferences: List<HoyolabMaterialReference>,
    )

    private data class ImportedWeaponImage(
        val label: String,
        val url: String,
        val description: String? = null,
    )

    companion object {
        private const val CURRENT_DATA_VERSION = 1
        private const val BASE_INFO_COMPONENT_ID = "baseInfo"
        private const val ASCENSION_COMPONENT_ID = "ascension"
        private const val GALLERY_COMPONENT_ID = "gallery_character"
        private const val STORY_COMPONENT_ID = "story"
        private const val ATTACK_BEFORE_ASCENSION = "ATK before Ascension"
        private const val ATTACK_AFTER_ASCENSION = "ATK after Ascension"
        private const val FAILURE_COOLDOWN_MS = 5 * 60 * 1000L
        private val KNOWN_BASE_INFO_KEYS = setOf(
            "Name",
            "Region",
            "Source",
            "Type",
            "Secondary Attributes",
            "Version Released",
        )
        private val LEVEL_PATTERN = Regex("(\\d+)")
        private val RARITY_PATTERN = Regex("([1-5])")
        private val HORIZONTAL_WHITESPACE = Regex("[\\t \\x0B\\f\\r]+")
        private val EXCESS_NEWLINES = Regex("\\n{3,}")
    }
}
