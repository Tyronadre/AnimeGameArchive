package de.tyro.genshinapp.service.damage.v2

import org.springframework.core.io.Resource
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.reflect.KProperty

/** Source overrides are enabled only by the ui-dev editor configuration. */
object DamageFormulaResourcesV2 {
    @Volatile var sourceRoot: Path? = null
    val revision = AtomicLong()
    private val preview = ThreadLocal<Map<String, String>>()
    fun resource(path: String): Resource {
        preview.get()?.get(path)?.let { content -> return object : ByteArrayResource(content.toByteArray(Charsets.UTF_8)) {
            override fun getFilename() = path.substringAfterLast('/')
            override fun getDescription() = path
        } }
        val root = sourceRoot
        return if (root != null && path.startsWith("data/damage-formulas-v2/"))
            FileSystemResource(root.resolve(path.removePrefix("data/damage-formulas-v2/"))) else ClassPathResource(path)
    }
    fun resources(pattern: String): Array<Resource> {
        val root = sourceRoot
        val resolved = if (root == null) pattern else root.toUri().toString() + pattern.substringAfter("data/damage-formulas-v2/")
        return PathMatchingResourcePatternResolver().getResources(resolved).map { item ->
            if (root == null) item else resource("data/damage-formulas-v2/" + root.relativize(item.file.toPath()).toString().replace('\\', '/'))
        }.toTypedArray()
    }
    fun <T> preview(path: String, content: String, action: () -> T): T {
        preview.set(mapOf("data/damage-formulas-v2/$path" to content))
        try { return action() } finally { preview.remove() }
    }
}

class ReloadableFormulaV2<T>(private val load: () -> T) {
    private var version = -1L
    private var cached: T? = null
    @Synchronized operator fun getValue(owner: Any?, property: KProperty<*>): T {
        val current = DamageFormulaResourcesV2.revision.get()
        if (version != current) { cached = load(); version = current }
        @Suppress("UNCHECKED_CAST")
        return cached as T
    }
}
fun <T> reloadableFormulaV2(load: () -> T) = ReloadableFormulaV2(load)
