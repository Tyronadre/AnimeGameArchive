package de.tyro.genshinapp.controller

import com.fasterxml.jackson.databind.ObjectMapper
import de.tyro.genshinapp.service.presentation.CharacterStageCatalog
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration

@RestController
class CharacterStageController(private val catalog: CharacterStageCatalog, objectMapper: ObjectMapper) {
    private val motionFrames = ClassPathResource("static/character-stage/motions/provenance.json").inputStream.use { stream ->
        objectMapper.readTree(stream).fields().asSequence().associate { it.key to it.value["frames"].asInt() }
    }

    @GetMapping("/api/character-stages/{key}")
    fun descriptor(@PathVariable key: String, request: HttpServletRequest): Map<String, Any> {
        val entry = catalog.find(key) ?: return mapOf("available" to false)
        val model = entry.model.split('/').joinToString("/") { URLEncoder.encode(it, StandardCharsets.UTF_8).replace("+", "%20") }
        return mapOf(
            "available" to true,
            "modelUrl" to "${request.contextPath}/media/character-stages/${entry.key}/files/$model",
            "motionUrl" to "${request.contextPath}/character-stage/motions/${entry.profile}-idle.vmd",
            "motionFrames" to (motionFrames[entry.profile] ?: 66),
            "variant" to entry.variant,
        )
    }

    @GetMapping("/media/character-stages/{key}/files/{*asset}")
    fun asset(@PathVariable key: String, @PathVariable asset: String): ResponseEntity<Resource> {
        val resource = catalog.asset(key, asset) ?: return ResponseEntity.notFound().build()
        val type = when (asset.substringAfterLast('.').lowercase()) {
            "png" -> MediaType.IMAGE_PNG
            "jpg", "jpeg" -> MediaType.IMAGE_JPEG
            "bmp", "sph", "spa" -> MediaType.parseMediaType("image/bmp")
            else -> MediaType.APPLICATION_OCTET_STREAM
        }
        return ResponseEntity.ok().contentType(type)
            .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
            .lastModified(resource.lastModified()).contentLength(resource.contentLength()).body(resource)
    }
}
