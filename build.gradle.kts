import org.springframework.boot.gradle.tasks.bundling.BootJar
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.bundling.ZipEntryCompression
import java.util.Properties
import java.util.concurrent.TimeUnit


plugins {
    kotlin("jvm") version "1.9.25"
    kotlin("plugin.spring") version "1.9.25"
    id("org.springframework.boot") version "3.5.7"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.openjfx.javafxplugin") version "0.1.0"
}

group = "de.tyro"
version = "0.1.6"
description = "GenshinApp"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

javafx {
    version = "21.0.9"
    modules("javafx.controls")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-docker-compose")
    runtimeOnly("com.mysql:mysql-connector-j")
    runtimeOnly("com.h2database:h2")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

springBoot {
    mainClass.set("de.tyro.genshinapp.GenshinAppApplicationKt")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Opt in for either bootRun or desktopRun without changing packaged behavior.
tasks.withType<JavaExec>().configureEach {
    if (providers.gradleProperty("uiDev").isPresent && name in setOf("bootRun", "desktopRun")) {
        workingDir = projectDir
        systemProperty("spring.profiles.include", "ui-dev")
    }
}

val desktopMainClass = "de.tyro.genshinapp.desktop.DesktopLauncherKt"
val scraperMainClass = "de.tyro.genshinapp.tools.Game8BuildProfileScraperKt"
val bundledCatalogSnapshotMainClass =
    "de.tyro.genshinapp.service.importer.BundledCatalogSnapshotKt"
val bundledCatalogSnapshotFile = layout.buildDirectory.file(
    "generated/bundled-catalog/data/bundled-catalog-snapshot.json",
)
val irminsulManifest = layout.projectDirectory.file("native/irminsul-helper/Cargo.toml")
val irminsulExecutable = layout.projectDirectory.file("native/irminsul-helper/target/release/genshin-irminsul-helper.exe")
val nativeUpstreamVersions = layout.projectDirectory.file("native/upstream-versions.properties")
val appName = "Another Anime Game Archive"
val appIcon = layout.projectDirectory.file("src/main/resources/desktop/app-icon.ico")
val appImageDirectory = layout.buildDirectory.dir("desktop/$appName")
val installerDirectory = layout.buildDirectory.dir("desktop-installer")
val packageDirectory = layout.buildDirectory.dir("desktop").get().asFile
val inputDirectory = layout.buildDirectory.dir("desktop-input").get().asFile
val javaHome = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(21)
}.get().metadata.installationPath.asFile
val onlineInstallerSources = layout.projectDirectory.dir("installer/windows-online")
val onlineInstallerBuildDirectory = layout.buildDirectory.dir("online-installer")
val onlineInstallerBinDirectory = onlineInstallerBuildDirectory.map { it.dir("bin") }
val onlineInstallerPayloadDirectory = onlineInstallerBuildDirectory.map { it.dir("payload") }
val onlineInstallerPayloadArchive = onlineInstallerBuildDirectory.map {
    it.file("online-app-payload.zip")
}
val onlineLauncherExecutable = onlineInstallerBinDirectory.map { it.file("launcher.exe") }
val onlineUninstallerExecutable = onlineInstallerBinDirectory.map { it.file("uninstaller.exe") }
val onlineVersionFile = onlineInstallerBuildDirectory.map { it.file("version.txt") }
val onlineInstallerExecutable = installerDirectory.map {
    it.file("$appName Online Installer-${project.version}.exe")
}
val windowsDirectory = System.getenv("WINDIR") ?: "C:/Windows"
val csharpCompilerCandidates = listOf(
    file("$windowsDirectory/Microsoft.NET/Framework64/v4.0.30319/csc.exe"),
    file("$windowsDirectory/Microsoft.NET/Framework/v4.0.30319/csc.exe"),
)
val csharpCompiler = csharpCompilerCandidates.firstOrNull(File::isFile)
    ?: csharpCompilerCandidates.first()
val csharpReferences = listOf(
    "Microsoft.CSharp.dll",
    "System.dll",
    "System.Core.dll",
    "System.Drawing.dll",
    "System.IO.Compression.dll",
    "System.IO.Compression.FileSystem.dll",
    "System.Web.Extensions.dll",
    "System.Windows.Forms.dll",
)

val generateBundledCatalogSnapshot by tasks.registering(JavaExec::class) {
    group = "content"
    description = "Fetches current catalog data and creates the image-free bundled snapshot."
    dependsOn(tasks.named("compileKotlin"))
    classpath(sourceSets.main.get().output.classesDirs)
    classpath(configurations.runtimeClasspath)
    mainClass.set(bundledCatalogSnapshotMainClass)
    args(
        "--output",
        bundledCatalogSnapshotFile.get().asFile.absolutePath,
    )
    outputs.file(bundledCatalogSnapshotFile)
    outputs.upToDateWhen { false }
}

val scrapeGame8ArtifactBuilds by tasks.registering(JavaExec::class) {
    group = "content"
    description = "Scrapes Game8 artifact build profiles into the bundled optimizer data file."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("de.tyro.genshinapp.tools.Game8BuildProfileScraperKt")
    args(
        "--output",
        layout.projectDirectory.file(
            "src/main/resources/data/artifact-optimizer-builds.json",
        ).asFile.absolutePath,
    )
}

tasks.named<BootJar>("bootJar") {
    dependsOn(generateBundledCatalogSnapshot)
    from(bundledCatalogSnapshotFile) {
        into("BOOT-INF/classes/data")
    }

    dependsOn(scrapeGame8ArtifactBuilds)
}

val buildIrminsulHelper by tasks.registering(Exec::class) {
    group = "desktop"
    description = "Builds the privileged Irminsul capture helper."
    dependsOn(checkNativeUpstreamUpdates)
    workingDir(layout.projectDirectory.dir("native/irminsul-helper"))
    commandLine(
        "cargo",
        "build",
        "--release",
        "--manifest-path",
        irminsulManifest.asFile.absolutePath,
    )
    inputs.files(
        fileTree("native/irminsul-helper") {
            exclude("target/**")
        },
    )
    outputs.file(irminsulExecutable)
}

val checkNativeUpstreamUpdates by tasks.registering {
    group = "verification"
    description = "Checks whether newer Irminsul native sources are available upstream."
    inputs.file(nativeUpstreamVersions)

    doLast {
        val instructionsFile = layout.projectDirectory.file("docs/native-upstream-updates.md").asFile
        val properties = Properties().apply {
            nativeUpstreamVersions.asFile.inputStream().use(::load)
        }

        fun property(name: String): String =
            properties.getProperty(name)?.trim()?.takeIf(String::isNotEmpty)
                ?: error("Missing '$name' in ${nativeUpstreamVersions.asFile}")

        fun gitLsRemote(vararg arguments: String): List<String> {
            val process = ProcessBuilder(listOf("git", "ls-remote") + arguments)
                .redirectErrorStream(true)
                .apply {
                    environment()["GIT_TERMINAL_PROMPT"] = "0"
                }
                .start()

            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("git ls-remote timed out")
            }

            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            if (process.exitValue() != 0) {
                error(output.ifEmpty { "git ls-remote exited with code ${process.exitValue()}" })
            }
            return output.lineSequence().filter(String::isNotBlank).toList()
        }

        fun revisionAndRef(line: String): Pair<String, String> {
            val fields = line.trim().split(Regex("\\s+"), limit = 2)
            check(fields.size == 2) { "Unexpected git ls-remote output: $line" }
            return fields[0] to fields[1]
        }

        val warnings = mutableListOf<String>()
        val failures = mutableListOf<String>()

        runCatching {
            val repository = property("irminsul.repository")
            val currentVersion = property("irminsul.currentVersion")
            val currentRevision = property("irminsul.currentRevision")
            val latest = gitLsRemote(
                "--tags",
                "--refs",
                "--sort=-version:refname",
                repository,
                "refs/tags/v*",
            ).firstOrNull() ?: error("No version tags were returned by $repository")
            val (latestRevision, latestRef) = revisionAndRef(latest)
            val latestVersion = latestRef.removePrefix("refs/tags/")

            if (latestVersion != currentVersion) {
                warnings += buildString {
                    appendLine("Irminsul: local $currentVersion (${currentRevision.take(12)}) -> upstream $latestVersion (${latestRevision.take(12)})")
                    append("  Review: ${repository.removeSuffix(".git")}/compare/$currentRevision...$latestRevision")
                }
            }
        }.onFailure {
            failures += "Irminsul: ${it.message ?: it.javaClass.simpleName}"
        }

        runCatching {
            val repository = property("autoArtifactarium.repository")
            val branchRef = property("autoArtifactarium.ref")
            val currentRevision = property("autoArtifactarium.currentRevision")
            val latest = gitLsRemote(repository, branchRef).singleOrNull()
                ?: error("Expected exactly one ref for $branchRef from $repository")
            val (latestRevision, _) = revisionAndRef(latest)

            if (!latestRevision.equals(currentRevision, ignoreCase = true)) {
                warnings += buildString {
                    appendLine("auto-artifactarium: local base ${currentRevision.take(12)} -> upstream ${latestRevision.take(12)}")
                    append("  Review: ${repository.removeSuffix(".git")}/compare/$currentRevision...$latestRevision")
                }
            }
        }.onFailure {
            failures += "auto-artifactarium: ${it.message ?: it.javaClass.simpleName}"
        }

        if (warnings.isEmpty() && failures.isEmpty()) {
            logger.lifecycle("Native upstream check: tracked sources are current.")
        }

        if (warnings.isNotEmpty()) {
            logger.warn(
                buildString {
                    appendLine()
                    appendLine("============================================================")
                    appendLine("WARNING: Native Rust source updates are available")
                    appendLine("------------------------------------------------------------")
                    appendLine(warnings.joinToString("\n"))
                    appendLine()
                    appendLine("Do not overwrite the locally adapted sources. Review and")
                    appendLine("port the upstream changes manually, then update:")
                    appendLine("  ${nativeUpstreamVersions.asFile}")
                    appendLine()
                    appendLine("Instructions:")
                    appendLine("  $instructionsFile")
                    appendLine()
                    appendLine("The installer build will continue.")
                    append("============================================================")
                },
            )
        }

        if (failures.isNotEmpty()) {
            logger.warn(
                "Native upstream update check could not be completed; the installer build will continue.\n" +
                    failures.joinToString("\n") { "  - $it" } +
                    "\nRun './gradlew checkNativeUpstreamUpdates' later or see $instructionsFile",
            )
        }
    }
}

val desktopBootJar by tasks.registering(BootJar::class) {
    group = "desktop"
    description = "Builds the self-contained Spring Boot jar used by the desktop application."
    archiveClassifier.set("desktop")
    mainClass.set(desktopMainClass)
    targetJavaVersion.set(JavaVersion.VERSION_21)
    classpath(sourceSets.main.get().runtimeClasspath)
    dependsOn(generateBundledCatalogSnapshot)
    from(bundledCatalogSnapshotFile) {
        into("BOOT-INF/classes/data")
    }
}

val compileOnlineLauncher by tasks.registering(Exec::class) {
    group = "desktop"
    description = "Builds the Java-independent Windows desktop launcher."
    inputs.file(onlineInstallerSources.file("Launcher.cs"))
    inputs.file(appIcon)
    outputs.file(onlineLauncherExecutable)

    doFirst {
        check(csharpCompiler.isFile) {
            "The .NET Framework C# compiler was not found: $csharpCompiler"
        }
        onlineInstallerBinDirectory.get().asFile.mkdirs()
        commandLine(
            csharpCompiler,
            "/nologo",
            "/target:winexe",
            "/optimize+",
            "/platform:x64",
            "/out:${onlineLauncherExecutable.get().asFile.absolutePath}",
            "/win32icon:${appIcon.asFile.absolutePath}",
            *csharpReferences.map { "/reference:$it" }.toTypedArray(),
            onlineInstallerSources.file("Launcher.cs").asFile.absolutePath,
        )
    }
}

val compileOnlineUninstaller by tasks.registering(Exec::class) {
    group = "desktop"
    description = "Builds the per-user Windows uninstaller."
    inputs.file(onlineInstallerSources.file("Uninstaller.cs"))
    inputs.file(appIcon)
    outputs.file(onlineUninstallerExecutable)

    doFirst {
        check(csharpCompiler.isFile) {
            "The .NET Framework C# compiler was not found: $csharpCompiler"
        }
        onlineInstallerBinDirectory.get().asFile.mkdirs()
        commandLine(
            csharpCompiler,
            "/nologo",
            "/target:winexe",
            "/optimize+",
            "/platform:x64",
            "/out:${onlineUninstallerExecutable.get().asFile.absolutePath}",
            "/win32icon:${appIcon.asFile.absolutePath}",
            *csharpReferences.map { "/reference:$it" }.toTypedArray(),
            onlineInstallerSources.file("Uninstaller.cs").asFile.absolutePath,
        )
    }
}

val writeOnlineInstallerVersion by tasks.registering {
    inputs.property("version", project.version.toString())
    outputs.file(onlineVersionFile)
    doLast {
        onlineVersionFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(project.version.toString())
        }
    }
}

val prepareOnlineInstallerPayload by tasks.registering(Sync::class) {
    group = "desktop"
    description = "Stages the application-only payload for the online installer."
    dependsOn(
        desktopBootJar,
        buildIrminsulHelper,
        compileOnlineLauncher,
        compileOnlineUninstaller,
        writeOnlineInstallerVersion,
    )
    from(onlineLauncherExecutable) {
        rename { "$appName.exe" }
    }
    from(onlineUninstallerExecutable) {
        rename { "Uninstall.exe" }
    }
    from(desktopBootJar.flatMap { it.archiveFile }) {
        into("app")
        rename { "app.jar" }
    }
    from(irminsulExecutable) {
        into("app")
    }
    from("native/irminsul-helper/LICENSE") {
        into("app")
        rename { "IRMINSUL-LICENSE.txt" }
    }
    from(onlineVersionFile) {
        into("app")
        rename { "version.txt" }
    }
    into(onlineInstallerPayloadDirectory)
}

val onlineInstallerPayload by tasks.registering(Zip::class) {
    group = "desktop"
    description = "Compresses the application-only online-installer payload."
    dependsOn(prepareOnlineInstallerPayload)
    from(onlineInstallerPayloadDirectory)
    destinationDirectory.set(onlineInstallerBuildDirectory)
    archiveFileName.set(onlineInstallerPayloadArchive.map { it.asFile.name })
    entryCompression = ZipEntryCompression.DEFLATED
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.register<JavaExec>("desktopRun") {
    group = "desktop"
    description = "Runs Genshin Archive locally in desktop mode."
    dependsOn(buildIrminsulHelper)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(desktopMainClass)
    jvmArgs("-Djava.awt.headless=false")
}



val prepareDesktopPackage by tasks.registering(Sync::class) {
    group = "desktop"
    description = "Stages the desktop boot jar for jpackage."
    dependsOn(desktopBootJar, buildIrminsulHelper)
    from(desktopBootJar.flatMap { it.archiveFile })
    from(irminsulExecutable)
    from("native/irminsul-helper/LICENSE") {
        rename { "IRMINSUL-LICENSE.txt" }
    }
    into(layout.buildDirectory.dir("desktop-input"))
}

tasks.register<Exec>("packageDesktop") {
    group = "desktop"
    description = "Creates a Windows application image with a bundled Java runtime."
    dependsOn(prepareDesktopPackage)
    inputs.file(appIcon)

    doFirst {
        delete(packageDirectory)
        val executable = javaHome.resolve("bin/jpackage.exe")
        check(executable.isFile) {
            "jpackage.exe was not found in the configured Java 21 toolchain: $javaHome"
        }
        val desktopJar = desktopBootJar.get().archiveFile.get().asFile
        commandLine(
            executable,
            "--type", "app-image",
            "--name", appName,
            "--icon", appIcon.asFile.absolutePath,
            "--dest", packageDirectory,
            "--input", inputDirectory,
            "--main-jar", desktopJar.name,
            "--main-class", "org.springframework.boot.loader.launch.JarLauncher",
            "--java-options", "-Dfile.encoding=UTF-8",
            "--java-options", "-Djava.awt.headless=false",
            "--vendor", "Tyro",
            "--app-version", project.version.toString().substringBefore("-"),
        )
    }
}

tasks.register<Exec>("packageDesktopOfflineInstaller") {
    group = "desktop"
    description = "Creates an offline Windows installer with a bundled Java runtime."
    dependsOn("packageDesktop", checkNativeUpstreamUpdates)

    doFirst {
        val jpackage = javaHome.resolve("bin/jpackage.exe")

        check(jpackage.isFile) {
            "jpackage.exe was not found in the configured Java 21 toolchain: ${jpackage.parentFile.parentFile}"
        }

        val appImage = appImageDirectory.get().asFile
        check(appImage.isDirectory) {
            "Application image was not found: $appImage"
        }

        delete(installerDirectory)
        installerDirectory.get().asFile.mkdirs()

        //https://github.com/wixtoolset/wix3/releases
        commandLine(
            jpackage,
            "--type",
            "exe",
            "--name",
            "$appName Installer",
            "--app-image",
            appImage.absolutePath,
            "--dest",
            installerDirectory.get().asFile.absolutePath,
            "--vendor",
            "Tyro",
            "--app-version",
            project.version.toString().substringBefore("-"),

            "--win-menu",
            "--win-shortcut",
            "--win-dir-chooser",
            "--win-per-user-install",

            // Important: keep this UUID stable across releases.
            "--win-upgrade-uuid",
            "7b3f3b9b-2c86-4b54-9873-5d1f3a9b6c21"
        )
    }
}

tasks.register<Exec>("packageDesktopInstaller") {
    group = "desktop"
    description = "Creates a small online installer that downloads a private Java 21 runtime."
    dependsOn(onlineInstallerPayload, checkNativeUpstreamUpdates)
    inputs.file(onlineInstallerSources.file("OnlineInstaller.cs"))
    inputs.file(onlineInstallerPayloadArchive)
    inputs.file(appIcon)
    outputs.file(onlineInstallerExecutable)

    doFirst {
        check(csharpCompiler.isFile) {
            "The .NET Framework C# compiler was not found: $csharpCompiler"
        }
        delete(installerDirectory)
        installerDirectory.get().asFile.mkdirs()
        commandLine(
            csharpCompiler,
            "/nologo",
            "/target:winexe",
            "/optimize+",
            "/platform:x64",
            "/out:${onlineInstallerExecutable.get().asFile.absolutePath}",
            "/win32icon:${appIcon.asFile.absolutePath}",
            "/resource:${onlineInstallerPayloadArchive.get().asFile.absolutePath},AppPayload.zip",
            *csharpReferences.map { "/reference:$it" }.toTypedArray(),
            onlineInstallerSources.file("OnlineInstaller.cs").asFile.absolutePath,
        )
    }
}
