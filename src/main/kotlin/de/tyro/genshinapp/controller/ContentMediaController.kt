package de.tyro.genshinapp.controller

import de.tyro.genshinapp.model.CharacterImageType
import de.tyro.genshinapp.model.CharacterTalentKind
import de.tyro.genshinapp.model.WeaponImageType
import de.tyro.genshinapp.service.ArtifactCatalogService
import de.tyro.genshinapp.service.AttributeIcon
import de.tyro.genshinapp.service.CharacterCatalogService
import de.tyro.genshinapp.service.DynamicContentLoader
import de.tyro.genshinapp.service.MaterialCatalogService
import de.tyro.genshinapp.service.WeaponCatalogService
import de.tyro.genshinapp.service.WeaponDataService
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/media")
class ContentMediaController(
    private val catalogService: CharacterCatalogService,
    private val materialCatalogService: MaterialCatalogService,
    private val artifactCatalogService: ArtifactCatalogService,
    private val weaponCatalogService: WeaponCatalogService,
    private val weaponDataService: WeaponDataService,
    private val contentLoader: DynamicContentLoader,
) {
    @GetMapping("/characters/{key}/{type}")
    fun characterImage(
        @PathVariable key: String,
        @PathVariable type: String,
    ): ResponseEntity<ByteArray> {
        val character = catalogService.findMediaCharacter(key)
            ?: return ResponseEntity.notFound().build()
        val imageType = CharacterImageType.fromKey(type)
            ?: return ResponseEntity.notFound().build()
        val image = contentLoader.loadCharacterImage(character, imageType)
            ?: return ResponseEntity.notFound().build()
        return imageResponse(image)
    }

    @GetMapping("/characters/{characterKey}/talents/{talentKey}")
    fun talentImage(
        @PathVariable characterKey: String,
        @PathVariable talentKey: String,
    ): ResponseEntity<ByteArray> {
        val character = catalogService.findMediaCharacter(characterKey)
            ?: return ResponseEntity.notFound().build()
        val talent = character.talents.firstOrNull { it.key == talentKey.lowercase() }
            ?: return ResponseEntity.notFound().build()
        val normalAttack = talent.kind == CharacterTalentKind.NORMAL_ATTACK
        val image = contentLoader.loadTalentImage(
            character.talentResourceKey,
            talent.key,
            talent.name,
            normalAttackWeapon = character.weapon.takeIf { normalAttack },
            normalAttackElement = character.element.takeIf { normalAttack },
        )
            ?: return ResponseEntity.notFound().build()
        return imageResponse(image)
    }

    @GetMapping("/materials/{id}")
    fun materialImage(@PathVariable id: Int): ResponseEntity<ByteArray> {
        val material = materialCatalogService.findMaterial(id)
            ?: weaponDataService.findKnownMaterial(id)
            ?: return ResponseEntity.notFound().build()
        val image = contentLoader.loadMaterialImage(material.id, material.name)
            ?: return ResponseEntity.notFound().build()
        return imageResponse(image)
    }

    @GetMapping("/artifacts/{setKey}/{slotKey}")
    fun artifactImage(
        @PathVariable setKey: String,
        @PathVariable slotKey: String,
    ): ResponseEntity<ByteArray> {
        val pieceName = artifactCatalogService.pieceName(setKey, slotKey)
            ?: return ResponseEntity.notFound().build()
        val image = contentLoader.loadArtifactImage(setKey, slotKey, pieceName)
            ?: return ResponseEntity.notFound().build()
        return imageResponse(image)
    }

    @GetMapping("/attributes/{key}")
    fun attributeImage(@PathVariable key: String): ResponseEntity<ByteArray> {
        val attributeIcon = AttributeIcon.fromKey(key)
            ?: return ResponseEntity.notFound().build()
        val image = contentLoader.loadAttributeImage(attributeIcon)
            ?: return ResponseEntity.notFound().build()
        return imageResponse(image)
    }

    @GetMapping("/weapons/{key}/{type}")
    fun weaponImage(
        @PathVariable key: String,
        @PathVariable type: String,
    ): ResponseEntity<ByteArray> {
        val weapon = weaponCatalogService.find(key)
            ?: return ResponseEntity.notFound().build()
        val imageType = WeaponImageType.fromKey(type)
            ?: return ResponseEntity.notFound().build()
        val image = contentLoader.loadWeaponImage(
            weapon.key,
            weapon.name,
            imageType,
            weapon.remoteImageUrl(imageType),
        ) ?: return ResponseEntity.notFound().build()
        return imageResponse(image)
    }

    private fun imageResponse(image: DynamicContentLoader.LoadedImage): ResponseEntity<ByteArray> =
        ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(image.contentType))
            .cacheControl(CacheControl.noCache())
            .contentLength(image.bytes.size.toLong())
            .body(image.bytes)
}
