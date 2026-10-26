package de.tyro.genshinapp.service.catalog

import com.fasterxml.jackson.databind.JsonNode
import de.tyro.genshinapp.entity.*
import de.tyro.genshinapp.model.*
import de.tyro.genshinapp.service.importer.*
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

data class SourcePageState(val detail: JsonNode?, val parserVersion: Int?, val parsedAt: Instant?, val error: String?, val queriedAt: Instant?)
data class ResolvedSourceReward(val material: MaterialDefinition, val category: MaterialCategory, val role: MaterialSourceRole)

/** Projects a completely resolved page atomically, including its extraction checkpoint. */
@Service
class HoyowikiSourceStore(private val em: EntityManager, transactionManager: PlatformTransactionManager) {
    private val transactions = TransactionTemplate(transactionManager)
    private val writeLock = Any()

    fun enemy(parsed: ParsedEnemySource, rewards: List<ResolvedSourceReward>) = synchronized(writeLock) {
        transactions.executeWithoutResult { projectEnemy(parsed, rewards) }
    }

    private fun projectEnemy(parsed: ParsedEnemySource, rewards: List<ResolvedSourceReward>) {
        val source = em.createQuery("select s from MaterialSource s where s.hoyowikiEntry.pageId = :page", MaterialSource::class.java)
            .setParameter("page", parsed.pageId).resultList.singleOrNull()
            ?: em.createQuery("select s from MaterialSource s where s.sourceType = :type and (s.origin is null or s.origin = 'BUNDLED')", MaterialSource::class.java)
                .setParameter("type", parsed.type.name).resultList.filter {
                    GoodKeyNormalizer.normalize(it.name) == GoodKeyNormalizer.normalize(parsed.name)
                }.singleOrNull()
            ?: MaterialSource().also {
                it.catalogKey = "hoyowiki:enemy:${parsed.pageId}"
                em.persist(it)
            }
        val resolved = rewards.map { reward -> reward to material(reward.material, reward.category) }
        source.name = parsed.name
        source.region = parsed.region
        source.sourceType = parsed.type.name
        source.origin = "HOYOWIKI_ENEMY"
        source.hoyowikiEntry = requireNotNull(em.find(HoyowikiEntry::class.java, parsed.pageId))
        val desired = resolved.map { (reward, row) -> row.id to reward.role.name }.toSet()
        source.materials.removeIf { (it.material.id to it.sourceRole) !in desired }
        resolved.forEachIndexed { order, (reward, row) ->
            val membership = source.materials.firstOrNull { it.material.id == row.id && it.sourceRole == reward.role.name }
                ?: MaterialSourceMaterial().also { it.source = source; it.material = row; it.sourceRole = reward.role.name; source.materials += it }
            membership.materialOrder = if (reward.role == MaterialSourceRole.GEM) gemTier(row.name) else order
            membership.familyOrder = if (reward.role == MaterialSourceRole.GEM) gemFamily(row.name).hashCode() else 0
            if (reward.role == MaterialSourceRole.GEM) {
                row.craftingFamily = "gem:${GoodKeyNormalizer.normalize(gemFamily(row.name))}"
                row.craftingTier = gemTier(row.name)
                row.conversionGroup = "gem-tier"
            }
            if (parsed.type == MaterialSourceType.WEEKLY_BOSS && reward.role == MaterialSourceRole.DROP) {
                row.conversionGroup = source.catalogKey
                row.craftingFamily = source.catalogKey
            }
        }
        success(parsed.pageId)
    }

    fun talent(parsed: ParsedTalentChallenge, definition: MaterialDefinition) = synchronized(writeLock) {
        transactions.executeWithoutResult { projectTalent(parsed, definition) }
    }

    private fun projectTalent(parsed: ParsedTalentChallenge, definition: MaterialDefinition) {
        val row = material(definition, MaterialCategory.TALENT_BOOK)
        row.craftingFamily = parsed.family
        row.craftingTier = parsed.tier
        val linked = em.createQuery("select distinct s from MaterialSource s join s.materials m where m.material.id = :id and s.sourceType = 'TALENT_DOMAIN'", MaterialSource::class.java)
            .setParameter("id", row.id).resultList
        val physical = linked.firstOrNull { it.origin == null || it.origin == "BUNDLED" }
        val key = "hoyowiki:challenge:${GoodKeyNormalizer.normalize(parsed.region.orEmpty())}:${GoodKeyNormalizer.normalize(parsed.name)}"
        val source = physical ?: em.createQuery("select s from MaterialSource s where s.catalogKey = :key", MaterialSource::class.java)
            .setParameter("key", key).resultList.singleOrNull() ?: MaterialSource().also {
            it.catalogKey = key; it.name = parsed.name; it.region = parsed.region
            it.sourceType = MaterialSourceType.TALENT_DOMAIN.name; it.origin = "HOYOWIKI_CHALLENGE"
            em.persist(it)
        }
        // A changed challenge must not leave the same book on an obsolete challenge card.
        linked.filter { it !== source && it.origin == "HOYOWIKI_CHALLENGE" }.forEach {
            it.materials.removeIf { membership -> membership.material.id == row.id }
        }
        val membership = source.materials.firstOrNull { it.material.id == row.id }
            ?: MaterialSourceMaterial().also { it.source = source; it.material = row; it.sourceRole = MaterialSourceRole.DROP.name; source.materials += it }
        membership.materialOrder = parsed.tier
        if (physical == null) membership.familyOrder = parsed.family.hashCode()
        membership.scheduleKey = parsed.schedule?.name
        success(parsed.pageId)
    }

    private fun material(definition: MaterialDefinition, category: MaterialCategory): Material {
        val row = em.createQuery("select m from Material m where m.gameId = :id", Material::class.java)
            .setParameter("id", definition.id).resultList.single()
        check(row.categoryOrigin != "HOYOWIKI" || row.type == category.name) {
            "Conflicting explicit categories for ${row.name}: ${row.type} / $category"
        }
        row.type = category.name
        row.categoryOrigin = "HOYOWIKI"
        return row
    }

    private fun success(pageId: Long) {
        val page = requireNotNull(em.find(HoyowikiEntry::class.java, pageId))
        page.sourceParserVersion = HoyolabSourceParser.VERSION
        page.sourceParsedAt = Instant.now()
        page.sourceLastError = null
    }

    private fun gemFamily(name: String) = name.substringBeforeLast(' ')
    private fun gemTier(name: String) = listOf("Sliver", "Fragment", "Chunk", "Gemstone").indexOf(name.substringAfterLast(' '))
}
