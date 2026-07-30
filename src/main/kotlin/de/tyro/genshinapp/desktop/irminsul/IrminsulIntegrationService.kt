package de.tyro.genshinapp.desktop.irminsul

import de.tyro.genshinapp.configuration.DesktopUserProvider
import de.tyro.genshinapp.service.PlayerSnapshotStore
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@Component
@Profile("desktop")
@ConfigurationProperties(prefix = "genshin.irminsul")
class IrminsulProperties {
    var executable: String? = null
}

enum class IrminsulCaptureState {
    UNAVAILABLE,
    IDLE,
    STARTING,
    WAITING_FOR_GAME,
    CAPTURING,
    IMPORTING,
    SYNCING,
    LIVE,
    COMPLETE,
    STOPPING,
    ERROR,
    ;

    val active: Boolean
        get() = this in setOf(
            STARTING,
            WAITING_FOR_GAME,
            CAPTURING,
            IMPORTING,
            SYNCING,
            LIVE,
            STOPPING,
        )
}

data class IrminsulCaptureStatus(
    val state: IrminsulCaptureState,
    val message: String,
    val updatedAt: Instant = Instant.now(),
)

data class IrminsulStatusEvent(
    val state: String = "",
    val message: String? = null,
)

@Service
@Profile("desktop")
class IrminsulIntegrationService(
    private val applicationContext: ApplicationContext,
    private val properties: IrminsulProperties,
    private val desktopUserProvider: DesktopUserProvider,
    private val snapshotStore: PlayerSnapshotStore,
) {
    private val token = ByteArray(32).also(SecureRandom()::nextBytes)
        .let(Base64.getUrlEncoder().withoutPadding()::encodeToString)
    private val currentStatus = AtomicReference(initialStatus())
    private val listeners = CopyOnWriteArrayList<(IrminsulCaptureStatus) -> Unit>()

    @Volatile
    private var activeSession: String? = null

    @Volatile
    private var cancelRequested: Boolean = false

    @Volatile
    private var launchProcess: Process? = null

    fun status(): IrminsulCaptureStatus = currentStatus.get()

    fun addListener(listener: (IrminsulCaptureStatus) -> Unit): AutoCloseable {
        listeners.add(listener)
        listener(status())
        return AutoCloseable { listeners.remove(listener) }
    }

    @Synchronized
    fun startCapture(reuseSessionKey: Boolean = false): IrminsulCaptureStatus {
        if (status().state.active) return status()
        if (reuseSessionKey && !developmentMode()) {
            return update(
                IrminsulCaptureState.ERROR,
                "Session-key reuse is available only in development mode.",
            )
        }
        if (reuseSessionKey && !hasCachedSessionKey()) {
            return update(
                IrminsulCaptureState.ERROR,
                "No cached Genshin session key is available yet.",
            )
        }

        val executable = resolveExecutable()
        if (executable == null) {
            return update(
                IrminsulCaptureState.UNAVAILABLE,
                "The bundled Irminsul capture helper was not found.",
            )
        }

        val session = UUID.randomUUID().toString()
        activeSession = session
        cancelRequested = false
        update(
            IrminsulCaptureState.STARTING,
            "Approve the Windows administrator prompt to start packet capture.",
        )

        val port = (applicationContext as? ServletWebServerApplicationContext)
            ?.webServer
            ?.port
            ?: return update(
                IrminsulCaptureState.ERROR,
                "The local desktop server is not available.",
            )
        val baseUrl = "http://127.0.0.1:$port/api/desktop/irminsul"
        val logDirectory = desktopHome().resolve("irminsul")
        Files.createDirectories(logDirectory)
        val launchLog = logDirectory.resolve("launcher.log").toFile()
        val captureLog = captureLogPath()
        val packetInspectionLog = packetInspectionLogPath()
        rotateCaptureLog(captureLog)
        rotatePacketInspectionLog(packetInspectionLog)
        if (developmentMode() && !reuseSessionKey) {
            Files.deleteIfExists(sessionKeyPath())
        }

        return try {
            val command = mutableListOf(
                executable.toString(),
                "--endpoint",
                baseUrl,
                "--token",
                token,
                "--session",
                session,
                "--log-file",
                captureLog.toString(),
                "--packet-log-file",
                packetInspectionLog.toString(),
            )
            if (developmentMode()) {
                command += listOf("--session-key-file", sessionKeyPath().toString())
                if (reuseSessionKey) command += "--reuse-session-key"
            }
            launchProcess = ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(launchLog))
                .start()
            scheduleStartupTimeout(session)
            status()
        } catch (exception: Exception) {
            activeSession = null
            update(
                IrminsulCaptureState.ERROR,
                "The capture helper could not be started: ${exception.message}",
            )
        }
    }

    @Synchronized
    fun stopCapture(): IrminsulCaptureStatus {
        if (!status().state.active) return status()
        cancelRequested = true
        val session = activeSession
        val result = update(
            IrminsulCaptureState.STOPPING,
            "Stopping capture…",
        )
        if (session != null) scheduleStopTimeout(session)
        return result
    }

    fun accepts(requestToken: String?, session: String?): Boolean {
        val expectedSession = activeSession ?: return false
        if (session != expectedSession || requestToken == null) return false
        return MessageDigest.isEqual(
            token.toByteArray(Charsets.UTF_8),
            requestToken.toByteArray(Charsets.UTF_8),
        )
    }

    fun cancellationRequested(session: String): Boolean =
        session == activeSession && cancelRequested

    fun receiveStatus(event: IrminsulStatusEvent) {
        when (event.state.lowercase()) {
            "waiting_for_game" -> update(
                IrminsulCaptureState.WAITING_FOR_GAME,
                event.message ?: "Capture is running. Start Genshin Impact and enter the game.",
            )
            "capturing", "characters_captured", "items_captured" -> update(
                IrminsulCaptureState.CAPTURING,
                event.message ?: "Game data detected. Waiting for the complete snapshot…",
            )
            "uploading" -> update(
                IrminsulCaptureState.IMPORTING,
                event.message ?: "Importing the captured GOOD snapshot…",
            )
            "syncing" -> update(
                IrminsulCaptureState.SYNCING,
                event.message ?: "Saving live game changes…",
            )
            "stopped" -> {
                activeSession = null
                cancelRequested = false
                update(IrminsulCaptureState.IDLE, event.message ?: "Capture stopped.")
            }
            "error" -> {
                activeSession = null
                cancelRequested = false
                update(
                    IrminsulCaptureState.ERROR,
                    event.message ?: "Irminsul reported an unknown error.",
                )
            }
        }
    }

    @Synchronized
    fun receiveSnapshot(bytes: ByteArray): IrminsulCaptureStatus {
        update(IrminsulCaptureState.IMPORTING, "Validating and saving the GOOD snapshot…")
        return try {
            val principal = desktopUserProvider.principal()
            val snapshot = snapshotStore.save(principal.id, bytes)
            val summary = "${snapshot.characters.size} characters, " +
                "${snapshot.artifacts.size} artifacts, and ${snapshot.weapons.size} weapons"
            if (activeSession != null) {
                update(
                    IrminsulCaptureState.LIVE,
                    "Live sync active. Saved $summary.",
                )
            } else {
                update(
                    IrminsulCaptureState.COMPLETE,
                    "Imported $summary.",
                )
            }
        } catch (exception: Exception) {
            activeSession = null
            cancelRequested = false
            update(
                IrminsulCaptureState.ERROR,
                "The captured snapshot could not be imported: ${exception.message}",
            )
        }
    }

    fun captureLogPath(): Path = desktopHome().resolve("irminsul").resolve("live-capture.log")

    fun packetInspectionLogPath(): Path =
        desktopHome().resolve("irminsul").resolve("packet-inspection.jsonl")

    fun developmentMode(): Boolean = System.getProperty("jpackage.app-path").isNullOrBlank()

    fun hasCachedSessionKey(): Boolean =
        developmentMode() && runCatching {
            Files.readString(sessionKeyPath()).contains("\"complete\":true")
        }.getOrDefault(false)

    private fun sessionKeyPath(): Path =
        desktopHome().resolve("irminsul").resolve("dev-session.json")

    private fun initialStatus(): IrminsulCaptureStatus =
        if (resolveExecutable() == null) {
            IrminsulCaptureStatus(
                IrminsulCaptureState.UNAVAILABLE,
                "Build or package the Irminsul helper to enable local capture.",
            )
        } else {
            IrminsulCaptureStatus(
                IrminsulCaptureState.IDLE,
                "Ready. Start capture before entering the game.",
            )
        }

    private fun update(
        state: IrminsulCaptureState,
        message: String,
    ): IrminsulCaptureStatus {
        val next = IrminsulCaptureStatus(state, message)
        currentStatus.set(next)
        listeners.forEach { it(next) }
        return next
    }

    private fun scheduleStartupTimeout(session: String) {
        CompletableFuture.runAsync(
            {
                if (activeSession == session &&
                    status().state == IrminsulCaptureState.STARTING
                ) {
                    activeSession = null
                    update(
                        IrminsulCaptureState.ERROR,
                        "The capture helper did not start. The administrator prompt may have been cancelled.",
                    )
                }
            },
            CompletableFuture.delayedExecutor(30, TimeUnit.SECONDS),
        )
    }

    private fun scheduleStopTimeout(session: String) {
        CompletableFuture.runAsync(
            {
                if (activeSession == session &&
                    status().state == IrminsulCaptureState.STOPPING
                ) {
                    launchProcess?.destroy()
                    activeSession = null
                    cancelRequested = false
                    update(IrminsulCaptureState.IDLE, "Capture stopped.")
                }
            },
            CompletableFuture.delayedExecutor(10, TimeUnit.SECONDS),
        )
    }

    private fun resolveExecutable(): Path? {
        val fileName = "genshin-irminsul-helper.exe"
        val configured = properties.executable
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let(Path::of)
        val packaged = System.getProperty("jpackage.app-path")
            ?.let(Path::of)
            ?.parent
            ?.resolve("app")
            ?.resolve(fileName)
        val development = Path.of(
            "native",
            "irminsul-helper",
            "target",
            "release",
            fileName,
        )
        return listOfNotNull(configured, packaged, development)
            .map { it.toAbsolutePath().normalize() }
            .firstOrNull(Files::isRegularFile)
    }

    private fun desktopHome(): Path =
        Path.of(
            System.getProperty(
                "genshin.desktop.home",
                Path.of(
                    System.getProperty("user.home"),
                    ".genshinapp",
                    "desktop",
                ).toString(),
            ),
        ).toAbsolutePath().normalize()

    private fun rotateCaptureLog(path: Path) {
        Files.createDirectories(path.parent)
        if (Files.isRegularFile(path) && Files.size(path) > MAX_CAPTURE_LOG_SIZE) {
            Files.move(
                path,
                path.resolveSibling("live-capture.previous.log"),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun rotatePacketInspectionLog(path: Path) {
        Files.createDirectories(path.parent)
        if (Files.isRegularFile(path)) {
            Files.move(
                path,
                path.resolveSibling("packet-inspection.previous.jsonl"),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    companion object {
        private const val MAX_CAPTURE_LOG_SIZE = 2L * 1024L * 1024L
    }
}
