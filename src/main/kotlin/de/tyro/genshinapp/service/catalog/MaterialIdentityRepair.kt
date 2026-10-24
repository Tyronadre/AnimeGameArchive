package de.tyro.genshinapp.service.catalog

import de.tyro.genshinapp.entity.*
import de.tyro.genshinapp.model.GoodKeyNormalizer
import jakarta.annotation.PostConstruct
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

data class MaterialIdentityRepairResult(
    val mergedMaterials: Int = 0,
    val movedReferences: Int = 0,
    val duplicateReferences: Int = 0,
    val conflictingMaterialIds: List<Int> = emptyList(),
)

/** Repairs old wiki IDs and synthetic fallbacks before any catalog caches are populated. */
@Service
class MaterialIdentityRepair(
    private val entityManager: EntityManager,
    private val identities: MaterialIdentityCatalog,
    transactionManager: PlatformTransactionManager,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val transactions = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    @PostConstruct
    fun initialize() {
        val result = repair()
        if (result.mergedMaterials > 0) logger.info("Repaired material identities: {}", result)
        if (result.conflictingMaterialIds.isNotEmpty()) {
            logger.warn("Material identity repair retained aliases with conflicting costs: {}", result.conflictingMaterialIds)
        }
    }

    fun repair(): MaterialIdentityRepairResult = requireNotNull(transactions.execute {
        val materials = entityManager.createQuery(
            "select m from Material m left join fetch m.hoyowikiEntry", Material::class.java,
        ).resultList.associateBy { it.gameId }.toMutableMap()
        val pages = entityManager.createQuery("select e from HoyowikiEntry e", HoyowikiEntry::class.java)
            .resultList.associateBy { it.pageId }
        var merged = 0
        var moved = 0
        var duplicates = 0
        val conflicts = mutableListOf<Int>()
        materials.values.toList().forEach { alias ->
            val synthetic = alias.gameId >= SYNTHETIC_ID_BASE
            val pageId = if (synthetic) (alias.gameId - SYNTHETIC_ID_BASE).toLong() else alias.gameId.toLong()
            val page = pages[pageId] ?: alias.hoyowikiEntry
            // A small game ID can be legitimate. Only treat it as a legacy wiki ID when
            // the page ID and normalized material name both agree.
            if (!synthetic && (page?.pageId != pageId ||
                    GoodKeyNormalizer.normalize(alias.name) != GoodKeyNormalizer.normalize(page.name))) return@forEach
            val canonicalId = page?.name?.let(identities::gameIdForName)
                ?: identities.gameIdForName(alias.name) ?: return@forEach
            if (canonicalId == alias.gameId) return@forEach
            val canonical = materials[canonicalId] ?: Material().also {
                it.gameId = canonicalId
                it.name = page?.name ?: alias.name
                it.type = alias.type
                it.hoyowikiEntry = page
                entityManager.persist(it)
                materials[canonicalId] = it
            }
            entityManager.flush()
            val references = references(requireNotNull(alias.id), requireNotNull(canonical.id))
            val targets = references.filter { it.materialId == canonical.id }.associateBy { it.key }
            val sources = references.filter { it.materialId == alias.id }
            if (sources.any { source -> targets[source.key]?.let { it.amount != source.amount } == true }) {
                conflicts += alias.gameId
                return@forEach
            }
            if (canonical.hoyowikiEntry == null) canonical.hoyowikiEntry = page
            sources.forEach { source ->
                if (source.key in targets) {
                    entityManager.remove(source.entity)
                    duplicates++
                } else {
                    source.assign(canonical)
                    moved++
                }
            }
            entityManager.flush()
            entityManager.remove(alias)
            entityManager.flush()
            materials.remove(alias.gameId)
            merged++
        }
        MaterialIdentityRepairResult(merged, moved, duplicates, conflicts)
    })

    private fun references(aliasId: Long, canonicalId: Long): List<Reference> {
        val ids = listOf(aliasId, canonicalId)
        val characters = entityManager.createQuery(
            "select c from GameCharacterMaterialCost c where c.material.id in :ids", GameCharacterMaterialCost::class.java,
        ).setParameter("ids", ids).resultList.map { row ->
            Reference("character:${row.character.id}:${row.costType}:${row.level}", row.amount,
                requireNotNull(row.material.id), row) { row.material = it }
        }
        val weapons = entityManager.createQuery(
            "select w from GameWeaponMaterialCost w where w.material.id in :ids", GameWeaponMaterialCost::class.java,
        ).setParameter("ids", ids).resultList.map { row ->
            Reference("weapon:${row.weapon.id}:${row.phase}", row.amount,
                requireNotNull(row.material.id), row) { row.material = it }
        }
        val sources = entityManager.createQuery(
            "select s from MaterialSourceMaterial s where s.material.id in :ids", MaterialSourceMaterial::class.java,
        ).setParameter("ids", ids).resultList.map { row ->
            Reference("source:${row.source.id}:${row.sourceRole}", null,
                requireNotNull(row.material.id), row) { row.material = it }
        }
        return characters + weapons + sources
    }

    private data class Reference(
        val key: String,
        val amount: Long?,
        val materialId: Long,
        val entity: Any,
        val assign: (Material) -> Unit,
    )

    private companion object { const val SYNTHETIC_ID_BASE = 1_500_000_000 }
}
