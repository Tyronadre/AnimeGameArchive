# Genshin Archive

Genshin Archive can run in two modes that share the same Spring backend and
Thymeleaf interface.

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
`%USERPROFILE%\.genshinapp\desktop\irminsul\live-capture.log`.

To create a Windows application image containing both the application and its
Java runtime:

```powershell
.\gradlew.bat packageDesktop
```

The runnable image is written to:

```text
build\desktop\Genshin Archive\Genshin Archive.exe
```

The `desktop` Spring profile is the boundary for future local-only features,
including the elevated Irminsul capture helper. Normal web deployments never
activate that profile.
