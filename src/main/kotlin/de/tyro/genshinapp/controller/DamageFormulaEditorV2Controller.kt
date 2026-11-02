package de.tyro.genshinapp.controller

import com.fasterxml.jackson.databind.ObjectMapper
import de.tyro.genshinapp.model.PlayerArtifact
import de.tyro.genshinapp.model.PlayerWeapon
import de.tyro.genshinapp.service.damage.v2.*
import org.springframework.context.annotation.Profile
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.*
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.FileAlreadyExistsException
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import java.security.MessageDigest

data class FormulaEditorRequestV2(val path: String, val content: String, val revision: String = "")

@Controller
@Profile("ui-dev")
class DamageFormulaEditorV2Controller(
    private val mapper: ObjectMapper,
    @Value("\${genshin.formulas.source-directory:src/main/resources/data/damage-formulas-v2}")
    sourceDirectory: String = "src/main/resources/data/damage-formulas-v2",
) {
    private val root = Path.of(sourceDirectory).toAbsolutePath().normalize()
    init {
        check(Files.isDirectory(root)) { "The formula editor must run from the repository root" }
        DamageFormulaResourcesV2.sourceRoot = root
        DamageFormulaResourcesV2.revision.incrementAndGet()
    }

    @GetMapping("/damage-calculator-v2/editor")
    fun page() = "damage-formula-editor-v2"

    @GetMapping("/api/damage-formulas/v2/editor/damage-types")
    @ResponseBody
    fun damageTypes() = DamageTypeRegistryV2(mapper).definitions.values

    @GetMapping("/api/damage-formulas/v2/editor/standard-inputs")
    @ResponseBody
    fun standardInputs() = CharacterStandardInputsV3.elements.associateWith { CharacterStandardInputsV3.definitions(it).values }

    @GetMapping("/api/damage-formulas/v2/editor/files")
    @ResponseBody
    fun files(): List<String> = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".json") && it.toRealPath().startsWith(root.toRealPath()) }
            .map { root.relativize(it).toString().replace('\\', '/') }.sorted().toList()
    }

    @GetMapping("/api/damage-formulas/v2/editor/new-file-options")
    @ResponseBody
    fun newFileOptions(): List<Map<String, Any>> = PathMatchingResourcePatternResolver()
        .getResources("classpath*:data/characters/data/*.json").map { resource ->
            val character = resource.inputStream.use(mapper::readTree)
            val key = requireNotNull(resource.filename).removeSuffix(".json")
            mapOf("key" to key, "name" to character.path("name").asText(key),
                "element" to character.path("elementText").asText(), "weaponType" to character.path("weaponText").asText(),
                "characterDataResource" to "data/characters/data/$key.json",
                "exists" to Files.exists(root.resolve("$key.json")),
                "talents" to listOf("combat1", "combat2", "combat3").mapIndexed { index, talent ->
                    mapOf("key" to listOf("normal", "skill", "burst")[index],
                        "name" to character.path("talents").path(talent).path("name").asText(talent), "formulas" to emptyList<Any>())
                })
        }.distinctBy { it.getValue("key") }.sortedBy { it.getValue("name").toString() }

    @GetMapping("/api/damage-formulas/v2/editor/catalog")
    @ResponseBody
    fun catalog(): List<Map<String, Any>> = files().mapNotNull { name ->
        if ('/' in name) return@mapNotNull null
        val document = mapper.readTree(Files.readString(file(name)))
        if (!document.has("characterKey")) return@mapNotNull null
        val inputs = CharacterStandardInputsV3.complete(mapper.treeToValue(document, DamageFormulaProfileV2::class.java)).inputs
        val resource = document.path("characterDataResource").asText()
        val character = if (resource.matches(Regex("data/(characters/data|damage-talent-data-v2)/[a-z0-9_-]+\\.json"))) {
            org.springframework.core.io.ClassPathResource(resource).inputStream.use { mapper.readTree(it) }
        } else mapper.createObjectNode()
        val talents = character.path("talents").properties().filter { it.key.startsWith("combat") }.map { (key, talent) ->
            val labels = talent.path("attributes").path("labels").map { it.asText() }
            mapOf("key" to key, "name" to talent.path("name").asText(key), "parameters" to
                talent.path("attributes").path("parameters").properties().map { (parameter, values) ->
                    mapOf("key" to parameter, "label" to (labels.firstOrNull { it.contains("{$parameter:") }?.substringBefore('|') ?: parameter),
                        "values" to values.map { it.asDouble() })
                })
        }.toMutableList()
        document.path("talentParameters").properties().groupBy { it.key.substringBefore('.') }.forEach { (talent, parameters) ->
            talents += mapOf("key" to talent, "name" to talent, "parameters" to parameters.map {
                mapOf("key" to it.key.substringAfter('.'), "label" to it.key.substringAfter('.'), "values" to it.value.map { value -> value.asDouble() })
            })
        }
        mapOf("key" to document.path("key").asText(), "name" to document.path("name").asText(),
            "damageAvailable" to document.path("talents").any { it.path("formulas").size() > 0 },
            "file" to name, "inputs" to inputs, "talents" to talents)
    }

    private fun file(name: String): Path {
        val target = root.resolve(name).normalize()
        require(target.startsWith(root) && name.endsWith(".json") && Files.isRegularFile(target) && target.toRealPath().startsWith(root.toRealPath())) { "Choose an existing formula JSON file" }
        return target
    }
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun newFile(name: String): Path {
        require(name.matches(Regex("(?:weapons/|artifacts/)?[a-z0-9][a-z0-9_-]*\\.json"))) { "Use a character, weapon, or artifact key for the filename" }
        require(name !in setOf("damage-types.json", "team-rules.json", "elemental-reactions.json")) { "Shared catalogs already have their own editor entries" }
        val target = root.resolve(name).normalize()
        require(target.startsWith(root) && Files.isDirectory(target.parent) && target.parent.toRealPath().startsWith(root.toRealPath())) { "Choose a file inside the formula library" }
        if (Files.exists(target)) throw ResponseStatusException(HttpStatus.CONFLICT, "This file already exists. Open it from the library instead.")
        return target
    }

    @GetMapping("/api/damage-formulas/v2/editor/file")
    @ResponseBody
    fun read(@RequestParam path: String): Map<String, String> {
        val content = Files.readString(file(path))
        return mapOf("content" to content, "revision" to hash(content))
    }

    @PostMapping("/api/damage-formulas/v2/editor/validate")
    @ResponseBody
    fun validate(@RequestBody request: FormulaEditorRequestV2): Map<String, String> {
        val isNew = !Files.exists(root.resolve(request.path).normalize())
        if (isNew) newFile(request.path) else file(request.path)
        require(request.content.length <= 2_000_000) { "File is too large" }
        val reader = mapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        val tree: com.fasterxml.jackson.databind.JsonNode = reader.readTree(request.content)
        require(tree.isObject) { "The JSON root must be an object" }
        if (isNew) {
            val key = request.path.substringAfterLast('/').removeSuffix(".json")
            require(tree.path("key").asText() == key) { "The document key must match the filename" }
            if ('/' !in request.path) {
                require(tree.path("schemaVersion").asInt() == 3 && tree.path("characterKey").asText() == key) { "Character files require schema 3 and a matching characterKey" }
                require(tree.path("element").asText() in CharacterStandardInputsV3.elements) { "Choose a valid character element" }
            } else require(tree.path("schemaVersion").asInt() == 2) { "Equipment files require schema 2" }
        }
        DamageFormulaResourcesV2.preview(request.path, request.content) {
            val formulas = DamageFormulaServiceV2(mapper)
            formulas.validateDamageTypes()
            val teams = DamageFormulaTeamServiceV2(mapper)
            val artifacts = DamageFormulaArtifactServiceV2(mapper)
            val weapons = DamageFormulaWeaponServiceV2(mapper, artifacts)
            val artifactDefinitions = artifacts.allDefinitions()
            val weaponDefinitions = weapons.allDefinitions()
            val registry = CharacterFormulaRegistryV3(mapper)
            registry.definitions.keys.forEach(formulas::validateCharacter)
            registry.definitions.values.filter { it.talents.any { talent -> talent.formulas.isNotEmpty() } }.forEach { definition ->
                val base = requireNotNull(formulas.profile(definition.key))
                formulas.document(teams.compose(base))
                val members = teams.compose(base).teamMembers
                members.filter { it.key != base.characterKey }.forEach { member ->
                    formulas.document(teams.compose(base, setOf(member.key)))
                }
                val party = teams.compose(base)
                if (request.path.startsWith("artifacts/")) {
                    val definition = artifactDefinitions.single { it.key == request.path.substringAfter('/').removeSuffix(".json") }
                    party.selectedTeam.forEach { wearer ->
                        val gear = listOf("flower", "plume", "sands", "goblet").map { slot ->
                            PlayerArtifact(definition.key, slot, 20, 5, "eleMas", wearer, false, emptyList(), null, false, false)
                        }
                        formulas.document(artifacts.attach(party, gear))
                    }
                }
                if (request.path.startsWith("weapons/")) {
                    val definition = weaponDefinitions.single { it.key == request.path.substringAfter('/').removeSuffix(".json") }
                    party.selectedTeam.forEach { wearer ->
                        formulas.document(weapons.attach(party, listOf(PlayerWeapon(definition.key, 90, 6, 1, wearer, false))))
                    }
                }
            }
        }
        return mapOf("message" to "Valid JSON. Formula profiles and team calculations loaded successfully.")
    }

    @PostMapping("/api/damage-formulas/v2/editor/create")
    @ResponseBody
    @Synchronized
    fun create(@RequestBody request: FormulaEditorRequestV2): Map<String, String> {
        val target = newFile(request.path)
        validate(request)
        try {
            Files.writeString(target, request.content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        } catch (error: FileAlreadyExistsException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "This file was just created elsewhere. Open it from the library instead.")
        }
        DamageFormulaResourcesV2.revision.incrementAndGet()
        return mapOf("message" to "Created. Add damage instances and effects in the editor.",
            "path" to request.path, "content" to request.content, "revision" to hash(request.content))
    }

    @PostMapping("/api/damage-formulas/v2/editor/save")
    @ResponseBody
    @Synchronized
    fun save(@RequestBody request: FormulaEditorRequestV2): Map<String, String> {
        val target = file(request.path)
        val previous = Files.readString(target)
        if (hash(previous) != request.revision) throw ResponseStatusException(HttpStatus.CONFLICT, "This file changed on disk. Reload it before saving.")
        validate(request)
        val temp = Files.createTempFile(target.parent, ".formula-", ".tmp")
        try {
            Files.writeString(temp, request.content)
            if (hash(Files.readString(target)) != request.revision) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "This file changed during validation. Reload it before saving.")
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { Files.deleteIfExists(temp) }
        DamageFormulaResourcesV2.revision.incrementAndGet()
        return mapOf("message" to "Saved. Refresh the calculator to use these formulas.", "revision" to hash(request.content))
    }

    @ExceptionHandler(Exception::class)
    @ResponseBody
    fun error(error: Exception): ResponseEntity<Map<String, String>> {
        val status = (error as? ResponseStatusException)?.statusCode ?: HttpStatus.BAD_REQUEST
        return ResponseEntity.status(status).body(mapOf("error" to (error.message ?: "Could not process this formula file")))
    }
}
