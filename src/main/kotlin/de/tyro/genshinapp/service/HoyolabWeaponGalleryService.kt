package de.tyro.genshinapp.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import de.tyro.genshinapp.configuration.GenshinContentProperties
import de.tyro.genshinapp.model.GoodKeyNormalizer
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

@Service
class HoyolabWeaponGalleryService(
    private val objectMapper: ObjectMapper,
    private val properties: GenshinContentProperties,
    private val weaponCatalogService: WeaponCatalogService,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val lookupLocks = ConcurrentHashMap<String, Any>()
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(properties.connectTimeout)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

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
            weaponCatalogService.saveEnrichment(
                current.copy(
                    name = page.name ?: current.name,
                    rarity = page.rarity ?: current.rarity,
                    weaponType = page.weaponType ?: current.weaponType,
                    secondaryStatType = page.secondaryStatType
                        ?.let(::canonicalSecondaryStatType)
                        ?: current.secondaryStatType,
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
                    hoyolabIconUrl = page.iconUrl ?: current.hoyolabIconUrl,
                    hoyolabPageVersion = page.pageVersion ?: current.hoyolabPageVersion,
                    hoyolabDataVersion = CURRENT_DATA_VERSION,
                    fullImageUrl = page.preferredImageUrl ?: current.fullImageUrl,
                    galleryImages = page.galleryImages.ifEmpty { current.galleryImages },
                    hoyolabAscension = page.ascension.ifEmpty { current.hoyolabAscension },
                ),
            )
        }
    }

    private fun lookupPage(weapon: WeaponDefinition): WeaponPageResult? {
        val entryIds = buildList {
            weapon.hoyolabEntryId?.let(::add)
            addAll(findWeaponEntryIds(weapon.name.replace("\"", "")))
        }.distinct()
        return entryIds.firstNotNullOfOrNull(::loadPage)
    }

    private fun loadPage(requestedEntryId: Long): WeaponPageResult? {
        val root = getJson("/entry_page?entry_page_id=$requestedEntryId") ?: return null
        val page = root.path("data").path("page").takeIf(JsonNode::isObject) ?: return null
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
        val preferredImage = galleryImages.firstOrNull {
            it.label.equals("Awakened", ignoreCase = true)
        } ?: galleryImages.firstOrNull {
            it.description?.contains("after", ignoreCase = true) == true
        } ?: galleryImages.lastOrNull()

        val ascension = componentData(page, ASCENSION_COMPONENT_ID)
            ?.path("list")
            ?.takeIf(JsonNode::isArray)
            ?.mapNotNull(::toAscensionRow)
            ?.sortedBy(WeaponHoyolabAscension::level)
            .orEmpty()
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
            iconUrl = page.path("icon_url").asText().takeIf(::isAllowedImageUrl),
            pageVersion = page.path("version").asText().takeIf(String::isNotBlank),
            rarity = rarity,
            region = baseValues["Region"].nonBlank(),
            obtainMethod = baseValues["Source"].nonBlank(),
            weaponType = baseValues["Type"].nonBlank()
                ?: filterValue(filters, "weapon_type"),
            secondaryStatType = baseValues["Secondary Attributes"].nonBlank()
                ?: filterValue(filters, "weapon_property"),
            releaseVersion = baseValues["Version Released"].nonBlank(),
            passiveName = passive?.path("key")?.asText()?.nonBlank(),
            passiveDescription = passive?.path("value")?.plainValues()?.joinToString("\n")?.nonBlank(),
            story = story,
            galleryImages = galleryImages,
            preferredImageUrl = preferredImage?.url,
            ascension = ascension,
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
        val materials = node.path("materials")
            .takeIf(JsonNode::isArray)
            ?.flatMap(::toMaterials)
            .orEmpty()

        return WeaponHoyolabAscension(
            level = level,
            attackBeforeAscension = stats[ATTACK_BEFORE_ASCENSION],
            attackAfterAscension = stats[ATTACK_AFTER_ASCENSION],
            secondaryStat = stats.entries.firstOrNull { (heading, _) ->
                heading != ATTACK_BEFORE_ASCENSION && heading != ATTACK_AFTER_ASCENSION
            }?.value,
            materials = materials,
        )
    }

    private fun toMaterials(node: JsonNode): List<WeaponHoyolabMaterial> {
        val encoded = node.asText().removePrefix("\$").removeSuffix("\$")
        val parsed = runCatching { objectMapper.readTree(encoded) }.getOrNull() ?: return emptyList()
        return parsed.descendants()
            .filter(JsonNode::isObject)
            .mapNotNull { material ->
                val amount = material.path("amount").asLong(0).takeIf { it > 0 } ?: return@mapNotNull null
                WeaponHoyolabMaterial(
                    entryId = material.path("ep_id").asText().toLongOrNull(),
                    name = material.optionalPlainText("nickname"),
                    amount = amount,
                    imageUrl = material.path("img").asText().takeIf(::isAllowedImageUrl),
                )
            }
            .toList()
    }

    private fun findWeaponEntryIds(name: String): List<Long> {
        val encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8)
        val root = getJson("/search?keyword=$encodedName") ?: return emptyList()
        return root.descendants()
            .filter { node ->
                node.isObject &&
                    node.path("name").asText().equals(name, ignoreCase = true) &&
                    entryId(node) != null
            }
            .sortedByDescending(::belongsToWeaponMenu)
            .mapNotNull(::entryId)
            .distinct()
            .toList()
    }

    private fun entryId(node: JsonNode): Long? =
        node.path("entry_page_id").asText().toLongOrNull()
            ?: node.path("id").asText().toLongOrNull()

    private fun belongsToWeaponMenu(node: JsonNode): Boolean {
        if (node.path("menu_id").asText() == WEAPON_MENU_ID) return true
        val menu = node.path("menu")
        return menu.descendants().any { child -> child.path("id").asText() == WEAPON_MENU_ID }
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

    private fun toGalleryImage(node: JsonNode): WeaponGalleryImage? {
        val url = node.path("img").asText().takeIf(::isAllowedImageUrl) ?: return null
        val label = node.path("key").asText().ifBlank { "Gallery" }
        val description = node.path("imgDesc").asText().toPlainText()
        return WeaponGalleryImage(label, url, description)
    }

    private fun getJson(pathAndQuery: String): JsonNode? {
        val baseUrl = properties.hoyolabWikiApiUrl.trimEnd('/')
        val uri = URI.create("$baseUrl$pathAndQuery")
        val request = HttpRequest.newBuilder(uri)
            .timeout(properties.requestTimeout)
            .header("Accept", "application/json")
            .header("Referer", "https://wiki.hoyolab.com/")
            .header("User-Agent", "Mozilla/5.0 (compatible; GenshinArchive/1.0)")
            .header("x-rpc-language", "en-us")
            .GET()
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() !in 200..299 || response.body().isEmpty()) {
            logger.warn("HoYoWiki request {} returned HTTP {}", uri.path, response.statusCode())
            return null
        }
        val root = objectMapper.readTree(response.body())
        if (root.path("retcode").asInt(-1) != 0) {
            logger.warn("HoYoWiki request {} returned retcode {}", uri.path, root.path("retcode"))
            return null
        }
        return root
    }

    private fun isAllowedImageUrl(value: String): Boolean {
        if (value.length !in 1..MAX_URL_LENGTH) return false
        val uri = runCatching { URI.create(value) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.host?.lowercase() in ALLOWED_IMAGE_HOSTS
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

    private fun canonicalSecondaryStatType(value: String): String = when (value.lowercase()) {
        "crit rate" -> "FIGHT_PROP_CRITICAL"
        "crit dmg" -> "FIGHT_PROP_CRITICAL_HURT"
        "energy recharge" -> "FIGHT_PROP_CHARGE_EFFICIENCY"
        "elemental mastery" -> "FIGHT_PROP_ELEMENT_MASTERY"
        "atk", "atk%" -> "FIGHT_PROP_ATTACK_PERCENT"
        "hp", "hp%" -> "FIGHT_PROP_HP_PERCENT"
        "def", "def%" -> "FIGHT_PROP_DEFENSE_PERCENT"
        "physical dmg bonus", "physical dmg" -> "FIGHT_PROP_PHYSICAL_ADD_HURT"
        else -> value
    }

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
        val iconUrl: String?,
        val pageVersion: String?,
        val rarity: Int?,
        val region: String?,
        val obtainMethod: String?,
        val weaponType: String?,
        val secondaryStatType: String?,
        val releaseVersion: String?,
        val passiveName: String?,
        val passiveDescription: String?,
        val story: String?,
        val galleryImages: List<WeaponGalleryImage>,
        val preferredImageUrl: String?,
        val ascension: List<WeaponHoyolabAscension>,
    )

    companion object {
        private const val CURRENT_DATA_VERSION = 1
        private const val WEAPON_MENU_ID = "4"
        private const val BASE_INFO_COMPONENT_ID = "baseInfo"
        private const val ASCENSION_COMPONENT_ID = "ascension"
        private const val GALLERY_COMPONENT_ID = "gallery_character"
        private const val STORY_COMPONENT_ID = "story"
        private const val ATTACK_BEFORE_ASCENSION = "ATK before Ascension"
        private const val ATTACK_AFTER_ASCENSION = "ATK after Ascension"
        private const val MAX_URL_LENGTH = 4096
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
        private val ALLOWED_IMAGE_HOSTS = setOf(
            "upload-static.hoyoverse.com",
            "act-upload.hoyoverse.com",
            "act-webstatic.hoyoverse.com",
            "bbs.hoyolab.com",
        )
    }
}
