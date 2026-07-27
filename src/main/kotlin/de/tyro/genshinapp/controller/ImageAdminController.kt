package de.tyro.genshinapp.controller

import de.tyro.genshinapp.configuration.LocalizedMessages
import de.tyro.genshinapp.model.CharacterImageType
import de.tyro.genshinapp.model.TravelerIdentity
import de.tyro.genshinapp.service.CharacterCatalogService
import de.tyro.genshinapp.service.DynamicContentLoader
import de.tyro.genshinapp.service.EditableImageLink
import de.tyro.genshinapp.service.ImageUrlRegistry
import de.tyro.genshinapp.service.MaterialCatalogService
import de.tyro.genshinapp.service.WeaponCatalogService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
@RequestMapping("/admin/images")
class ImageAdminController(
    private val catalogService: CharacterCatalogService,
    private val materialCatalogService: MaterialCatalogService,
    private val contentLoader: DynamicContentLoader,
    private val imageUrlRegistry: ImageUrlRegistry,
    private val weaponCatalogService: WeaponCatalogService,
    private val messages: LocalizedMessages,
) {
    @GetMapping
    fun images(
        @RequestParam(defaultValue = "") query: String,
        @RequestParam(defaultValue = "all") type: String,
        @RequestParam(defaultValue = "all") state: String,
        model: Model,
    ): String {
        val normalizedQuery = query.trim()
        val allRows = imageRows()
        val rows = allRows
            .filter { type == "all" || it.type == type }
            .filter { state == "all" || it.state.name.equals(state, ignoreCase = true) }
            .filter {
                normalizedQuery.isBlank() ||
                    it.name.contains(normalizedQuery, ignoreCase = true) ||
                    it.key.contains(normalizedQuery, ignoreCase = true)
            }

        model.addAttribute("images", rows)
        model.addAttribute("query", normalizedQuery)
        model.addAttribute("selectedType", type)
        model.addAttribute("selectedState", state)
        model.addAttribute("registryFile", imageUrlRegistry.filePath().toString())
        model.addAttribute("totalImages", allRows.size)
        return "admin-images"
    }

    @PostMapping("/characters/{key}/{type}")
    fun updateCharacter(
        @PathVariable key: String,
        @PathVariable type: String,
        @RequestParam(defaultValue = "") url: String,
        @RequestParam(defaultValue = "save") action: String,
        redirectAttributes: RedirectAttributes,
    ): String {
        val character = catalogService.findMediaCharacter(key)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val imageType = CharacterImageType.fromKey(type)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val result = if (action == "reset") {
            contentLoader.resetCharacterImageUrl(character, imageType)
            DynamicContentLoader.ImageUpdateResult(
                true,
                "images.update.characterReset",
                arrayOf(character.name, imageType.label),
            )
        } else {
            contentLoader.updateCharacterImageUrl(character, imageType, url)
        }
        addResult(result, redirectAttributes)
        return "redirect:/admin/images"
    }

    @PostMapping("/materials/{id}")
    fun updateMaterial(
        @PathVariable id: Int,
        @RequestParam(defaultValue = "") url: String,
        @RequestParam(defaultValue = "save") action: String,
        redirectAttributes: RedirectAttributes,
    ): String {
        val material = materialCatalogService.findMaterial(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val result = if (action == "reset") {
            contentLoader.resetMaterialImageUrl(material)
            DynamicContentLoader.ImageUpdateResult(
                true,
                "images.update.materialReset",
                arrayOf(material.name),
            )
        } else {
            contentLoader.updateMaterialImageUrl(material, url)
        }
        addResult(result, redirectAttributes)
        return "redirect:/admin/images"
    }

    @PostMapping("/weapons/{key}")
    fun updateWeapon(
        @PathVariable key: String,
        @RequestParam(defaultValue = "") url: String,
        @RequestParam(defaultValue = "save") action: String,
        redirectAttributes: RedirectAttributes,
    ): String {
        val weapon = weaponCatalogService.find(key)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val result = if (action == "reset") {
            contentLoader.resetWeaponImageUrl(weapon.key)
            DynamicContentLoader.ImageUpdateResult(
                true,
                "images.update.weaponReset",
                arrayOf(weapon.name),
            )
        } else {
            contentLoader.updateWeaponImageUrl(weapon.key, weapon.name, url)
        }
        addResult(result, redirectAttributes)
        return "redirect:/admin/images"
    }

    @PostMapping("/weapons/{key}/full")
    fun updateWeaponFull(
        @PathVariable key: String,
        @RequestParam(defaultValue = "") url: String,
        @RequestParam(defaultValue = "save") action: String,
        redirectAttributes: RedirectAttributes,
    ): String {
        val weapon = weaponCatalogService.find(key)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val result = if (action == "reset") {
            contentLoader.resetWeaponFullImageUrl(weapon.key)
            DynamicContentLoader.ImageUpdateResult(
                true,
                "images.update.weaponFullReset",
                arrayOf(weapon.name),
            )
        } else {
            contentLoader.updateWeaponFullImageUrl(weapon.key, weapon.name, url)
        }
        addResult(result, redirectAttributes)
        return "redirect:/admin/images"
    }

    @PostMapping("/characters/{characterKey}/talents/{talentKey}")
    fun updateTalent(
        @PathVariable characterKey: String,
        @PathVariable talentKey: String,
        @RequestParam(defaultValue = "") url: String,
        @RequestParam(defaultValue = "save") action: String,
        redirectAttributes: RedirectAttributes,
    ): String {
        val character = catalogService.findMediaCharacter(characterKey)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val talent = character.talents.firstOrNull { it.key == talentKey.lowercase() }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val result = if (action == "reset") {
            contentLoader.resetTalentImageUrl(character, talent)
            DynamicContentLoader.ImageUpdateResult(
                true,
                "images.update.talentReset",
                arrayOf(talent.name, character.name),
            )
        } else {
            contentLoader.updateTalentImageUrl(character, talent, url)
        }
        addResult(result, redirectAttributes)
        return "redirect:/admin/images"
    }

    private fun imageRows(): List<AdminImageRow> {
        val imageCharacters = catalogService.getCharacters()
            .filterNot { it.key == TravelerIdentity.KEY } +
            catalogService.travelerAppearanceCharacters()
        val characterRows = imageCharacters.flatMap { character ->
            CharacterImageType.entries.map { imageType ->
                val link = imageUrlRegistry.characterLink(character.imageResourceKey, imageType)
                    ?: EditableImageLink(
                        "${character.name} ${imageType.label}",
                        character.remoteImageUrl(imageType).orEmpty(),
                    )
                val state = contentLoader.characterImageState(character, imageType)
                AdminImageRow(
                    type = "character",
                    typeLabel = messages.get("images.type.character", imageType.label),
                    key = "${character.imageResourceKey}:${imageType.key}",
                    name = character.name,
                    currentUrl = link.effectiveUrl.orEmpty(),
                    defaultUrl = link.defaultUrl,
                    hasOverride = link.hasOverride,
                    state = state,
                    previewUrl = character.imageUrls[imageType].takeIf { state.hasPreview },
                    updatePath = "/admin/images/characters/" +
                        "${character.imageResourceKey}/${imageType.key}",
                )
            }
        }
        val materialRows = materialCatalogService.getMaterials().map { material ->
            val link = imageUrlRegistry.materialLink(material.id)
                ?: EditableImageLink(material.name)
            val state = contentLoader.materialImageState(material)
            AdminImageRow(
                type = "material",
                typeLabel = messages.get("images.type.material"),
                key = material.id.toString(),
                name = material.name,
                currentUrl = link.effectiveUrl.orEmpty(),
                defaultUrl = link.defaultUrl,
                hasOverride = link.hasOverride,
                state = state,
                previewUrl = materialCatalogService.materialImageUrl(material.id)
                    .takeIf { state.hasPreview },
                updatePath = "/admin/images/materials/${material.id}",
            )
        }
        val talentCharacters = catalogService.getCharacters()
            .filterNot { it.key == TravelerIdentity.KEY } +
            catalogService.travelerElementCharacters()
        val talentRows = talentCharacters.flatMap { character ->
            character.talents.map { talent ->
                val link = imageUrlRegistry.talentLink(character.talentResourceKey, talent.key)
                    ?: EditableImageLink("${character.name} - ${talent.name}")
                val state = contentLoader.talentImageState(character, talent)
                AdminImageRow(
                    type = "talent",
                    typeLabel = messages.get("images.type.talent", character.name),
                    key = "${character.talentResourceKey}:${talent.key}",
                    name = talent.name,
                    currentUrl = link.effectiveUrl.orEmpty(),
                    defaultUrl = link.defaultUrl,
                    hasOverride = link.hasOverride,
                    state = state,
                    previewUrl = (
                        "/media/characters/" +
                            "${character.talentResourceKey}/talents/${talent.key}"
                        ).takeIf { state.hasPreview },
                    updatePath = "/admin/images/characters/" +
                        "${character.talentResourceKey}/talents/${talent.key}",
                )
            }
        }
        val weaponRows = weaponCatalogService.getWeapons().map { weapon ->
            val link = imageUrlRegistry.weaponLink(weapon.key)
                ?: EditableImageLink(weapon.name, weapon.remoteImageUrl.orEmpty())
            val state = contentLoader.weaponImageState(weapon.key, weapon.name)
            AdminImageRow(
                type = "weapon",
                typeLabel = messages.get("images.type.weapon"),
                key = weapon.key,
                name = weapon.name,
                currentUrl = link.effectiveUrl.orEmpty(),
                defaultUrl = link.defaultUrl,
                hasOverride = link.hasOverride,
                state = state,
                previewUrl = weaponCatalogService.imageUrl(weapon.key)
                    .takeIf { state.hasPreview },
                updatePath = "/admin/images/weapons/${weapon.key}",
            )
        }
        val weaponFullRows = weaponCatalogService.getWeapons().map { weapon ->
            val link = imageUrlRegistry.weaponFullLink(weapon.key)
                ?: EditableImageLink("${weapon.name} full view", weapon.fullImageUrl.orEmpty())
            val state = contentLoader.weaponFullImageState(weapon.key, weapon.fullImageUrl)
            AdminImageRow(
                type = "weapon-full",
                typeLabel = messages.get("images.type.weaponFull"),
                key = weapon.key,
                name = weapon.name,
                currentUrl = link.effectiveUrl.orEmpty(),
                defaultUrl = link.defaultUrl,
                hasOverride = link.hasOverride,
                state = state,
                previewUrl = weaponCatalogService.fullImageUrl(weapon.key)
                    .takeIf { state.hasPreview },
                updatePath = "/admin/images/weapons/${weapon.key}/full",
            )
        }
        return characterRows + talentRows + weaponRows + weaponFullRows + materialRows
    }

    private fun addResult(
        result: DynamicContentLoader.ImageUpdateResult,
        redirectAttributes: RedirectAttributes,
    ) {
        redirectAttributes.addFlashAttribute(
            if (result.successful) "successMessage" else "errorMessage",
            messages.get(result.messageKey, *result.messageArguments),
        )
    }
}

data class AdminImageRow(
    val type: String,
    val typeLabel: String,
    val key: String,
    val name: String,
    val currentUrl: String,
    val defaultUrl: String,
    val hasOverride: Boolean,
    val state: DynamicContentLoader.ImageState,
    val previewUrl: String?,
    val updatePath: String,
)

private val DynamicContentLoader.ImageState.hasPreview: Boolean
    get() = this == DynamicContentLoader.ImageState.BUNDLED ||
        this == DynamicContentLoader.ImageState.CACHED
