package de.tyro.genshinapp.service

import de.tyro.genshinapp.configuration.GenshinContentProperties
import de.tyro.genshinapp.model.CharacterImageType
import org.springframework.stereotype.Service
import org.springframework.web.util.UriUtils
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer

@Service
class FandomImageUrlResolver(
    private val properties: GenshinContentProperties,
) {
    fun characterImageUrl(
        characterName: String,
        imageType: CharacterImageType,
    ): String = hashedImageUrl(imageType.fileName(characterName))

    fun itemImageUrl(itemName: String): String {
        val normalizedName = itemName.trim().replace(WHITESPACE, "_").replace(":", "")
        return hashedImageUrl("Item_${normalizedName}.png")
    }

    fun weaponImageUrl(weaponName: String): String {
        val normalizedName = weaponName.trim().replace(WHITESPACE, "_")
        return hashedImageUrl("Weapon_${normalizedName}.png")
    }

    fun talentImageUrl(talentName: String): String {
        val normalizedName = talentName
            .replace(TALENT_SPECIAL_CHARACTERS, "")
            .trim()
            .replace(WHITESPACE, "_")
        return hashedImageUrl("Talent_${normalizedName}.png")
    }

    fun normalAttackImageUrl(weaponName: String, elementName: String): String {
        val normalizedName = "$weaponName $elementName".trim().replace(WHITESPACE, "_")
        return hashedImageUrl("$normalizedName.png")
    }

    private fun hashedImageUrl(rawFileName: String): String {
        val fileName = Normalizer.normalize(rawFileName, Normalizer.Form.NFC)
        val hash = MessageDigest.getInstance("MD5")
            .digest(fileName.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        val encodedFileName = UriUtils.encodePathSegment(fileName, StandardCharsets.UTF_8)
            .replace("'", "%27")

        return properties.fandomImageBaseUrl.trimEnd('/') +
            "/${hash[0]}/${hash.substring(0, 2)}/$encodedFileName"
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")
        private val TALENT_SPECIAL_CHARACTERS = Regex("[^\\p{L}\\p{N}\\s]")
    }
}
