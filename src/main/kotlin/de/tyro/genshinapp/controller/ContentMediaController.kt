package de.tyro.genshinapp.controller

import de.tyro.genshinapp.model.CharacterImageType
import de.tyro.genshinapp.model.CharacterTalentKind
import de.tyro.genshinapp.service.ArtifactCatalogService
import de.tyro.genshinapp.service.CharacterCatalogService
import de.tyro.genshinapp.service.DynamicContentLoader
import de.tyro.genshinapp.service.WeaponCatalogService
import de.tyro.genshinapp.service.WeaponDataService
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Duration

@RestController
@RequestMapping("/media")
class ContentMediaController(
    private val catalogService: CharacterCatalogService,
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
        val character = catalogService.findCharacter(key)
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
        val character = catalogService.findCharacter(characterKey)
            ?: return ResponseEntity.notFound().build()
        val talent = character.talents.firstOrNull { it.key == talentKey.lowercase() }
            ?: return ResponseEntity.notFound().build()
        val normalAttack = talent.kind == CharacterTalentKind.NORMAL_ATTACK
        val image = contentLoader.loadTalentImage(
            character.key,
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
        val material = catalogService.findMaterial(id)
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

    @GetMapping("/weapons/{key}")
    fun weaponImage(@PathVariable key: String): ResponseEntity<ByteArray> {
        val weaponName = weaponCatalogService.weaponName(key)
            ?: return ResponseEntity.notFound().build()
        val image = contentLoader.loadWeaponImage(key, weaponName)
            ?: return ResponseEntity.notFound().build()
        return imageResponse(image)
    }

    private fun imageResponse(image: DynamicContentLoader.LoadedImage): ResponseEntity<ByteArray> =
        ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(image.contentType))
            .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
            .contentLength(image.bytes.size.toLong())
            .body(image.bytes)
}
