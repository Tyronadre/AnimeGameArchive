package de.tyro.genshinapp.service.catalog

import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.repository.GameArtifactMainStatRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Equipment stats use HoYoWiki weapon boundaries and the local artifact main-stat table. */
@Service
@Transactional(readOnly = true)
class EquipmentStatCatalog(
    private val weapons: WeaponCatalogStore,
    private val artifacts: GameArtifactMainStatRepository,
) {
    fun weapon(key: String, level: Int, ascension: Int): Map<String, Double>? {
        val weapon = weapons.findWeapon(GoodKeyNormalizer.normalize(key)) ?: return null
        val rows = weapon.hoyolabAscension.sortedBy(WeaponHoyolabAscension::level)
        val start = rows.getOrNull(ascension) ?: return null
        val end = rows.getOrNull(ascension + 1) ?: return null
        if (level !in start.level..end.level) return null
        val distance = (end.level - start.level).takeIf { it > 0 } ?: return null
        val atStart = level == start.level && ascension > 0
        val startAttack = start.attackAfterAscension ?: return null
        val endAttack = end.attackBeforeAscension ?: return null
        val attack = if (atStart) startAttack else {
            startAttack + (endAttack - startAttack) * (level - start.level).toDouble() / distance
        }
        val stats = linkedMapOf("atk" to attack)
        weapon.secondaryStatType?.optimizerKey?.let { stat ->
            val startValue = start.secondaryStat ?: return null
            val endValue = end.secondaryStat ?: return null
            stats[stat] = startValue +
                (endValue - startValue) * (level - start.level).toDouble() / distance
        }
        return stats
    }

    fun artifactMainStat(rarity: Int, level: Int, key: String): Double? =
        artifacts.findById("$rarity:$level:$key").orElse(null)?.value?.takeIf(Double::isFinite)

}
