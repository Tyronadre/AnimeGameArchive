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

Desktop mode runs the backend only on `127.0.0.1`, hosts the existing web
interface inside a native desktop window, signs in a dedicated local user
automatically, and stores its file-backed H2 database and cache below
`%USERPROFILE%\.genshinapp\desktop`.

Set `GENSHIN_DESKTOP_HOME` before launch to use another local data directory.

For development:

```powershell
.\gradlew.bat desktopRun
```

The native local-tools panel is reserved for machine-only features such as
game-data capture. Its **Start capture** action launches the bundled,
Irminsul-derived Rust helper with a Windows administrator prompt. Start capture
before entering Genshin Impact; once character and inventory packets are found,
the resulting GOOD v3 snapshot is imported automatically. The system-tray icon
remains available as a secondary way to reopen or stop the application.

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
