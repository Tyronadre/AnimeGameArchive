package de.tyro.genshinapp.service.catalog

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import de.tyro.genshinapp.entity.*
import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.service.importer.HoyolabWikiEntry
import de.tyro.genshinapp.service.importer.HoyolabWikiMenu
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

data class HoyowikiCatalogCheckpoint(val total: Int?, val firstPageId: Long?, val lastFullSyncAt: Instant?)

@Service
class HoyowikiCatalogStore(
    private val entityManager: EntityManager,
    private val objectMapper: ObjectMapper,
    private val identityCatalog: MaterialIdentityCatalog,
) {
    @Transactional(readOnly = true)
    fun checkpoint(menu: HoyolabWikiMenu): HoyowikiCatalogCheckpoint? =
        entityManager.find(HoyowikiCatalogState::class.java, menu.id)?.let {
            HoyowikiCatalogCheckpoint(it.entryCount, it.firstPageId, it.lastFullSyncAt)
        }

    @Transactional(readOnly = true)
    fun hasBundledVersion(version: String): Boolean =
        entityManager.find(HoyowikiCatalogState::class.java, BUNDLED_KEY)?.bundledVersion == version

    @Transactional
    fun markBundledVersion(version: String) {
        state(BUNDLED_KEY).bundledVersion = version
    }

    @Transactional
    fun recordCheck(menu: HoyolabWikiMenu, firstPageId: Long, queriedAt: Instant) {
        state(menu.id).lastCheckedAt = queriedAt
        entityManager.find(HoyowikiEntry::class.java, firstPageId)?.lastQueriedAt = queriedAt
    }

    @Transactional(readOnly = true)
    fun entries(menu: HoyolabWikiMenu): List<HoyolabWikiEntry> =
        entityManager.createQuery(
            "select e from HoyowikiEntry e where e.menuId = :menu and e.summaryJson is not null",
            HoyowikiEntry::class.java,
        ).setParameter("menu", menu.id).resultList.map {
            objectMapper.readValue(it.summaryJson, HoyolabWikiEntry::class.java)
        }

    /** Called only after the entire category has been downloaded and projected successfully. */
    @Transactional
    fun recordCatalog(menu: HoyolabWikiMenu, entries: List<HoyolabWikiEntry>, at: Instant, live: Boolean) {
        require(entries.isNotEmpty() && entries.map { it.entryPageId }.distinct().size == entries.size)
        val checkpoint = state(menu.id)
        // Do not replace a newer live checkpoint with an older packaged snapshot.
        if (!live && checkpoint.lastFullSyncAt?.isAfter(at) == true) return
        val existing = entityManager.createQuery("select e from HoyowikiEntry e", HoyowikiEntry::class.java)
            .resultList.associateBy { it.pageId }
        val ids = entries.map { it.entryPageId }.toSet()
        existing.values.filter { it.menuId == menu.id && it.pageId !in ids }.forEach { it.menuId = "" }
        val pages = entries.associate { entry ->
            val page = existing[entry.entryPageId] ?: HoyowikiEntry().also {
                it.pageId = entry.entryPageId
                entityManager.persist(it)
            }
            page.menuId = menu.id
            page.name = entry.name
            page.summaryJson = objectMapper.writeValueAsString(entry)
            if (live) page.lastQueriedAt = at
            entry.entryPageId to page
        }
        linkCatalog(menu, entries, pages)
        checkpoint.entryCount = entries.size
        checkpoint.firstPageId = entries.first().entryPageId
        checkpoint.lastFullSyncAt = at
        if (live) checkpoint.lastCheckedAt = at
    }

    @Transactional
    fun recordDetailQuery(pageId: Long, detail: JsonNode, at: Instant) {
        val page = entityManager.find(HoyowikiEntry::class.java, pageId) ?: HoyowikiEntry().also {
            it.pageId = pageId
            it.name = detail.path("name").asText()
            entityManager.persist(it)
        }
        page.detailJson = objectMapper.writeValueAsString(detail)
        page.lastQueriedAt = at
        page.lastDetailQueriedAt = at
    }

    private fun linkCatalog(menu: HoyolabWikiMenu, entries: List<HoyolabWikiEntry>, pages: Map<Long, HoyowikiEntry>) {
        when (menu) {
            HoyolabWikiMenu.CHARACTERS -> entityManager.createQuery("select c from GameCharacter c", GameCharacter::class.java)
                .resultList.forEach { it.hoyowikiEntry = pages[it.hoyolabEntryId] ?: it.hoyowikiEntry }
            HoyolabWikiMenu.WEAPONS -> entityManager.createQuery("select w from GameWeapon w", GameWeapon::class.java)
                .resultList.forEach { it.hoyowikiEntry = pages[it.hoyolabEntryId] ?: it.hoyowikiEntry }
            HoyolabWikiMenu.MATERIALS -> {
                val materials = entityManager.createQuery("select m from Material m", Material::class.java)
                    .resultList.associateBy { it.gameId }
                entries.forEach { entry ->
                    val id = identityCatalog.gameIdForName(entry.name)
                        ?: Math.addExact(1_500_000_000, entry.entryPageId.toInt())
                    materials[id]?.hoyowikiEntry = pages.getValue(entry.entryPageId)
                }
            }
            HoyolabWikiMenu.ARTIFACTS -> {
                val sets = entityManager.createQuery("select a from GameArtifactSet a", GameArtifactSet::class.java)
                    .resultList.associateBy { it.setKey }
                val bonuses = entityManager.createQuery("select a from ArtifactSetBonus a", ArtifactSetBonus::class.java)
                    .resultList.associateBy { it.setKey }
                entries.forEach { entry ->
                    val key = GoodKeyNormalizer.normalize(entry.name)
                    val set = sets[key] ?: GameArtifactSet().also {
                        it.setKey = key
                        entityManager.persist(it)
                    }
                    set.name = entry.name
                    set.hoyowikiEntry = pages.getValue(entry.entryPageId)
                    bonuses[key]?.hoyowikiEntry = set.hoyowikiEntry
                }
            }
            else -> Unit
        }
    }

    private fun state(menuId: String): HoyowikiCatalogState =
        entityManager.find(HoyowikiCatalogState::class.java, menuId) ?: HoyowikiCatalogState().also {
            it.menuId = menuId
            entityManager.persist(it)
        }

    private companion object { const val BUNDLED_KEY = "bundled" }
}
