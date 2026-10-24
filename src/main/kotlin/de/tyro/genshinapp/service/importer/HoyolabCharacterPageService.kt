package de.tyro.genshinapp.service.importer

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import de.tyro.genshinapp.configuration.GenshinContentProperties
import de.tyro.genshinapp.model.CharacterDefinition
import de.tyro.genshinapp.model.CharacterTalent
import de.tyro.genshinapp.model.CharacterTalentAttribute
import de.tyro.genshinapp.model.CharacterTalentKind
import de.tyro.genshinapp.model.FightProperty
import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.service.catalog.CharacterCatalogService
import de.tyro.genshinapp.service.catalog.CharacterBaseStatCorrections
import de.tyro.genshinapp.service.catalog.MaterialCatalogService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import jakarta.annotation.PreDestroy

/** Imports structured character details directly from the official HoYoWiki page format. */
@Service
class HoyolabCharacterPageService(
    objectMapper: ObjectMapper,
    private val properties: GenshinContentProperties,
    private val characterCatalogService: CharacterCatalogService,
    private val materialCatalogService: MaterialCatalogService,
    private val source: HoyolabWikiDataSource = HttpHoyolabWikiDataSource(objectMapper, properties),
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val parser = HoyolabPageParser(objectMapper)
    private val locks = ConcurrentHashMap<String, Any>()
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val rerunCompleted = ConcurrentHashMap.newKeySet<String>()
    private val planningImports = Executors.newFixedThreadPool(4) { task ->
        Thread(task, "hoyowiki-planning-import").apply { isDaemon = true }
    }

    /** A catalog summary has no costs. Resolve missing details before adding up a plan. */
    fun ensureMaterialCosts(keys: Collection<String>) {
        val missing = keys.map(GoodKeyNormalizer::normalize).distinct().filter { key ->
            characterCatalogService.findExactCharacter(key)?.let {
                it.ascensionCosts.isEmpty() || it.talentCosts.isEmpty()
            } == true
        }
        planningImports.invokeAll(missing.map { key -> Callable { enrichExact(key) } })
            .forEach { it.get() }
    }

    @PreDestroy
    fun close() { planningImports.shutdownNow() }

    fun enrichExact(key: String, force: Boolean = false): CharacterDefinition? {
        val normalizedKey = GoodKeyNormalizer.normalize(key)
        val existing = characterCatalogService.findExactCharacter(normalizedKey) ?: return null
        if (shouldSkip(existing, force) || !properties.hoyolabWikiEnabled) {
            return existing
        }
        failedAt[normalizedKey]?.let { failure ->
            if (System.currentTimeMillis() - failure < FAILURE_COOLDOWN_MS) return existing
        }
        return synchronized(locks.computeIfAbsent(normalizedKey) { Any() }) {
            val current = characterCatalogService.findExactCharacter(normalizedKey)
                ?: return@synchronized null
            if (shouldSkip(current, force)) return@synchronized current
            val page = runCatching { lookupPage(current) }
                .onFailure { logger.warn("Could not load the HoYoWiki page for {}", current.name, it) }
                .getOrNull()
            if (page == null) {
                failedAt[normalizedKey] = System.currentTimeMillis()
                current
            } else {
                logger.info("Updating character: {}", normalizedKey)
                val imported = runCatching { importPage(page, force) }
                    .onFailure { logger.warn("Could not import the HoYoWiki page for {}", current.name, it) }
                    .getOrNull()
                if (imported == null || imported.ascensionCosts.isEmpty() || imported.talentCosts.isEmpty()) {
                    failedAt[normalizedKey] = System.currentTimeMillis()
                }
                else failedAt.remove(normalizedKey)
                imported ?: current
            }
        }
    }

    fun importPage(page: JsonNode, force: Boolean = false): CharacterDefinition? {
        val name = page.path("name").asText().trim().takeIf(String::isNotBlank) ?: return null
        val key = GoodKeyNormalizer.normalize(name)
        return synchronized(locks.computeIfAbsent(key) { Any() }) {
            val current = characterCatalogService.findExactCharacter(key) ?: return@synchronized null
            if (shouldSkip(current, force)) return@synchronized current
            importPageDetails(page, current)?.also {
                if (force) rerunCompleted.add(key)
            }
        }
    }

    private fun shouldSkip(character: CharacterDefinition, force: Boolean): Boolean =
        if (force) character.key in rerunCompleted else character.hoyolabDataVersion > 0 &&
            character.ascensionCosts.isNotEmpty() && character.talentCosts.isNotEmpty()

    private fun importPageDetails(page: JsonNode, current: CharacterDefinition): CharacterDefinition? {
        val name = page.path("name").asText().trim()
        val base = parser.baseValues(page)
        val filters = page.path("filter_values")
        val ascensionRows = parser.componentData(page, ASCENSION_COMPONENT_ID)
            ?.path("list")
            ?.takeIf(JsonNode::isArray)
            ?.toList()
            .orEmpty()
        val ascensionReferences = ascensionRows.flatMap { row ->
            parser.materialReferences(row.path("materials"))
        }
        val talentData = parseTalents(page)
        materialCatalogService.synchronizeHoyolabReferences(ascensionReferences + talentData.materialReferences)
        val ascensionCosts = ascensionRows.mapIndexedNotNull { index, row ->
            if (index == 0) return@mapIndexedNotNull null
            parser.materialReferences(row.path("materials"))
                .map(materialCatalogService::canonicalCost)
                .takeIf { it.isNotEmpty() }
                ?.let { index to it }
        }.toMap()

        val talentCosts = talentData.costReferences.mapValues { (_, references) ->
            references.map(materialCatalogService::canonicalCost)
        }
        val entryId = page.path("id").asText().toLongOrNull()
            ?: current.hoyolabEntryId
            ?: return null
        val ascensionStat = FightProperty.fromExternal(filterValue(filters, "character_property"))
            ?: current.ascensionStatType
        val stats = HoyolabCharacterStatsParser(parser).parse(ascensionRows, ascensionStat)
            ?.let { CharacterBaseStatCorrections.fillMissing(current.key, it) }
        if (stats == null && ascensionRows.isNotEmpty()) {
            logger.warn("Incomplete HoYoWiki stats for {}; keeping the previously imported stats", name)
        }
        val candidate = current.copy(
            id = entryId,
            name = name,
            title = base["Title"] ?: current.title,
            description = parser.plainText(page.path("desc").asText()) ?: current.description,
            weapon = filterValue(filters, "character_weapon") ?: current.weapon,
            rarity = filterValue(filters, "character_rarity")
                ?.let { RARITY_PATTERN.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
                ?: current.rarity,
            birthday = base["Birthday"] ?: current.birthday,
            element = filterValue(filters, "character_vision")
                ?: base["Vision"]
                ?: base["Moon Wheel"]
                ?: current.element,
            affiliation = base["Affiliation"] ?: current.affiliation,
            region = filterValue(filters, "character_region") ?: current.region,
            constellation = base["Constellation"] ?: current.constellation,
            ascensionStatType = ascensionStat,
            ascensionCosts = ascensionCosts.ifEmpty { current.ascensionCosts },
            talentCosts = talentCosts.ifEmpty { current.talentCosts },
            talents = talentData.talents.ifEmpty { current.talents },
            hoyolabEntryId = entryId,
            hoyolabPageVersion = page.path("version").asText().trim()
                .takeIf(String::isNotBlank) ?: current.hoyolabPageVersion,
            hoyolabDataVersion = CURRENT_DATA_VERSION,
            stats = stats ?: current.stats,
        )
        return characterCatalogService.saveHoyolabEnrichment(candidate)
    }

    private fun lookupPage(character: CharacterDefinition): JsonNode? {
        character.hoyolabEntryId?.let { id -> source.fetchPage(id)?.let { return it } }
        val ids = source.search(character.name).filter {
                it.name.equals(character.name, ignoreCase = true)
            }.sortedByDescending { HoyolabWikiMenu.CHARACTERS.id in it.menuIds }
                .map(HoyolabWikiSearchHit::entryPageId).distinct()
                .filter { it != character.hoyolabEntryId }
        return ids.firstNotNullOfOrNull(source::fetchPage)
    }

    private fun parseTalents(page: JsonNode): ParsedTalents {
        val rows = parser.componentData(page, TALENT_COMPONENT_ID)
            ?.path("list")?.takeIf(JsonNode::isArray)?.toList().orEmpty()
        var combatIndex = 0
        var passiveIndex = 0
        val talents = rows.mapNotNull { row ->
            val name = row.path("title").asText().trim().takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val attributesNode = row.path("attributes")
            val combat = attributesNode.isArray && !attributesNode.isEmpty
            val kind = if (combat) {
                when (combatIndex++) {
                    0 -> CharacterTalentKind.NORMAL_ATTACK
                    1 -> CharacterTalentKind.ELEMENTAL_SKILL
                    2 -> CharacterTalentKind.ELEMENTAL_BURST
                    else -> CharacterTalentKind.SPECIAL_MOVEMENT
                }
            } else {
                passiveIndex++
                CharacterTalentKind.PASSIVE
            }
            val key = when (kind) {
                CharacterTalentKind.NORMAL_ATTACK -> "combat1"
                CharacterTalentKind.ELEMENTAL_SKILL -> "combat2"
                CharacterTalentKind.ELEMENTAL_BURST -> "combat3"
                CharacterTalentKind.SPECIAL_MOVEMENT -> "combatsp$combatIndex"
                CharacterTalentKind.PASSIVE -> "passive$passiveIndex"
            }
            CharacterTalent(
                key = key,
                kind = kind,
                name = name,
                description = parser.plainText(row.path("desc").asText()).orEmpty(),
                flavorText = null,
                attributes = attributesNode.takeIf(JsonNode::isArray)?.mapNotNull { attribute ->
                    val label = attribute.path("key").asText().trim()
                    if (label.isBlank() || label.equals("Level", ignoreCase = true)) null
                    else CharacterTalentAttribute(
                        label,
                        attribute.path("values").takeIf(JsonNode::isArray)
                            ?.map(JsonNode::asText).orEmpty(),
                    )
                }.orEmpty(),
            )
        }
        val materialNode = rows.firstOrNull { it.path("materials").isArray }
            ?.path("materials")
        val references = materialNode?.flatMapIndexed { index, levelCosts ->
            if (index == 0) emptyList() else parser.materialReferences(levelCosts)
        }.orEmpty()
        val costs = materialNode?.mapIndexedNotNull { index, levelCosts ->
            if (index == 0) return@mapIndexedNotNull null
            parser.materialReferences(levelCosts)
                .takeIf { it.isNotEmpty() }?.let { (index + 1) to it }
        }?.toMap().orEmpty()
        return ParsedTalents(
            talents,
            costs,
            references,
        )
    }

    private fun filterValue(filters: JsonNode, key: String): String? =
        filters.path(key).path("value_types").takeIf(JsonNode::isArray)
            ?.firstOrNull()?.path("enum_string")?.asText()?.trim()?.takeIf(String::isNotBlank)
            ?: filters.path(key).path("values").takeIf(JsonNode::isArray)
                ?.firstOrNull()?.asText()?.trim()?.takeIf(String::isNotBlank)

    private data class ParsedTalents(
        val talents: List<CharacterTalent>,
        val costReferences: Map<Int, List<HoyolabMaterialReference>>,
        val materialReferences: List<HoyolabMaterialReference>,
    )

    private companion object {
        private const val CURRENT_DATA_VERSION = 2
        private const val FAILURE_COOLDOWN_MS = 5 * 60 * 1000L
        private const val ASCENSION_COMPONENT_ID = "ascension"
        private const val TALENT_COMPONENT_ID = "talent"
        private val RARITY_PATTERN = Regex("([1-5])")
    }
}
