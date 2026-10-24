package de.tyro.genshinapp.desktop

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class H2DesktopConcurrentAccessTest {
    @Test
    fun `a separate database client can inspect the running desktop database`(@TempDir directory: Path) {
        val properties = Properties().apply {
            H2DesktopConcurrentAccessTest::class.java.classLoader
                .getResourceAsStream("application-desktop.properties")!!.use(::load)
        }
        val url = "jdbc:h2:file:${directory.resolve("genshinapp").toString().replace('\\', '/')}" +
            properties.getProperty("spring.datasource.url").substringAfter("/database/genshinapp")
        val source = directory.resolve("DesktopDatabaseProbe.java")
        Files.writeString(source, """
            import java.sql.*;
            class DesktopDatabaseProbe {
                public static void main(String[] args) throws Exception {
                    try (var connection = DriverManager.getConnection(args[0], "sa", "");
                         var statement = connection.createStatement()) {
                        if (args[1].equals("app")) {
                            statement.execute("CREATE TABLE inspection_probe (sample_value INT)");
                            statement.execute("INSERT INTO inspection_probe VALUES (41)");
                            System.out.println("READY");
                            System.out.flush();
                            System.in.read();
                        } else {
                            try (var result = statement.executeQuery("SELECT sample_value FROM inspection_probe")) {
                                result.next();
                                System.out.println("READ " + result.getInt(1));
                            }
                            statement.execute("UPDATE inspection_probe SET sample_value = 42");
                        }
                        try (var result = statement.executeQuery("SELECT sample_value FROM inspection_probe")) {
                            result.next();
                            System.out.println("FINAL " + result.getInt(1));
                        }
                    }
                }
            }
        """.trimIndent())
        val binary = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        val java = Path.of(System.getProperty("java.home"), "bin", binary).toString()
        val driverJar = Path.of(Class.forName("org.h2.Driver").protectionDomain.codeSource.location.toURI()).toString()
        fun start(mode: String, jdbcUrl: String) = ProcessBuilder(java, "-Dh2.bindAddress=127.0.0.1",
            "-cp", driverJar, source.toString(), jdbcUrl, mode).redirectErrorStream(true).start()
        val app = start("app", url)
        val reader = app.inputStream.bufferedReader()
        val waiter = Executors.newSingleThreadExecutor()
        var client: Process? = null
        try {
            val ready = waiter.submit<String> { reader.readLine() }.get(20, TimeUnit.SECONDS)
            assertEquals("READY", ready)
            val lock = Properties().apply {
                Files.newInputStream(directory.resolve("genshinapp.lock.db")).use(::load)
            }
            // H2 records the numeric loopback address as the hostname "localhost".
            val serverHost = lock.getProperty("server").substringBefore(':')
            assertTrue(serverHost in setOf("127.0.0.1", "localhost"), "Automatic server must bind to localhost")
            client = start("client", "$url;IFEXISTS=TRUE")
            assertTrue(client.waitFor(20, TimeUnit.SECONDS), "External client did not finish")
            val output = client.inputStream.bufferedReader().readText()
            assertEquals(0, client.exitValue(), output)
            assertTrue(output.contains("READ 41"), output)
            app.outputStream.write(10)
            app.outputStream.flush()
            assertTrue(app.waitFor(20, TimeUnit.SECONDS), "App connection did not close")
            assertEquals("FINAL 42", reader.readLine())
            assertEquals(0, app.exitValue())
        } finally {
            client?.destroyForcibly()
            app.destroyForcibly()
            waiter.shutdownNow()
        }
    }
}
