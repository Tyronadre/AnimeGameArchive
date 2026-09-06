package de.tyro.genshinapp.configuration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import de.tyro.genshinapp.entity.GameArtifactMainStat
import de.tyro.genshinapp.repository.GameArtifactMainStatRepository
import de.tyro.genshinapp.startup.ApplicationStartupStatus
import jakarta.annotation.PostConstruct
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component

/** Installs the small, source-independent artifact level table used by damage calculations. */
@Component
class ArtifactMainStatInitialization(
    private val objectMapper: ObjectMapper,
    private val repository: GameArtifactMainStatRepository,
) {
    @PostConstruct
    fun install() {
        ApplicationStartupStatus.report(
            "Loading artifact stat tables…",
            "Updating level values used by build calculations",
        )
        val resource = ClassPathResource("data/artifact-main-stats.json")
        val table = resource.inputStream.use(objectMapper::readTree)
        table.properties().forEach { (rarityKey, stats) ->
            val rarity = rarityKey.toInt()
            stats.properties().forEach { (statKey, values) ->
                values.takeIf(JsonNode::isArray)?.forEachIndexed { level, value ->
                    val id = "$rarity:$level:$statKey"
                    repository.save(
                        (repository.findById(id).orElse(null) ?: GameArtifactMainStat()).apply {
                            this.id = id
                            this.rarity = rarity
                            this.level = level
                            this.statKey = statKey
                            this.value = value.asDouble() * if (statKey.endsWith("_")) 100 else 1
                        },
                    )
                }
            }
        }
    }
}
