using Microsoft.Win32;
using System;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Windows.Forms;

internal static class Uninstaller
{
    private const string ProductName = "Another Anime Game Archive";
    private const string UninstallRegistryPath =
        @"Software\Microsoft\Windows\CurrentVersion\Uninstall\AnotherAnimeGameArchive";

    [STAThread]
    private static int Main(string[] args)
    {
        bool silent = false;
        foreach (string argument in args)
        {
            if (string.Equals(argument, "--silent", StringComparison.OrdinalIgnoreCase))
            {
                silent = true;
            }
        }
        string installDirectory = Path.GetFullPath(AppDomain.CurrentDomain.BaseDirectory)
            .TrimEnd(Path.DirectorySeparatorChar);
        if (!IsApplicationDirectory(installDirectory))
        {
            if (!silent)
            {
                MessageBox.Show(
                    "The installation directory could not be verified, so no files were removed.",
                    ProductName,
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Error);
            }
            else
            {
                WriteSilentError("Installation directory verification failed: " + installDirectory);
            }
            return 1;
        }

        if (!silent && MessageBox.Show(
                "Remove " + ProductName + " from this computer?\n\n" +
                "Your database and imported data will be kept.",
                "Uninstall " + ProductName,
                MessageBoxButtons.YesNo,
                MessageBoxIcon.Question,
                MessageBoxDefaultButton.Button2) != DialogResult.Yes)
        {
            return 0;
        }

        try
        {
            EnsureApplicationIsStopped(installDirectory);
            DeleteShortcuts(installDirectory);
            Registry.CurrentUser.DeleteSubKeyTree(UninstallRegistryPath, false);

            string script = Path.Combine(
                Path.GetTempPath(),
                "remove-another-anime-game-archive-" + Guid.NewGuid().ToString("N") + ".cmd");
            File.WriteAllText(
                script,
                "@echo off\r\n" +
                "ping 127.0.0.1 -n 3 > nul\r\n" +
                "rmdir /s /q \"" + installDirectory.Replace("\"", "") + "\"\r\n" +
                "del /q \"%~f0\"\r\n",
                Encoding.ASCII);
            Process.Start(new ProcessStartInfo
            {
                FileName = Environment.GetEnvironmentVariable("COMSPEC") ?? "cmd.exe",
                Arguments = "/d /c \"\"" + script + "\"\"",
                WindowStyle = ProcessWindowStyle.Hidden,
                CreateNoWindow = true,
                UseShellExecute = false,
            });
            return 0;
        }
        catch (Exception exception)
        {
            if (!silent)
            {
                MessageBox.Show(
                    "Uninstallation could not be completed. Close the application and try again.\n\n" +
                    exception.Message,
                    ProductName,
                    MessageBoxButtons.OK,
                    MessageBoxIcon.Error);
            }
            else
            {
                WriteSilentError(exception.ToString());
            }
            return 1;
        }
    }

    private static bool IsApplicationDirectory(string directory)
    {
        return File.Exists(Path.Combine(directory, ProductName + ".exe")) &&
            File.Exists(Path.Combine(directory, "app", "app.jar")) &&
            File.Exists(Path.Combine(directory, "app", "version.txt"));
    }

    private static void EnsureApplicationIsStopped(string installDirectory)
    {
        string virtualMachine = Path.Combine(
            installDirectory,
            "runtime",
            "bin",
            "server",
            "jvm.dll");
        if (!File.Exists(virtualMachine))
        {
            return;
        }
        try
        {
            using (File.Open(virtualMachine, FileMode.Open, FileAccess.Read, FileShare.None))
            {
            }
        }
        catch (IOException)
        {
            throw new IOException("The application is still running. Close it and try again.");
        }
    }

    private static void DeleteShortcuts(string installDirectory)
    {
        string desktopShortcut = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory),
            ProductName + ".lnk");
        string startMenuDirectory = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.Programs),
            ProductName);
        DeleteShortcutIfOwned(desktopShortcut, installDirectory);
        if (Directory.Exists(startMenuDirectory))
        {
            DeleteShortcutIfOwned(
                Path.Combine(startMenuDirectory, ProductName + ".lnk"),
                installDirectory);
            if (Directory.GetFileSystemEntries(startMenuDirectory).Length == 0)
            {
                Directory.Delete(startMenuDirectory);
            }
        }
    }

    private static void DeleteShortcutIfOwned(string shortcutPath, string installDirectory)
    {
        if (!File.Exists(shortcutPath))
        {
            return;
        }
        Type shellType = Type.GetTypeFromProgID("WScript.Shell");
        if (shellType == null)
        {
            return;
        }
        dynamic shell = Activator.CreateInstance(shellType);
        dynamic shortcut = shell.CreateShortcut(shortcutPath);
        string target = Convert.ToString(shortcut.TargetPath);
        string ownedRoot = Path.GetFullPath(installDirectory)
            .TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
        if (!string.IsNullOrWhiteSpace(target) &&
            Path.GetFullPath(target).StartsWith(ownedRoot, StringComparison.OrdinalIgnoreCase))
        {
            File.Delete(shortcutPath);
        }
    }

    private static void WriteSilentError(string message)
    {
        try
        {
            File.WriteAllText(
                Path.Combine(Path.GetTempPath(), "another-anime-game-archive-uninstall-error.log"),
                message);
        }
        catch
        {
        }
    }
}
