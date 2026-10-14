package de.tyro.genshinapp.service.catalog

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.tyro.genshinapp.model.CharacterStatProfile
import de.tyro.genshinapp.model.FightProperty
import de.tyro.genshinapp.model.GoodKeyNormalizer
import org.springframework.core.io.ClassPathResource

/** Base values missing from HoYoWiki, maintained as data rather than character branches in calculations. */
object CharacterBaseStatCorrections {
    private val corrections = ClassPathResource("data/character-base-stat-corrections.json").inputStream.use { input ->
        jacksonObjectMapper().readTree(input).properties().associate { (key, stats) ->
            key to stats.properties().associate { (stat, value) -> FightProperty.valueOf(stat) to value.asDouble() }
        }
    }

    fun fillMissing(key: String, profile: CharacterStatProfile): CharacterStatProfile {
        val missing = corrections[GoodKeyNormalizer.normalize(key)].orEmpty().filter { (stat, _) ->
            profile.baseStats.getOrDefault(stat, 0.0) == 0.0
        }
        return if (missing.isEmpty()) profile else profile.copy(baseStats = profile.baseStats + missing)
    }
}
