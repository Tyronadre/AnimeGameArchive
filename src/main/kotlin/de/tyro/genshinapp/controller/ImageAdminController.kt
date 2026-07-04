package de.tyro.genshinapp.controller

import de.tyro.genshinapp.configuration.LocalizedMessages
import de.tyro.genshinapp.model.CharacterImageType
import de.tyro.genshinapp.service.CharacterCatalogService
import de.tyro.genshinapp.service.DynamicContentLoader
import de.tyro.genshinapp.service.EditableImageLink
import de.tyro.genshinapp.service.ImageUrlRegistry
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
    private val contentLoader: DynamicContentLoader,
    private val imageUrlRegistry: ImageUrlRegistry,
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
        val character = catalogService.findCharacter(key)
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
        val material = catalogService.findMaterial(id)
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

    private fun imageRows(): List<AdminImageRow> {
        val characterRows = catalogService.getCharacters().flatMap { character ->
            CharacterImageType.entries.map { imageType ->
                val link = imageUrlRegistry.characterLink(character.key, imageType)
                    ?: EditableImageLink(
                        "${character.name} ${imageType.label}",
                        character.remoteImageUrl(imageType).orEmpty(),
                    )
                val state = contentLoader.characterImageState(character, imageType)
                AdminImageRow(
                    type = "character",
                    typeLabel = messages.get("images.type.character", imageType.label),
                    key = "${character.key}:${imageType.key}",
                    name = character.name,
                    currentUrl = link.effectiveUrl.orEmpty(),
                    defaultUrl = link.defaultUrl,
                    hasOverride = link.hasOverride,
                    state = state,
                    previewUrl = character.imageUrls[imageType].takeIf { state.hasPreview },
                    updatePath = "/admin/images/characters/${character.key}/${imageType.key}",
                )
            }
        }
        val materialRows = catalogService.getMaterials().map { material ->
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
                previewUrl = catalogService.materialImageUrl(material.id)
                    .takeIf { state.hasPreview },
                updatePath = "/admin/images/materials/${material.id}",
            )
        }
        return characterRows + materialRows
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
