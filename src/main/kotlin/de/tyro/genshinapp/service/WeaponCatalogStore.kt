package de.tyro.genshinapp.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import de.tyro.genshinapp.entity.GameWeapon
import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.model.MaterialCost
import de.tyro.genshinapp.repository.GameWeaponRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

interface WeaponCatalogStore {
    fun getWeapons(): List<WeaponDefinition>

    fun findWeapon(key: String): WeaponDefinition?

    fun saveWeapon(weapon: WeaponDefinition): WeaponDefinition
}

@Service
class JpaWeaponCatalogStore(
    private val objectMapper: ObjectMapper,
    private val repository: GameWeaponRepository,
) : WeaponCatalogStore {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    override fun getWeapons(): List<WeaponDefinition> =
        repository.findAllByOrderByNameAsc().mapNotNull(::toDefinition)

    @Transactional(readOnly = true)
    override fun findWeapon(key: String): WeaponDefinition? =
        repository.findByKey(GoodKeyNormalizer.normalize(key))?.let(::toDefinition)

    @Transactional
    override fun saveWeapon(weapon: WeaponDefinition): WeaponDefinition {
        val normalizedKey = GoodKeyNormalizer.normalize(weapon.key)
        require(normalizedKey.isNotBlank()) { "Weapon key must not be blank" }
        val entity = repository.findByKey(normalizedKey)
            ?: GameWeapon().also { it.key = normalizedKey }

        entity.name = weapon.name
        entity.rarity = weapon.rarity
        entity.weaponType = weapon.weaponType
        entity.secondaryStatType = weapon.secondaryStatType
        entity.baseAttack = weapon.baseAttack
        entity.baseSecondaryStat = weapon.baseSecondaryStat
        entity.description = weapon.description
        entity.region = weapon.region
        entity.obtainMethod = weapon.obtainMethod
        entity.releaseVersion = weapon.releaseVersion
        entity.passiveName = weapon.passiveName
        entity.passiveDescription = weapon.passiveDescription
        entity.story = weapon.story
        entity.imageUrl = weapon.imageUrl
        entity.remoteImageUrl = weapon.remoteImageUrl
        entity.hoyolabEntryId = weapon.hoyolabEntryId
        entity.hoyolabIconUrl = weapon.hoyolabIconUrl
        entity.hoyolabPageVersion = weapon.hoyolabPageVersion
        entity.hoyolabDataVersion = weapon.hoyolabDataVersion
        entity.fullImageUrl = weapon.fullImageUrl
        entity.galleryImagesJson = objectMapper.writeValueAsString(weapon.galleryImages)
        entity.hoyolabAscensionJson = objectMapper.writeValueAsString(weapon.hoyolabAscension)
        entity.ascensionCostsJson = objectMapper.writeValueAsString(weapon.ascensionCosts)

        return toDefinition(repository.save(entity)) ?: weapon.copy(key = normalizedKey)
    }

    private fun toDefinition(entity: GameWeapon): WeaponDefinition? = runCatching {
        WeaponDefinition(
            key = entity.key,
            name = entity.name,
            rarity = entity.rarity,
            weaponType = entity.weaponType,
            secondaryStatType = entity.secondaryStatType,
            baseAttack = entity.baseAttack,
            baseSecondaryStat = entity.baseSecondaryStat,
            description = entity.description,
            region = entity.region,
            obtainMethod = entity.obtainMethod,
            releaseVersion = entity.releaseVersion,
            passiveName = entity.passiveName,
            passiveDescription = entity.passiveDescription,
            story = entity.story,
            imageUrl = entity.imageUrl,
            remoteImageUrl = entity.remoteImageUrl,
            hoyolabEntryId = entity.hoyolabEntryId,
            hoyolabIconUrl = entity.hoyolabIconUrl,
            hoyolabPageVersion = entity.hoyolabPageVersion,
            hoyolabDataVersion = entity.hoyolabDataVersion ?: 0,
            fullImageUrl = entity.fullImageUrl,
            galleryImages = objectMapper.readValue(
                entity.galleryImagesJson?.takeIf(String::isNotBlank) ?: "[]",
                GALLERY_TYPE,
            ),
            hoyolabAscension = objectMapper.readValue(
                entity.hoyolabAscensionJson?.takeIf(String::isNotBlank) ?: "[]",
                HOYOLAB_ASCENSION_TYPE,
            ),
            ascensionCosts = objectMapper.readValue(entity.ascensionCostsJson, COSTS_TYPE),
        )
    }.onFailure {
        logger.warn("Stored weapon data for '{}' is invalid", entity.key, it)
    }.getOrNull()

    companion object {
        private val COSTS_TYPE = object : TypeReference<Map<Int, List<MaterialCost>>>() {}
        private val GALLERY_TYPE = object : TypeReference<List<WeaponGalleryImage>>() {}
        private val HOYOLAB_ASCENSION_TYPE =
            object : TypeReference<List<WeaponHoyolabAscension>>() {}
    }
}
