package de.tyro.genshinapp.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import de.tyro.genshinapp.entity.ArtifactSetBonus
import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.model.PlayerArtifact
import de.tyro.genshinapp.repository.ArtifactSetBonusRepository
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import java.time.Instant

interface ArtifactSetBonusProvider {
    fun activeBonuses(artifacts: Collection<PlayerArtifact>): ArtifactSetBonusTotals
}

@Service
class ArtifactSetBonusService(
    private val objectMapper: ObjectMapper,
    private val repository: ArtifactSetBonusRepository,
) : ArtifactSetBonusProvider {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var definitionsBySetKey: Map<String, ArtifactSetBonusDefinition> = emptyMap()

    @PostConstruct
    fun initialize() {
        val seedCatalog = readSeedCatalog()
        val stored = repository.findAll().associateBy(ArtifactSetBonus::setKey)
        val changed = seedCatalog.sets.mapNotNull { seed ->
            val setKey = GoodKeyNormalizer.normalize(seed.setKey)
            val entity = stored[setKey]
            if (entity != null && entity.sourceVersion == seedCatalog.version) {
                null
            } else {
                (entity ?: ArtifactSetBonus()).also {
                    it.setKey = setKey
                    it.setName = seed.setName
                    it.sourceVersion = seedCatalog.version
                    it.bonusesJson = objectMapper.writeValueAsString(seed.bonuses)
                    it.updatedAt = Instant.now()
                }
            }
        }
        if (changed.isNotEmpty()) {
            repository.saveAll(changed)
            logger.info(
                "Stored {} new or updated artifact set bonus definitions (version {})",
                changed.size,
                seedCatalog.version,
            )
        }
        definitionsBySetKey = repository.findAll().mapNotNull(::toDefinition)
            .associateBy(ArtifactSetBonusDefinition::setKey)
    }

    override fun activeBonuses(
        artifacts: Collection<PlayerArtifact>,
    ): ArtifactSetBonusTotals {
        if (artifacts.isEmpty()) return ArtifactSetBonusTotals()
        val counts = artifacts.groupingBy { artifact ->
            GoodKeyNormalizer.normalize(artifact.setKey)
        }.eachCount()
        val activations = counts.entries.flatMap { (setKey, count) ->
            definitionsBySetKey[setKey]
                ?.bonuses
                .orEmpty()
                .filter { bonus -> count >= bonus.pieces }
                .map { bonus ->
                    ActiveArtifactSetBonus(
                        setKey = setKey,
                        setName = definitionsBySetKey.getValue(setKey).setName,
                        pieces = bonus.pieces,
                        effect = bonus.effect,
                        assumption = bonus.assumption,
                        stats = bonus.stats,
                    )
                }
        }.sortedWith(
            compareBy<ActiveArtifactSetBonus>(ActiveArtifactSetBonus::setName)
                .thenBy(ActiveArtifactSetBonus::pieces),
        )
        val stats = linkedMapOf<String, Double>()
        activations.forEach { activation ->
            activation.stats.forEach { (key, value) ->
                stats[key] = stats.getOrDefault(key, 0.0) + value
            }
        }
        return ArtifactSetBonusTotals(stats, activations)
    }

    fun allDefinitions(): List<ArtifactSetBonusDefinition> =
        definitionsBySetKey.values.sortedBy(ArtifactSetBonusDefinition::setName)

    private fun readSeedCatalog(): ArtifactSetBonusSeedCatalog =
        ClassPathResource(CATALOG_RESOURCE).inputStream.use { input ->
            objectMapper.readValue(input, ArtifactSetBonusSeedCatalog::class.java)
        }

    private fun toDefinition(entity: ArtifactSetBonus): ArtifactSetBonusDefinition? = runCatching {
        ArtifactSetBonusDefinition(
            setKey = entity.setKey,
            setName = entity.setName,
            bonuses = objectMapper.readValue(entity.bonusesJson, BONUSES_TYPE),
        )
    }.onFailure {
        logger.warn("Stored artifact set bonus for '{}' is invalid", entity.setKey, it)
    }.getOrNull()

    companion object {
        private const val CATALOG_RESOURCE = "data/artifact-set-bonuses.json"
        private val BONUSES_TYPE = object : TypeReference<List<ArtifactSetBonusTier>>() {}
    }
}

data class ArtifactSetBonusSeedCatalog(
    val version: Int = 1,
    val sets: List<ArtifactSetBonusDefinition> = emptyList(),
)

data class ArtifactSetBonusDefinition(
    val setKey: String = "",
    val setName: String = "",
    val bonuses: List<ArtifactSetBonusTier> = emptyList(),
)

data class ArtifactSetBonusTier(
    val pieces: Int = 2,
    val effect: String = "",
    val assumption: String? = null,
    val stats: Map<String, Double> = emptyMap(),
)

data class ArtifactSetBonusTotals(
    val stats: Map<String, Double> = emptyMap(),
    val activations: List<ActiveArtifactSetBonus> = emptyList(),
)

data class ActiveArtifactSetBonus(
    val setKey: String,
    val setName: String,
    val pieces: Int,
    val effect: String,
    val assumption: String?,
    val stats: Map<String, Double>,
)
