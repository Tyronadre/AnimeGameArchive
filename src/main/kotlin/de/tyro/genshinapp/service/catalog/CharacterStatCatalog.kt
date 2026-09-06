package de.tyro.genshinapp.service.catalog

import de.tyro.genshinapp.model.*
import org.springframework.stereotype.Service

/** Resolves character stats from the ascension boundary values persisted from HoYoWiki. */
@Service
class CharacterStatCatalog(private val characters: CharacterCatalogStore) {
    fun find(key: String): CharacterStatProfile? {
        val sourceKey = sourceKey(key)
        val character = (
            characters.findCharacter(sourceKey)
                ?: if (sourceKey == "traveler") {
                    characters.findCharacter("traveleranemo")
                } else null
            ) ?: return null
        val first = character.hoyolabAscension.minByOrNull { it.level } ?: return null
        return CharacterStatProfile(
            key = character.key,
            base = CharacterBaseValues(
                hp = first.hpAfterAscension,
                attack = first.attackAfterAscension,
                defense = first.defenseAfterAscension,
                critRate = 5.0,
                critDamage = 50.0,
                energyRecharge = 100.0,
                elementalMastery = 0.0,
            ),
            hpCurve = null,
            attackCurve = null,
            defenseCurve = null,
            ascensionStat = character.ascensionStatType,
            ascensions = emptyList(),
            sourceRevision = character.hoyolabPageVersion.orEmpty(),
            sourceUrl = "https://wiki.hoyolab.com/pc/genshin/entry/${character.hoyolabEntryId}",
            hoyolabAscension = character.hoyolabAscension,
        )
    }

    fun resolve(key: String, level: Int, ascension: Int): Map<String, Double>? = find(key)?.resolve(level, ascension)

    companion object {
        fun sourceKey(key: String): String = GoodKeyNormalizer.normalize(key).let {
            if (it == "aether" || it == "lumine" ||
                TravelerElement.entries.any { element -> element.variantKey == it }
            ) "traveler" else it
        }
    }
}
