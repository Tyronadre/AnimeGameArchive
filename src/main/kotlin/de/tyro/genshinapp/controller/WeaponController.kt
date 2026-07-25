package de.tyro.genshinapp.controller

import de.tyro.genshinapp.model.GoodKeyNormalizer
import de.tyro.genshinapp.model.PlayerWeapon
import de.tyro.genshinapp.model.TravelerIdentity
import de.tyro.genshinapp.security.AppUserPrincipal
import de.tyro.genshinapp.service.CharacterCatalogService
import de.tyro.genshinapp.service.HoyolabWeaponGalleryService
import de.tyro.genshinapp.service.OptimizerCombatStatService
import de.tyro.genshinapp.service.PlayerSnapshotStore
import de.tyro.genshinapp.service.WeaponCatalogService
import de.tyro.genshinapp.service.WeaponDataService
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.server.ResponseStatusException

@Controller
class WeaponController(
    private val weaponCatalogService: WeaponCatalogService,
    private val weaponDataService: WeaponDataService,
    private val snapshotStore: PlayerSnapshotStore,
    private val characterCatalogService: CharacterCatalogService,
    private val hoyolabWeaponGalleryService: HoyolabWeaponGalleryService,
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
        val ownedCopies = snapshot?.weapons.orEmpty()
            .filter { GoodKeyNormalizer.normalize(it.key) == weaponKey }
            .sortedWith(
                compareByDescending<PlayerWeapon> { it.level }
                    .thenByDescending { it.refinement },
            )
            .map { weapon ->
                val owner = weapon.location?.let(characterCatalogService::findCharacter)
                OwnedWeaponCopy(
                    level = weapon.level,
                    ascension = weapon.ascension,
                    refinement = weapon.refinement,
                    locked = weapon.locked,
                    ownerName = owner?.name ?: weapon.location?.let(GoodKeyNormalizer::humanize),
                    ownerKey = owner?.key?.let(TravelerIdentity::canonicalCharacterKey),
                )
            }
        val secondaryStatKey = OptimizerCombatStatService.combatStatKey(
            definition?.secondaryStatType,
        )
        val originalImageIndex = definition?.galleryImages
            ?.indexOfFirst { image ->
                image.label.equals("Original", ignoreCase = true) ||
                    image.description?.contains("before", ignoreCase = true) == true
            }
            ?.takeIf { it >= 0 }

        model.addAttribute("weaponKey", weaponKey)
        model.addAttribute("weaponName", weaponName)
        model.addAttribute("weaponIcon", weaponCatalogService.imageUrl(weaponKey))
        model.addAttribute("weaponFullImage", weaponCatalogService.fullImageUrl(weaponKey))
        model.addAttribute(
            "weaponOriginalImage",
            originalImageIndex?.let { weaponCatalogService.galleryImageUrl(weaponKey, it) },
        )
        model.addAttribute("weaponDefinition", definition)
        model.addAttribute("secondaryStatKey", secondaryStatKey)
        model.addAttribute(
            "secondaryStatName",
            secondaryStatKey?.let(GoodKeyNormalizer::statName),
        )
        model.addAttribute("ownedCopies", ownedCopies)
        model.addAttribute("owned", ownedCopies.isNotEmpty())
        model.addAttribute("inventoryAvailable", snapshot != null)
        return "weapon"
    }
}

data class OwnedWeaponCopy(
    val level: Int,
    val ascension: Int,
    val refinement: Int,
    val locked: Boolean,
    val ownerName: String?,
    val ownerKey: String?,
)
