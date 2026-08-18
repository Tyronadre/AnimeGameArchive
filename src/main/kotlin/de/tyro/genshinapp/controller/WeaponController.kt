package de.tyro.genshinapp.controller

import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.model.PlayerWeapon
import de.tyro.genshinapp.security.AppUserPrincipal
import de.tyro.genshinapp.service.CharacterCatalogService
import de.tyro.genshinapp.service.AttributeIcon
import de.tyro.genshinapp.service.HoyolabWeaponGalleryService
import de.tyro.genshinapp.service.MaterialCatalogService
import de.tyro.genshinapp.service.OptimizerCombatStatService
import de.tyro.genshinapp.service.PlayerSnapshotStore
import de.tyro.genshinapp.service.WeaponCatalogService
import de.tyro.genshinapp.service.WeaponDataService
import de.tyro.genshinapp.service.WeaponDefinition
import de.tyro.genshinapp.service.WeaponPlanningService
import de.tyro.genshinapp.service.PlayerWeaponService
import de.tyro.genshinapp.service.StoredPlayerWeapon
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.server.ResponseStatusException

@Controller
class WeaponController(
    private val weaponCatalogService: WeaponCatalogService,
    private val weaponDataService: WeaponDataService,
    private val hoyolabWeaponGalleryService: HoyolabWeaponGalleryService,
    private val snapshotStore: PlayerSnapshotStore,
    private val characterCatalogService: CharacterCatalogService,
    private val materialCatalogService: MaterialCatalogService,
    private val weaponPlanningService: WeaponPlanningService,
    private val playerWeaponService: PlayerWeaponService,
) {
    @GetMapping("/weapons/{key}")
    fun weapon(
        @PathVariable key: String,
        @AuthenticationPrincipal principal: AppUserPrincipal,
        model: Model,
    ): String {
        val weaponKey = GoodKeyNormalizer.normalize(key)
        val weaponName = weaponCatalogService.officialName(weaponKey)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Weapon not found")
        weaponDataService.find(weaponKey)
        val definition = hoyolabWeaponGalleryService.enrich(weaponKey)
        val snapshot = snapshotStore.current(principal.id)
        val ownedCopies = if (snapshot == null) emptyList() else {
            playerWeaponService.findAllStored(principal.id)
        }.filter { GoodKeyNormalizer.normalize(it.weapon.key) == weaponKey }
            .sortedWith(
                compareByDescending<StoredPlayerWeapon> {
                    it.weapon.level
                }.thenByDescending { it.weapon.refinement },
            )
            .mapIndexed { index, stored ->
                toOwnedCopy(index, stored.weapon, definition, stored.id)
            }
        val secondaryStatKey = OptimizerCombatStatService.combatStatKey(
            definition?.secondaryStatType,
        )
        val maxStats = definition?.hoyolabAscension?.maxByOrNull { it.level }
        model.addAttribute("weaponKey", weaponKey)
        model.addAttribute("weaponName", weaponName)
        model.addAttribute("weaponIcon", weaponCatalogService.imageUrl(weaponKey))
        model.addAttribute(
            "weaponTypeIcon",
            weaponCatalogService.weaponTypeImageUrl(definition?.weaponType),
        )
        model.addAttribute("attackStatIcon", AttributeIcon.ATTACK.mediaUrl)
        model.addAttribute(
            "secondaryStatIcon",
            AttributeIcon.fromCombatStatKey(secondaryStatKey)?.mediaUrl,
        )
        model.addAttribute("weaponFullImage", weaponCatalogService.fullImageUrl(weaponKey))
        model.addAttribute(
            "weaponOriginalImage",
            weaponCatalogService.unascendedImageUrl(weaponKey),
        )
        model.addAttribute("weaponDefinition", definition)
        model.addAttribute("weaponMaxStats", maxStats)
        model.addAttribute("secondaryStatKey", secondaryStatKey)
        model.addAttribute(
            "secondaryStatName",
            secondaryStatKey?.let(GoodKeyNormalizer::statName),
        )
        model.addAttribute("ownedWeaponCopies", ownedCopies)
        model.addAttribute("inventoryAvailable", snapshot != null)
        model.addAttribute(
            "mysticEnhancementOreImage",
            materialCatalogService.materialImageUrl(MYSTIC_ENHANCEMENT_ORE_ID),
        )
        return "weapon"
    }

    @PostMapping("/weapons/{key}/copies/{copyId}/level")
    @ResponseBody
    fun updateCopyLevel(
        @PathVariable key: String,
        @PathVariable copyId: Long,
        @RequestParam level: Int,
        @RequestParam(required = false) ascension: Int?,
        @AuthenticationPrincipal principal: AppUserPrincipal,
    ): OwnedWeaponCopyView {
        val weaponKey = GoodKeyNormalizer.normalize(key)
        val definition = weaponCatalogService.find(weaponKey)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Weapon not found")
        val updated = try {
            playerWeaponService.updateLevel(
                principal.id,
                copyId,
                weaponKey,
                level,
                ascension,
            )
        } catch (_: NoSuchElementException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Weapon copy not found")
        } catch (exception: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, exception.message)
        }
        return toOwnedCopy(0, updated.weapon, definition, updated.id)
    }

    private fun toOwnedCopy(
        index: Int,
        weapon: PlayerWeapon,
        definition: WeaponDefinition?,
        storedId: Long? = null,
    ): OwnedWeaponCopyView {
        val owner = weapon.location?.let(characterCatalogService::findCharacter)
        val maxLevel = WeaponPlanningService.maxLevel(definition?.rarity ?: 5)
        val targetLevels = ASCENSION_CAPS.filter { it <= maxLevel }
        val currentAscension = weapon.ascension.coerceIn(0, targetLevels.lastIndex)
        return OwnedWeaponCopyView(
            id = storedId ?: -(index + 1).toLong(),
            editable = storedId != null,
            saveUrl = storedId?.let {
                "/weapons/${definition?.key ?: GoodKeyNormalizer.normalize(weapon.key)}/copies/$it/level"
            },
            ownerName = owner?.name
                ?: weapon.location?.let(GoodKeyNormalizer::humanize)
                ?: "",
            ownerIconUrl = owner?.iconImageUrl,
            level = weapon.level,
            ascension = currentAscension,
            refinement = weapon.refinement,
            locked = weapon.locked,
            targets = targetLevels.mapIndexed { targetAscension, targetLevel ->
                val enhancement = weaponPlanningService.calculateEnhancement(
                    rarity = definition?.rarity ?: 5,
                    currentLevel = weapon.level,
                    targetLevel = targetLevel,
                )
                OwnedWeaponTargetView(
                    ascension = targetAscension,
                    level = targetLevel,
                    reached = targetAscension <= currentAscension,
                    materials = definition?.let {
                        aggregateAscensionMaterials(it, currentAscension, targetAscension)
                    }.orEmpty(),
                    experience = enhancement.experience,
                    mysticEnhancementOre = enhancement.mysticEnhancementOre,
                )
            },
        )
    }

    private fun aggregateAscensionMaterials(
        definition: WeaponDefinition,
        currentAscension: Int,
        targetAscension: Int,
    ): List<OwnedWeaponMaterialView> {
        if (targetAscension <= currentAscension) return emptyList()
        val totals = linkedMapOf<String, OwnedWeaponMaterialView>()
        ((currentAscension + 1)..targetAscension)
            .flatMap { phase -> phaseMaterials(definition, phase) }
            .forEach { material ->
                val identity = material.identity
                val current = totals[identity]
                totals[identity] = if (current == null) {
                    material
                } else {
                    current.copy(amount = current.amount + material.amount)
                }
            }
        return totals.values.toList()
    }

    private fun phaseMaterials(
        definition: WeaponDefinition,
        phase: Int,
    ): List<OwnedWeaponMaterialView> = definition.ascensionCosts[phase]
        .orEmpty()
        .map { material ->
            OwnedWeaponMaterialView(
                identity = "material:${material.id}",
                name = material.name,
                amount = material.count,
                imageUrl = materialCatalogService.materialImageUrl(material.id),
                href = "/materials?materialId=${material.id}",
            )
        }

    private companion object {
        private const val MYSTIC_ENHANCEMENT_ORE_ID = 104013
        private val ASCENSION_CAPS = listOf(20, 40, 50, 60, 70, 80, 90)
    }
}

data class OwnedWeaponCopyView(
    val id: Long,
    val editable: Boolean,
    val saveUrl: String?,
    val ownerName: String,
    val ownerIconUrl: String?,
    val level: Int,
    val ascension: Int,
    val refinement: Int,
    val locked: Boolean,
    val targets: List<OwnedWeaponTargetView>,
)

data class OwnedWeaponTargetView(
    val ascension: Int,
    val level: Int,
    val reached: Boolean,
    val materials: List<OwnedWeaponMaterialView>,
    val experience: Long,
    val mysticEnhancementOre: Long,
)

data class OwnedWeaponMaterialView(
    val identity: String,
    val name: String,
    val amount: Long,
    val imageUrl: String?,
    val href: String?,
)
