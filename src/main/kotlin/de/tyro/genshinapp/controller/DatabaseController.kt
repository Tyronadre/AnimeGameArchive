package de.tyro.genshinapp.controller

import de.tyro.genshinapp.configuration.LocalizedMessages
import de.tyro.genshinapp.security.AppUserPrincipal
import de.tyro.genshinapp.service.player.GoodDatabaseService
import de.tyro.genshinapp.service.player.GoodExportService
import de.tyro.genshinapp.service.player.GoodImportException
import de.tyro.genshinapp.service.player.GoodImportService
import de.tyro.genshinapp.service.player.PlayerSnapshotStore
import org.slf4j.LoggerFactory
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
@RequestMapping("/database")
class DatabaseController(
    private val snapshotStore: PlayerSnapshotStore,
    private val databaseService: GoodDatabaseService,
    private val messages: LocalizedMessages,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @GetMapping
    fun database(
        @AuthenticationPrincipal principal: AppUserPrincipal,
        model: Model,
    ): String {
        model.addAttribute("snapshot", snapshotStore.current(principal.id))
        return "database"
    }

    @PostMapping("/import")
    fun importDatabase(
        @RequestParam("file") file: MultipartFile,
        @AuthenticationPrincipal principal: AppUserPrincipal,
        redirectAttributes: RedirectAttributes,
    ): String {
        val error = when {
            file.isEmpty -> messages.get("inventory.import.selectFile")
            file.size > GoodImportService.MAX_FILE_SIZE -> messages.get("good.error.fileSize")
            else -> null
        }
        if (error != null) {
            redirectAttributes.addFlashAttribute("errorMessage", error)
            return REDIRECT_DATABASE
        }

        return try {
            val snapshot = databaseService.import(principal.id, file.bytes)
            redirectAttributes.addFlashAttribute(
                "successMessage",
                messages.get(
                    "database.import.success",
                    snapshot.characters.size,
                    snapshot.artifacts.size,
                    snapshot.weapons.size,
                    snapshot.inventory.size,
                ),
            )
            REDIRECT_DATABASE
        } catch (exception: GoodImportException) {
            redirectAttributes.addFlashAttribute(
                "errorMessage",
                messages.get(exception.messageKey, *exception.messageArguments),
            )
            REDIRECT_DATABASE
        } catch (exception: Exception) {
            logger.error("The GOOD import could not be persisted", exception)
            redirectAttributes.addFlashAttribute(
                "errorMessage",
                messages.get("inventory.import.persistenceError"),
            )
            REDIRECT_DATABASE
        }
    }

    @GetMapping("/export", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun exportDatabase(
        @AuthenticationPrincipal principal: AppUserPrincipal,
    ): ResponseEntity<ByteArray> = try {
        ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(GoodExportService.FILE_NAME).build()
                    .toString(),
            )
            .body(databaseService.export(principal.id))
    } catch (_: IllegalStateException) {
        ResponseEntity.notFound().build()
    }

    companion object {
        private const val REDIRECT_DATABASE = "redirect:/database"
    }
}
