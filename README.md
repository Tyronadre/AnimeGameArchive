# Genshin Archive

Genshin Archive can run in two modes that share the same Spring backend and
Thymeleaf interface.

## Static game data

The official HoYoWiki is the catalogue and detail source for characters,
weapons, artifacts, and materials. Packaging creates a JSON snapshot containing
the current HoYoWiki records (but not image bytes). The snapshot is projected
into typed database tables on startup, followed by one live catalogue refresh.
Persisted data remains available if the live request fails.

HoYoWiki exposes page ids for materials, while GOOD inventories and source groups use
in-game item ids. [`material-identities.json`](src/main/resources/data/material-identities.json)
is the versioned bridge between both namespaces. It is bundled for offline/fresh installs and
the live catalogue refresh checks the configured raw GitHub URL for a newer copy, validates it,
and caches it before importing HoYoWiki data. New material mappings can therefore be shipped as
a data-file update without changing the application binary. Increment `catalogVersion` whenever
the file changes; the app only activates cached or remote catalogs newer than its current one.

The import is controlled with these properties:

```properties
genshin.content.hoyolab-wiki-enabled=true
genshin.content.hoyolab-catalog-sync-enabled=true
genshin.content.material-identity-catalog-url=https://raw.githubusercontent.com/Tyronadre/AnimeGameArchive/master/src/main/resources/data/material-identities.json
```

## Web mode

The normal web application keeps account authentication and the configured
MySQL database:

```powershell
.\gradlew.bat bootRun
```

## Desktop mode

Desktop mode runs the backend only on `127.0.0.1`, signs in a dedicated local
user automatically, and stores its file-backed H2 database and cache below
`%USERPROFILE%\.genshinapp\desktop`. A lightweight native launcher is shown
immediately while the local database and Spring services initialize. Use its
button to open the full interface in the default browser.

Set `GENSHIN_DESKTOP_HOME` before launch to use another local data directory.

For development:

```powershell
.\gradlew.bat desktopRun
```

The native local-tools panel is reserved for machine-only features such as
game-data capture. Its **Start capture** action launches the bundled,
Irminsul-derived Rust helper with a Windows administrator prompt. Start capture
before entering Genshin Impact; once character and inventory packets are found,
the resulting GOOD v3 snapshot is imported automatically and the helper stays
connected. Complete inventory item changes are then merged and saved locally
after a short debounce, covering artifact, weapon, and material updates and
deletions until **Stop capture** is selected. Character progression and equipped
item location changes still require a fresh capture. The system-tray icon remains
available as a secondary way to reopen or stop the application. The dashboard
keeps the ten latest detected inventory changes and refreshes that feed while it
is open. Packet heartbeats, recognized changes, and snapshot saves are shown in
the launcher's live log and persisted at
`%USERPROFILE%\.genshinapp\desktop\irminsul\live-capture.log`. For exploratory
capture work, the helper also writes decoded game-command inspection entries as
JSON Lines to
`%USERPROFILE%\.genshinapp\desktop\irminsul\packet-inspection.jsonl`. That file
is local-only but should be treated as private account/game data.

To create a Windows application image containing both the application and its
Java runtime:

```powershell
.\gradlew.bat packageDesktop
```

The runnable image is written to:

```text
build\desktop\Genshin Archive\Genshin Archive.exe
```

To create the smaller per-user Windows installer:

```powershell
.\gradlew.bat packageDesktopInstaller
```

The online installer contains the application and reuses the private Java 21
runtime from an existing installation when available, so upgrades do not
download Java again. On a fresh installation, it downloads the latest Eclipse
Temurin Java 21 JRE, verifies its SHA-256 checksum, and installs it privately
beside the application without modifying the system `PATH` or requiring
administrator access. The installer is written to `build\desktop-installer`.

For an installer that also contains Java and works without internet access, use:

```powershell
.\gradlew.bat packageDesktopOfflineInstaller
```

The `desktop` Spring profile is the boundary for future local-only features,
including the elevated Irminsul capture helper. Normal web deployments never
activate that profile.
