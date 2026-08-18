package de.tyro.genshinapp.desktop

import de.tyro.genshinapp.GenshinAppApplication
import de.tyro.genshinapp.configuration.GenshinRuntimeProperties
import de.tyro.genshinapp.desktop.irminsul.IrminsulCaptureState
import de.tyro.genshinapp.desktop.irminsul.IrminsulCaptureStatus
import de.tyro.genshinapp.desktop.irminsul.IrminsulIntegrationService
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.application.Application
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ProgressIndicator
import javafx.scene.control.Separator
import javafx.scene.control.TextArea
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.stage.Stage
import javafx.util.Duration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import java.awt.Color
import java.awt.Desktop
import java.awt.EventQueue
import java.awt.Font
import java.awt.Graphics2D
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import java.io.RandomAccessFile
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

fun main(args: Array<String>) {
    System.setProperty("java.awt.headless", "false")
    val desktopDirectory = desktopDirectory().toAbsolutePath().normalize()
    System.setProperty("genshin.desktop.home", desktopDirectory.toString())
    createDesktopDirectories(desktopDirectory)
    Application.launch(GenshinDesktopApplication::class.java, *args)
}

class GenshinDesktopApplication : Application() {
    private lateinit var context: ConfigurableApplicationContext
    private lateinit var applicationUri: URI
    private lateinit var runtimeProperties: GenshinRuntimeProperties
    private lateinit var irminsulIntegrationService: IrminsulIntegrationService
    private lateinit var dataDirectory: Path
    private var trayIcon: TrayIcon? = null
    private var primaryStage: Stage? = null
    private var captureStatusSubscription: AutoCloseable? = null
    private var captureLogRefresh: Timeline? = null

    @Volatile
    private var shutdownRequested = false

    override fun start(stage: Stage) {
        primaryStage = stage
        dataDirectory = Path.of(requireNotNull(System.getProperty("genshin.desktop.home")))
        val startupStatus = Label("Starting local database and services…").apply {
            styleClass.add("startup-status")
            isWrapText = true
        }

        stage.title = "Genshin Archive"
        stage.isResizable = false
        stage.scene = createStartupScene(startupStatus)
        stage.icons.add(
            javafx.scene.image.Image(
                requireNotNull(
                    GenshinDesktopApplication::class.java.getResourceAsStream("/desktop/app-icon.png"),
                ),
            ),
        )
        stage.sizeToScene()
        stage.centerOnScreen()
        stage.show()

        startBackend(stage, startupStatus)
    }

    override fun stop() {
        shutdownRequested = true
        captureStatusSubscription?.close()
        captureLogRefresh?.stop()
        if (::irminsulIntegrationService.isInitialized) {
            irminsulIntegrationService.stopCapture()
        }
        trayIcon?.let { icon ->
            if (SystemTray.isSupported()) {
                SystemTray.getSystemTray().remove(icon)
            }
        }
        if (::context.isInitialized) {
            context.close()
        }
    }

    private fun startBackend(stage: Stage, startupStatus: Label) {
        val applicationArguments = parameters.raw.toTypedArray()
        Thread(
            {
                try {
                    val startedContext = SpringApplicationBuilder(GenshinAppApplication::class.java).profiles("desktop").run(*applicationArguments)
                    context = startedContext
                    if (shutdownRequested) {
                        startedContext.close()
                        return@Thread
                    }

                    val webContext = startedContext as? ServletWebServerApplicationContext ?: error("Desktop mode requires a servlet web server")
                    applicationUri = URI("http://127.0.0.1:${webContext.webServer.port}/")
                    runtimeProperties = startedContext.getBean(GenshinRuntimeProperties::class.java)
                    irminsulIntegrationService = startedContext.getBean(IrminsulIntegrationService::class.java)

                    Platform.runLater {
                        if (!shutdownRequested) {
                            startupStatus.text = "Opening the local interface…"
                            showMainWindow(stage)
                        }
                    }
                } catch (exception: Exception) {
                    exception.printStackTrace()
                    Platform.runLater {
                        if (!shutdownRequested) showStartupFailure(stage, exception)
                    }
                }
            },
            "genshin-desktop-startup",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun showMainWindow(stage: Stage) {
        if (!runtimeProperties.showWindow) {
            Platform.setImplicitExit(false)
            stage.hide()
            if (runtimeProperties.trayEnabled) installTrayIcon()
            if (runtimeProperties.openBrowser) openInSystemBrowser()
            return
        }

        val root = BorderPane().apply {
            center = createLauncherPanel()
            styleClass.add("launcher-root")
        }
        val scene = Scene(root, 740.0, 840.0).apply { addDesktopStylesheet() }

        stage.isResizable = true
        stage.minWidth = 620.0
        stage.minHeight = 720.0
        stage.scene = scene
        stage.width = 740.0
        stage.height = 840.0
        stage.centerOnScreen()

        if (runtimeProperties.trayEnabled) {
            installTrayIcon()
        }
        if (runtimeProperties.openBrowser) {
            openInSystemBrowser()
        }
    }

    private fun createStartupScene(status: Label): Scene {
        val emblem = StackPane(
            Label("✦").apply { styleClass.add("startup-mark") },
        ).apply {
            styleClass.add("startup-emblem")
            minWidth = 76.0
            minHeight = 76.0
            maxWidth = 76.0
            maxHeight = 76.0
        }
        val progress = ProgressIndicator().apply {
            styleClass.add("startup-progress")
            prefWidth = 42.0
            prefHeight = 42.0
        }
        val content = VBox(
            14.0,
            emblem,
            Label("Genshin Archive").apply { styleClass.add("startup-title") },
            Label("Your local companion is waking up").apply {
                styleClass.add("startup-subtitle")
            },
            Region().apply {
                minHeight = 10.0
                prefHeight = 10.0
            },
            progress,
            status,
            Label(dataDirectory.toString()).apply {
                styleClass.add("startup-path")
                isWrapText = true
            },
        ).apply {
            alignment = Pos.CENTER
            padding = Insets(34.0, 48.0, 30.0, 48.0)
        }
        return Scene(
            StackPane(content).apply { styleClass.add("startup-root") },
            560.0,
            420.0,
        ).apply { addDesktopStylesheet() }
    }

    private fun showStartupFailure(stage: Stage, exception: Exception) {
        val message = exception.message?.takeIf(String::isNotBlank)?.take(300) ?: "The local services could not be started."
        val exit = Button("Close").apply {
            styleClass.add("startup-close")
            setOnAction { Platform.exit() }
        }
        val content = VBox(
            14.0,
            Label("Could not start Genshin Archive").apply {
                styleClass.add("startup-error-title")
            },
            Label(message).apply {
                styleClass.add("startup-error-copy")
                isWrapText = true
            },
            exit,
        ).apply {
            alignment = Pos.CENTER
            padding = Insets(48.0)
        }
        stage.scene = Scene(
            StackPane(content).apply { styleClass.add("startup-root") },
            560.0,
            360.0,
        ).apply { addDesktopStylesheet() }
        stage.sizeToScene()
        stage.centerOnScreen()
    }

    private fun Scene.addDesktopStylesheet() {
        stylesheets.add(
            requireNotNull(
                GenshinDesktopApplication::class.java.getResource("/desktop/desktop.css"),
            ).toExternalForm(),
        )
    }

    private fun createLauncherPanel(): VBox {
        val title = Label("Genshin Archive").apply {
            styleClass.add("desktop-title")
        }
        val mode = Label("DESKTOP").apply {
            styleClass.add("desktop-mode")
        }
        val heading = HBox(10.0, Label("✦").apply {
            styleClass.add("desktop-mark")
        }, VBox(3.0, title, mode)).apply {
            alignment = Pos.CENTER_LEFT
        }

        val dataPath = Label(dataDirectory.toString()).apply {
            styleClass.add("tool-detail")
            isWrapText = true
        }
        val address = Label(applicationUri.toString()).apply {
            styleClass.add("launcher-address")
            isWrapText = true
        }
        val browserCard = VBox(
            8.0,
            Label("Web dashboard").apply { styleClass.add("tool-title") },
            Label(
                "Open the full interface in your default browser for the best performance.",
            ).apply {
                styleClass.add("tool-copy")
                isWrapText = true
            },
            address,
            Button("Open dashboard in browser  ↗").apply {
                maxWidth = Double.MAX_VALUE
                styleClass.add("secondary-action")
                setOnAction { openInSystemBrowser() }
            },
        ).apply {
            styleClass.addAll("status-card", "launcher-browser-card")
        }
        return VBox(
            18.0,
            heading,
            Separator(),
            browserCard,
            sectionLabel("LOCAL SERVICES"),
            HBox(
                12.0,
                statusCard(
                    "Application backend",
                    "Running",
                    "Available only on this computer.",
                    "status-running",
                ),
                createCaptureCard(),
            ).apply {
                children.forEach { HBox.setHgrow(it, Priority.ALWAYS) }
            },
            createCaptureLogCard(),
            Separator(),
            sectionLabel("LOCAL STORAGE"),
            Label("Your database and imported data persist here:").apply {
                styleClass.add("tool-copy")
                isWrapText = true
            },
            dataPath,
            Region().apply { VBox.setVgrow(this, Priority.ALWAYS) },
        ).apply {
            padding = Insets(30.0)
            styleClass.add("launcher-panel")
        }
    }

    private fun createCaptureCard(): VBox {
        val stateLabel = Label().apply {
            styleClass.add("tool-state")
        }
        val description = Label().apply {
            styleClass.add("tool-copy")
            isWrapText = true
        }
        val action = Button().apply {
            maxWidth = Double.MAX_VALUE
            styleClass.add("capture-action")
        }
        val reuseSession = Button("Reuse current Genshin session (dev)").apply {
            maxWidth = Double.MAX_VALUE
            styleClass.add("capture-action")
            isVisible = irminsulIntegrationService.developmentMode()
            isManaged = isVisible
        }
        val card = VBox(
            8.0,
            Label("Game data capture").apply { styleClass.add("tool-title") },
            HBox(
                6.0,
                Label("●").apply { styleClass.add("status-dot") },
                stateLabel,
            ).apply {
                alignment = Pos.CENTER_LEFT
            },
            description,
            action,
            reuseSession,
        ).apply {
            styleClass.add("status-card")
            maxWidth = Double.MAX_VALUE
        }

        fun render(status: IrminsulCaptureStatus) {
            stateLabel.text = captureStateLabel(status.state)
            description.text = status.message
            stateLabel.styleClass.removeAll(
                "status-running",
                "status-idle",
                "status-error",
            )
            stateLabel.styleClass.add(
                when (status.state) {
                    IrminsulCaptureState.ERROR, IrminsulCaptureState.UNAVAILABLE -> "status-error"
                    IrminsulCaptureState.IDLE, IrminsulCaptureState.COMPLETE -> "status-idle"
                    else -> "status-running"
                },
            )
            action.text = when {
                status.state.active -> "Stop capture"
                status.state == IrminsulCaptureState.COMPLETE -> "Capture again"
                else -> "Start capture"
            }
            action.isDisable = status.state in setOf(
                IrminsulCaptureState.UNAVAILABLE,
                IrminsulCaptureState.IMPORTING,
                IrminsulCaptureState.STOPPING,
            )
            action.setOnAction {
                if (irminsulIntegrationService.status().state.active) {
                    irminsulIntegrationService.stopCapture()
                } else {
                    irminsulIntegrationService.startCapture()
                }
            }
            reuseSession.isDisable = status.state.active || status.state == IrminsulCaptureState.UNAVAILABLE || !irminsulIntegrationService.hasCachedSessionKey()
            reuseSession.setOnAction {
                irminsulIntegrationService.startCapture(reuseSessionKey = true)
            }
        }

        captureStatusSubscription = irminsulIntegrationService.addListener { status ->
            if (Platform.isFxApplicationThread()) {
                render(status)
            } else {
                Platform.runLater { render(status) }
            }
        }
        return card
    }

    private fun createCaptureLogCard(): VBox {
        val path = irminsulIntegrationService.captureLogPath()
        val packetPath = irminsulIntegrationService.packetInspectionLogPath()
        val log = TextArea().apply {
            isEditable = false
            isWrapText = false
            prefRowCount = 6
            styleClass.add("capture-log-area")
        }

        fun openLogFile(target: Path) {
            runCatching {
                Files.createDirectories(target.parent)
                if (!Files.exists(target)) Files.createFile(target)
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    Desktop.getDesktop().open(target.toFile())
                }
            }.onFailure {
                log.text = "The log file could not be opened: ${it.message}"
            }
        }

        fun refresh() {
            log.text = if (!Files.isRegularFile(path)) {
                "No capture log yet. Start capture to create it."
            } else {
                runCatching { captureLogTail(path, 12) }.getOrElse { "The capture log is temporarily unavailable: ${it.message}" }
            }
            log.positionCaret(log.text.length)
        }
        refresh()
        captureLogRefresh?.stop()
        captureLogRefresh = Timeline(
            KeyFrame(Duration.seconds(2.0), { refresh() }),
        ).apply {
            cycleCount = Timeline.INDEFINITE
            play()
        }

        return VBox(
            8.0,
            HBox(
                10.0,
                VBox(
                    2.0,
                    Label("Live capture log").apply { styleClass.add("tool-title") },
                    Label("Heartbeat below; decoded packet inspection is saved as JSONL.").apply {
                        styleClass.add("tool-copy")
                    },
                ),
                Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                HBox(
                    6.0,
                    Button("Open live log").apply {
                        styleClass.add("capture-log-open")
                        setOnAction { openLogFile(path) }
                    },
                    Button("Open packet JSONL").apply {
                        styleClass.add("capture-log-open")
                        setOnAction { openLogFile(packetPath) }
                    },
                ),
            ).apply { alignment = Pos.CENTER_LEFT },
            log,
        ).apply {
            styleClass.addAll("status-card", "capture-log-card")
        }
    }

    private fun captureStateLabel(state: IrminsulCaptureState): String = when (state) {
        IrminsulCaptureState.UNAVAILABLE -> "Helper unavailable"
        IrminsulCaptureState.IDLE -> "Ready"
        IrminsulCaptureState.STARTING -> "Starting"
        IrminsulCaptureState.WAITING_FOR_GAME -> "Waiting for game"
        IrminsulCaptureState.CAPTURING -> "Capturing"
        IrminsulCaptureState.IMPORTING -> "Importing"
        IrminsulCaptureState.SYNCING -> "Saving changes"
        IrminsulCaptureState.LIVE -> "Live sync active"
        IrminsulCaptureState.COMPLETE -> "Import complete"
        IrminsulCaptureState.STOPPING -> "Stopping"
        IrminsulCaptureState.ERROR -> "Capture error"
    }

    private fun captureLogTail(path: Path, maxLines: Int): String = RandomAccessFile(path.toFile(), "r").use { file ->
        val start = (file.length() - MAX_LOG_TAIL_BYTES).coerceAtLeast(0)
        file.seek(start)
        val bytes = ByteArray((file.length() - start).toInt())
        file.readFully(bytes)
        String(bytes, StandardCharsets.UTF_8).lineSequence().filter(String::isNotBlank).toList().takeLast(maxLines).joinToString(System.lineSeparator())
    }

    private fun statusCard(
        title: String,
        state: String,
        description: String,
        stateStyle: String,
    ): VBox = VBox(
        6.0,
        Label(title).apply { styleClass.add("tool-title") },
        HBox(6.0, Label("●").apply {
            styleClass.addAll("status-dot", stateStyle)
        }, Label(state).apply {
            styleClass.addAll("tool-state", stateStyle)
        }).apply {
            alignment = Pos.CENTER_LEFT
        },
        Label(description).apply {
            styleClass.add("tool-copy")
            isWrapText = true
        },
    ).apply {
        styleClass.add("status-card")
        maxWidth = Double.MAX_VALUE
    }

    private fun sectionLabel(text: String): Label = Label(text).apply {
        styleClass.add("section-label")
    }

    private fun installTrayIcon() {
        if (!SystemTray.isSupported()) return

        EventQueue.invokeLater {
            val popup = PopupMenu()
            popup.add(MenuItem("Open Genshin Archive").also {
                it.addActionListener { Platform.runLater(::showWindow) }
            })
            popup.addSeparator()
            popup.add(MenuItem("Exit").also {
                it.addActionListener {
                    Platform.runLater {
                        primaryStage?.close()
                        Platform.exit()
                    }
                }
            })

            val icon = TrayIcon(createTrayImage(), "Genshin Archive", popup).also {
                it.isImageAutoSize = true
                it.addActionListener { Platform.runLater(::showWindow) }
            }
            SystemTray.getSystemTray().add(icon)
            trayIcon = icon
        }
    }

    private fun showWindow() {
        primaryStage?.let {
            if (it.isIconified) it.isIconified = false
            it.show()
            it.toFront()
            it.requestFocus()
        }
    }

    private fun openInSystemBrowser() {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(applicationUri)
        }
    }

    companion object {
        private const val MAX_LOG_TAIL_BYTES = 64L * 1024L
    }
}

private fun desktopDirectory(): Path = System.getenv("GENSHIN_DESKTOP_HOME")?.trim()?.takeIf(String::isNotEmpty)?.let(Path::of) ?: Path.of(
    System.getProperty("user.home"),
    ".genshinapp",
    "desktop",
)

private fun createDesktopDirectories(desktopDirectory: Path) {
    Files.createDirectories(desktopDirectory.resolve("database"))
    Files.createDirectories(desktopDirectory.resolve("cache"))
}

private fun createTrayImage(): BufferedImage {
    val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    graphics.use {
        setRenderingHint(
            RenderingHints.KEY_ANTIALIASING,
            RenderingHints.VALUE_ANTIALIAS_ON,
        )
        color = Color(54, 45, 86)
        fillOval(1, 1, 30, 30)
        color = Color(245, 220, 153)
        font = Font(Font.SANS_SERIF, Font.BOLD, 22)
        drawString("✦", 6, 24)
    }
    return image
}

private inline fun Graphics2D.use(block: Graphics2D.() -> Unit) {
    try {
        block()
    } finally {
        dispose()
    }
}
