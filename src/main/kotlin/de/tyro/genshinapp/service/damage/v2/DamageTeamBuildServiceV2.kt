package de.tyro.genshinapp.service.damage.v2

import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.model.PlayerSnapshot
import de.tyro.genshinapp.model.TravelerIdentity
import de.tyro.genshinapp.model.WeaponImageType
import de.tyro.genshinapp.service.catalog.EquipmentStatCatalog
import de.tyro.genshinapp.service.catalog.CharacterStatCatalog
import de.tyro.genshinapp.service.catalog.CharacterCatalogStore
import de.tyro.genshinapp.service.catalog.WeaponCatalogStore
import de.tyro.genshinapp.service.importer.HoyolabCharacterPageService
import de.tyro.genshinapp.service.importer.HoyolabWeaponGalleryService
import de.tyro.genshinapp.service.planning.CharacterTargetService
import de.tyro.genshinapp.service.player.PlayerSnapshotStore
import org.springframework.stereotype.Service

data class StoredBuildStatV2(val key: String, val label: String, val value: Double?, val formula: String? = null)
data class StoredWeaponBuildV2(
    val key: String, val name: String, val iconUrl: String?,
    val level: Int, val ascension: Int, val refinement: Int, val stats: List<StoredBuildStatV2>,
)

data class StoredArtifactBuildV2(
    val setKey: String, val name: String, val slot: String, val slotName: String, val iconUrl: String,
    val level: Int, val rarity: Int, val mainStat: StoredBuildStatV2, val substats: List<StoredBuildStatV2>,
)

data class StoredTeamBuildV2(
    val key: String, val name: String, val element: String, val iconUrl: String?,
    val owned: Boolean, val level: Int?, val ascension: Int?, val constellation: Int?,
    val characterStats: List<StoredBuildStatV2>, val weapon: StoredWeaponBuildV2?,
    val artifacts: List<StoredArtifactBuildV2>, val artifactStats: List<StoredBuildStatV2>,
    val totalStats: List<StoredBuildStatV2>, val issues: List<String>,
)

/** A separate, read-only build view. Never consumes formula inputs, effect state or manual stat bonuses. */
@Service
class DamageTeamBuildServiceV2(
    private val snapshotStore: PlayerSnapshotStore,
    private val targets: CharacterTargetService,
    private val characters: CharacterCatalogStore,
    private val weapons: WeaponCatalogStore,
    private val statCatalog: EquipmentStatCatalog,
    private val characterStatCatalog: CharacterStatCatalog,
    private val hoyolabCharacterPageService: HoyolabCharacterPageService,
    private val hoyolabWeaponService: HoyolabWeaponGalleryService
) {
    fun builds(profile: DamageFormulaProfileV2, userId: Long): List<StoredTeamBuildV2> {
        val snapshot = snapshotStore.current(userId)
        return profile.teamMembers.filter { it.key in profile.selectedTeam }.map { build(it, snapshot, userId) }
    }

    private fun build(member: DamageTeamMemberV2, snapshot: PlayerSnapshot?, userId: Long): StoredTeamBuildV2 {
        val key = TravelerIdentity.canonicalCharacterKey(member.key)
        val character = hoyolabCharacterPageService.enrichExact(key)
        val imported = snapshot?.characters?.find { TravelerIdentity.canonicalCharacterKey(it.key) == key }
        val progress = targets.find(userId, key)
        val owned = progress?.owned ?: (imported != null)
        val level = if (owned) progress?.currentLevel ?: imported?.level else null
        val ascension = if (owned) progress?.currentAscension ?: imported?.ascension else null
        val constellation = if (owned) progress?.currentConstellation ?: imported?.constellation else null
        val issues = mutableListOf<String>()
        val characterStats = if (level != null && ascension != null) characterStatCatalog.resolve(key, level, ascension) else null
        if (!owned) issues += "No owned character build is stored."
        else if (characterStats == null) issues += "Exact character stats are unavailable for the saved level / ascension."
        if (snapshot == null) issues += "No imported equipment snapshot is available."

        val equippedWeapons = snapshot?.weapons.orEmpty().filter { TravelerIdentity.canonicalCharacterKey(it.location.orEmpty()) == key }
        if (equippedWeapons.size > 1) issues += "Multiple weapons are assigned to this character; totals are unavailable."
        val weapon = equippedWeapons.singleOrNull()
        val weaponStats = weapon?.let { statCatalog.weapon(it.key, it.level, it.ascension) }
        val storedWeapon = weapon?.let {
            val metadata =hoyolabWeaponService.enrich(it.key)
            if (weaponStats == null) issues += "Exact weapon stats are unavailable for ${metadata?.name ?: it.name} at the saved level / ascension."
            StoredWeaponBuildV2(
                it.key, metadata?.name ?: it.name, metadata?.imageUrl(WeaponImageType.ICON),
                it.level, it.ascension, it.refinement, rows(weaponStats ?: mapOf("atk" to null))
            )
        }
        val equippedArtifacts = snapshot?.artifacts.orEmpty().filter { TravelerIdentity.canonicalCharacterKey(it.location.orEmpty()) == key }
        val duplicateSlots = equippedArtifacts.groupingBy { it.slotKey.lowercase() }.eachCount().any { it.value > 1 }
        if (duplicateSlots) issues += "Multiple artifacts occupy the same slot; totals are unavailable."
        val artifacts = equippedArtifacts.sortedBy { SLOT_ORDER.indexOf(it.slotKey.lowercase()).takeIf { n -> n >= 0 } ?: 99 }.map { artifact ->
            val mainStat = statCatalog.artifactMainStat(artifact.rarity, artifact.level, artifact.mainStatKey)
            if (mainStat == null) issues += "Exact main stat unavailable: ${artifact.slotName}, ${artifact.rarity}★ +${artifact.level}."
            StoredArtifactBuildV2(
                artifact.setKey, artifact.setName, artifact.slotKey, artifact.slotName,
                "/media/artifacts/${GoodKeyNormalizer.normalize(artifact.setKey)}/${artifact.slotKey.lowercase()}",
                artifact.level, artifact.rarity, row(artifact.mainStatKey, mainStat),
                artifact.substats.map { row(it.key, it.value.takeIf(Double::isFinite)) })
        }
        val artifactStats = artifacts.flatMap { listOf(it.mainStat) + it.substats }.groupBy { it.key }.mapValues { (_, stats) ->
            if (stats.any { it.value == null }) null else stats.sumOf { it.value!! }
        }
        val equipmentKnown = snapshot != null && !duplicateSlots && equippedWeapons.size <= 1 && (weapon == null || weaponStats != null)
        val totals = StoredBuildTotalsV2.combine(
            characterStats, if (equipmentKnown) weaponStats.orEmpty() else null,
            if (equipmentKnown) artifactStats else null,
        )
        return StoredTeamBuildV2(
            member.key, character?.name ?: member.name, character?.element ?: member.element,
            character?.iconImageUrl ?: member.iconUrl, owned, level, ascension, constellation,
            rows(characterStats ?: CORE_STATS.associateWith { null }), storedWeapon, artifacts, rows(artifactStats), totals,
            issues.distinct()
        )
    }

    companion object {
        val CORE_STATS = listOf("hp", "atk", "def", "eleMas", "critRate_", "critDMG_", "enerRech_")
        private val SLOT_ORDER = listOf("flower", "plume", "sands", "goblet", "circlet")
        fun row(key: String, value: Double?, formula: String? = null) = StoredBuildStatV2(key, GoodKeyNormalizer.statName(key), value, formula)
        fun rows(stats: Map<String, Double?>): List<StoredBuildStatV2> = stats.map { (key, value) -> row(key, value) }
    }
}

object StoredBuildTotalsV2 {
    /** Percentage ATK scales character + weapon base ATK; artifact flat ATK is added afterwards. */
    fun combine(character: Map<String, Double>?, weapon: Map<String, Double>?, artifacts: Map<String, Double?>?): List<StoredBuildStatV2> {
        val keys = (DamageTeamBuildServiceV2.CORE_STATS +
                listOf("heal_", "physical_dmg_", "pyro_dmg_", "hydro_dmg_", "dendro_dmg_", "electro_dmg_", "cryo_dmg_", "anemo_dmg_", "geo_dmg_") +
                character.orEmpty().keys + weapon.orEmpty().keys + artifacts.orEmpty().keys).distinct().filterNot { it in listOf("hp_", "atk_", "def_") }
        return keys.map { key ->
            if (character == null || weapon == null || artifacts == null) return@map DamageTeamBuildServiceV2.row(key, null)
            fun amount(map: Map<String, Double?>, stat: String) = if (map.containsKey(stat)) map[stat] else 0.0
            if (key in listOf("hp", "atk", "def")) {
                val base = character.getOrDefault(key, 0.0) + weapon.getOrDefault(key, 0.0)
                val flat = amount(artifacts, key)
                val percent = amount(artifacts, "${key}_")?.plus(character.getOrDefault("${key}_", 0.0))?.plus(weapon.getOrDefault("${key}_", 0.0))
                val value = if (flat == null || percent == null) null else base * (1 + percent / 100) + flat
                DamageTeamBuildServiceV2.row(key, value, if (value == null) null else "$base × (1 + $percent / 100) + $flat")
            } else {
                val artifact = amount(artifacts, key)
                val base = character.getOrDefault(key, 0.0)
                val equipment = weapon.getOrDefault(key, 0.0)
                DamageTeamBuildServiceV2.row(
                    key, artifact?.let { base + equipment + it },
                    artifact?.let { "$base + $equipment + $it" })
            }
        }
    }
}
