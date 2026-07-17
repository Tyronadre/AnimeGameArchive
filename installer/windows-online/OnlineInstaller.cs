using Microsoft.Win32;
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Compression;
using System.Net;
using System.Reflection;
using System.Security.Cryptography;
using System.Threading;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using System.Windows.Forms;

internal static class OnlineInstaller
{
    internal const string ProductName = "Another Anime Game Archive";

    [STAThread]
    private static int Main(string[] args)
    {
        ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12;
        if (HasArgument(args, "--silent"))
        {
            try
            {
                var options = new InstallOptions
                {
                    InstallDirectory = ArgumentValue(args, "--install-dir=") ?? DefaultInstallDirectory(),
                    DesktopShortcut = !HasArgument(args, "--no-shortcuts"),
                    LaunchAfterInstall = !HasArgument(args, "--no-launch"),
                    RegisterInstallation = !HasArgument(args, "--test"),
                };
                new InstallerEngine(null, null).InstallAsync(options).GetAwaiter().GetResult();
                return 0;
            }
            catch
            {
                return 1;
            }
        }

        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        Application.Run(new InstallerForm());
        return 0;
    }

    internal static string DefaultInstallDirectory()
    {
        return Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            ProductName);
    }

    private static bool HasArgument(IEnumerable<string> args, string expected)
    {
        foreach (string argument in args)
        {
            if (string.Equals(argument, expected, StringComparison.OrdinalIgnoreCase))
            {
                return true;
            }
        }
        return false;
    }

    private static string ArgumentValue(IEnumerable<string> args, string prefix)
    {
        foreach (string argument in args)
        {
            if (argument.StartsWith(prefix, StringComparison.OrdinalIgnoreCase))
            {
                return argument.Substring(prefix.Length).Trim('"');
            }
        }
        return null;
    }
}

internal sealed class InstallerForm : Form
{
    private readonly TextBox installDirectory = new TextBox();
    private readonly Button browseButton = new Button();
    private readonly CheckBox desktopShortcut = new CheckBox();
    private readonly CheckBox launchAfterInstall = new CheckBox();
    private readonly ProgressBar progress = new ProgressBar();
    private readonly Label status = new Label();
    private readonly Button installButton = new Button();
    private readonly Button cancelButton = new Button();
    private InstallerEngine engine;
    private bool installing;

    internal InstallerForm()
    {
        Text = OnlineInstaller.ProductName + " Installer";
        ClientSize = new Size(590, 330);
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }

        var heading = new Label
        {
            Text = "Install " + OnlineInstaller.ProductName,
            Font = new Font(Font.FontFamily, 15, FontStyle.Bold),
            AutoSize = true,
            Location = new Point(24, 22),
        };
        var description = new Label
        {
            Text = "The application files are included. A private Eclipse Temurin Java 21 " +
                "runtime will be downloaded and verified during installation.",
            AutoSize = false,
            Size = new Size(540, 42),
            Location = new Point(26, 60),
        };
        var pathLabel = new Label
        {
            Text = "Install location",
            AutoSize = true,
            Location = new Point(26, 111),
        };
        installDirectory.Text = OnlineInstaller.DefaultInstallDirectory();
        installDirectory.Location = new Point(26, 132);
        installDirectory.Size = new Size(445, 24);
        browseButton.Text = "Browse…";
        browseButton.Location = new Point(480, 130);
        browseButton.Size = new Size(84, 27);
        browseButton.Click += Browse;

        desktopShortcut.Text = "Create a desktop shortcut";
        desktopShortcut.Checked = true;
        desktopShortcut.AutoSize = true;
        desktopShortcut.Location = new Point(26, 170);
        launchAfterInstall.Text = "Launch when installation finishes";
        launchAfterInstall.Checked = true;
        launchAfterInstall.AutoSize = true;
        launchAfterInstall.Location = new Point(250, 170);

        progress.Location = new Point(26, 207);
        progress.Size = new Size(538, 22);
        progress.Minimum = 0;
        progress.Maximum = 100;
        status.Text = "Ready to install. Internet access is required.";
        status.AutoEllipsis = true;
        status.Location = new Point(26, 236);
        status.Size = new Size(538, 36);

        installButton.Text = "Install";
        installButton.Location = new Point(382, 284);
        installButton.Size = new Size(86, 30);
        installButton.Click += Install;
        cancelButton.Text = "Cancel";
        cancelButton.Location = new Point(478, 284);
        cancelButton.Size = new Size(86, 30);
        cancelButton.Click += Cancel;
        AcceptButton = installButton;
        CancelButton = cancelButton;

        Controls.AddRange(new Control[]
        {
            heading, description, pathLabel, installDirectory, browseButton,
            desktopShortcut, launchAfterInstall, progress, status, installButton, cancelButton,
        });
        FormClosing += OnClosing;
    }

    private void Browse(object sender, EventArgs eventArgs)
    {
        using (var dialog = new FolderBrowserDialog())
        {
            dialog.Description = "Choose the application installation directory";
            dialog.SelectedPath = installDirectory.Text;
            if (dialog.ShowDialog(this) == DialogResult.OK)
            {
                installDirectory.Text = dialog.SelectedPath;
            }
        }
    }

    private async void Install(object sender, EventArgs eventArgs)
    {
        if (installing)
        {
            return;
        }
        installing = true;
        SetInputsEnabled(false);
        engine = new InstallerEngine(UpdateStatus, UpdateProgress);
        try
        {
            await engine.InstallAsync(new InstallOptions
            {
                InstallDirectory = installDirectory.Text,
                DesktopShortcut = desktopShortcut.Checked,
                LaunchAfterInstall = launchAfterInstall.Checked,
                RegisterInstallation = true,
            });
            progress.Value = 100;
            status.Text = "Installation complete.";
            MessageBox.Show(
                OnlineInstaller.ProductName + " was installed successfully.",
                OnlineInstaller.ProductName,
                MessageBoxButtons.OK,
                MessageBoxIcon.Information);
            DialogResult = DialogResult.OK;
            Close();
        }
        catch (OperationCanceledException)
        {
            status.Text = "Installation cancelled.";
            SetInputsEnabled(true);
        }
        catch (Exception exception)
        {
            status.Text = "Installation failed.";
            MessageBox.Show(
                "Installation could not be completed.\n\n" + exception.Message,
                OnlineInstaller.ProductName,
                MessageBoxButtons.OK,
                MessageBoxIcon.Error);
            SetInputsEnabled(true);
        }
        finally
        {
            installing = false;
            engine = null;
        }
    }

    private void Cancel(object sender, EventArgs eventArgs)
    {
        if (!installing)
        {
            Close();
            return;
        }
        status.Text = "Cancelling…";
        cancelButton.Enabled = false;
        if (engine != null)
        {
            engine.Cancel();
        }
    }

    private void OnClosing(object sender, FormClosingEventArgs eventArgs)
    {
        if (installing)
        {
            eventArgs.Cancel = true;
            Cancel(sender, EventArgs.Empty);
        }
    }

    private void SetInputsEnabled(bool enabled)
    {
        installDirectory.Enabled = enabled;
        browseButton.Enabled = enabled;
        desktopShortcut.Enabled = enabled;
        launchAfterInstall.Enabled = enabled;
        installButton.Enabled = enabled;
        cancelButton.Enabled = true;
    }

    private void UpdateStatus(string text)
    {
        if (InvokeRequired)
        {
            BeginInvoke(new Action<string>(UpdateStatus), text);
            return;
        }
        status.Text = text;
    }

    private void UpdateProgress(int value)
    {
        if (InvokeRequired)
        {
            BeginInvoke(new Action<int>(UpdateProgress), value);
            return;
        }
        progress.Value = Math.Max(progress.Minimum, Math.Min(progress.Maximum, value));
    }
}

internal sealed class InstallOptions
{
    internal string InstallDirectory { get; set; }
    internal bool DesktopShortcut { get; set; }
    internal bool LaunchAfterInstall { get; set; }
    internal bool RegisterInstallation { get; set; }
}

internal sealed class RuntimePackage
{
    internal string Link { get; set; }
    internal string Checksum { get; set; }
    internal long Size { get; set; }
    internal string Name { get; set; }
}

internal sealed class InstallerEngine
{
    private const string MetadataUrl =
        "https://api.adoptium.net/v3/assets/latest/21/hotspot" +
        "?architecture=x64&image_type=jre&os=windows&vendor=eclipse";
    private const string RegistryPath =
        @"Software\Microsoft\Windows\CurrentVersion\Uninstall\AnotherAnimeGameArchive";
    private readonly Action<string> reportStatus;
    private readonly Action<int> reportProgress;
    private volatile bool cancelled;
    private WebClient activeDownload;

    internal InstallerEngine(Action<string> reportStatus, Action<int> reportProgress)
    {
        this.reportStatus = reportStatus;
        this.reportProgress = reportProgress;
    }

    internal void Cancel()
    {
        cancelled = true;
        if (activeDownload != null)
        {
            activeDownload.CancelAsync();
        }
    }

    internal async Task InstallAsync(InstallOptions options)
    {
        string installDirectory = ValidateInstallDirectory(options.InstallDirectory);
        string temporaryDirectory = Path.Combine(
            Path.GetTempPath(),
            "another-anime-game-archive-" + Guid.NewGuid().ToString("N"));
        string runtimeArchive = Path.Combine(temporaryDirectory, "temurin-jre.zip");
        string stagedInstallation = Path.Combine(temporaryDirectory, "installation");
        Directory.CreateDirectory(temporaryDirectory);

        try
        {
            ReportStatus("Finding the latest Eclipse Temurin Java 21 runtime…");
            RuntimePackage package = await Task.Run(() => FetchRuntimePackage());
            ThrowIfCancelled();

            ReportStatus(
                "Downloading " + package.Name + " (" + FormatSize(package.Size) + ")…");
            using (var client = CreateWebClient())
            {
                activeDownload = client;
                client.DownloadProgressChanged += (sender, eventArgs) =>
                    ReportProgress((int)Math.Round(eventArgs.ProgressPercentage * 0.70));
                await client.DownloadFileTaskAsync(new Uri(package.Link), runtimeArchive);
                activeDownload = null;
            }
            ThrowIfCancelled();

            ReportStatus("Verifying the Java runtime checksum…");
            await Task.Run(() => VerifyChecksum(runtimeArchive, package.Checksum));
            ThrowIfCancelled();

            ReportProgress(73);
            ReportStatus("Preparing application files…");
            await Task.Run(() =>
            {
                ExtractEmbeddedPayload(stagedInstallation);
                ExtractRuntime(runtimeArchive, Path.Combine(stagedInstallation, "runtime"));
                ValidateRuntime(Path.Combine(stagedInstallation, "runtime"));
            });
            ThrowIfCancelled();

            ReportProgress(88);
            ReportStatus("Installing application files…");
            await Task.Run(() => Deploy(stagedInstallation, installDirectory));
            ThrowIfCancelled();

            if (options.RegisterInstallation)
            {
                ReportStatus("Creating shortcuts…");
                CreateShortcuts(installDirectory, options.DesktopShortcut);
                RegisterInstallation(installDirectory);
            }

            ReportProgress(100);
            if (options.LaunchAfterInstall)
            {
                Process.Start(Path.Combine(
                    installDirectory,
                    OnlineInstaller.ProductName + ".exe"));
            }
        }
        finally
        {
            activeDownload = null;
            TryDeleteDirectory(temporaryDirectory);
        }
    }

    private static RuntimePackage FetchRuntimePackage()
    {
        string json;
        using (var client = CreateWebClient())
        {
            json = client.DownloadString(MetadataUrl);
        }
        object[] releases = new JavaScriptSerializer().DeserializeObject(json) as object[];
        if (releases == null || releases.Length == 0)
        {
            throw new InvalidOperationException("Adoptium did not return a Java 21 JRE.");
        }

        var release = releases[0] as Dictionary<string, object>;
        var binary = release == null ? null : release["binary"] as Dictionary<string, object>;
        var package = binary == null ? null : binary["package"] as Dictionary<string, object>;
        if (package == null)
        {
            throw new InvalidOperationException("The Adoptium response did not contain a runtime package.");
        }
        return new RuntimePackage
        {
            Link = Convert.ToString(package["link"]),
            Checksum = Convert.ToString(package["checksum"]),
            Name = Convert.ToString(package["name"]),
            Size = Convert.ToInt64(package["size"]),
        };
    }

    private static WebClient CreateWebClient()
    {
        var client = new WebClient();
        client.Headers[HttpRequestHeader.UserAgent] =
            "AnotherAnimeGameArchive-Installer/1.0";
        return client;
    }

    private static void VerifyChecksum(string path, string expected)
    {
        string actual;
        using (var stream = File.OpenRead(path))
        using (var sha256 = SHA256.Create())
        {
            actual = BitConverter.ToString(sha256.ComputeHash(stream))
                .Replace("-", "")
                .ToLowerInvariant();
        }
        if (!string.Equals(actual, expected.Trim(), StringComparison.OrdinalIgnoreCase))
        {
            throw new InvalidDataException(
                "The downloaded Java runtime failed SHA-256 verification.");
        }
    }

    private static void ExtractEmbeddedPayload(string destination)
    {
        using (Stream resource = Assembly.GetExecutingAssembly()
            .GetManifestResourceStream("AppPayload.zip"))
        {
            if (resource == null)
            {
                throw new InvalidDataException("The embedded application payload is missing.");
            }
            ExtractArchive(resource, destination, false);
        }
    }

    private static void ExtractRuntime(string archive, string destination)
    {
        using (Stream stream = File.OpenRead(archive))
        {
            ExtractArchive(stream, destination, true);
        }
    }

    private static void ExtractArchive(Stream stream, string destination, bool stripFirstDirectory)
    {
        Directory.CreateDirectory(destination);
        string destinationRoot = Path.GetFullPath(destination)
            .TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
        using (var archive = new ZipArchive(stream, ZipArchiveMode.Read, true))
        {
            foreach (ZipArchiveEntry entry in archive.Entries)
            {
                string relative = entry.FullName.Replace('\\', '/');
                if (stripFirstDirectory)
                {
                    int separator = relative.IndexOf('/');
                    if (separator < 0)
                    {
                        continue;
                    }
                    relative = relative.Substring(separator + 1);
                }
                if (string.IsNullOrWhiteSpace(relative))
                {
                    continue;
                }

                string target = Path.GetFullPath(Path.Combine(
                    destinationRoot,
                    relative.Replace('/', Path.DirectorySeparatorChar)));
                if (!target.StartsWith(destinationRoot, StringComparison.OrdinalIgnoreCase))
                {
                    throw new InvalidDataException("An archive entry escaped the installation directory.");
                }
                if (string.IsNullOrEmpty(entry.Name))
                {
                    Directory.CreateDirectory(target);
                    continue;
                }
                Directory.CreateDirectory(Path.GetDirectoryName(target));
                using (Stream input = entry.Open())
                using (Stream output = File.Create(target))
                {
                    input.CopyTo(output);
                }
            }
        }
    }

    private static void ValidateRuntime(string runtimeDirectory)
    {
        string java = Path.Combine(runtimeDirectory, "bin", "java.exe");
        string javaw = Path.Combine(runtimeDirectory, "bin", "javaw.exe");
        if (!File.Exists(java) || !File.Exists(javaw))
        {
            throw new InvalidDataException("The downloaded archive did not contain a Windows JRE.");
        }

        var process = Process.Start(new ProcessStartInfo
        {
            FileName = java,
            Arguments = "-version",
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardError = true,
            RedirectStandardOutput = true,
        });
        string version = process.StandardError.ReadToEnd() + process.StandardOutput.ReadToEnd();
        if (!process.WaitForExit(15000))
        {
            process.Kill();
            throw new InvalidDataException("The downloaded Java runtime did not start.");
        }
        if (process.ExitCode != 0 || version.IndexOf("21.", StringComparison.Ordinal) < 0)
        {
            throw new InvalidDataException("The downloaded runtime is not Java 21.");
        }
    }

    private static string ValidateInstallDirectory(string requested)
    {
        if (string.IsNullOrWhiteSpace(requested))
        {
            throw new ArgumentException("Choose an installation directory.");
        }
        string directory = Path.GetFullPath(
            Environment.ExpandEnvironmentVariables(requested.Trim().Trim('"')))
            .TrimEnd(Path.DirectorySeparatorChar);
        if (string.Equals(directory, Path.GetPathRoot(directory), StringComparison.OrdinalIgnoreCase))
        {
            throw new ArgumentException("The root of a drive cannot be used as the installation directory.");
        }
        if (Directory.Exists(directory) && HasEntries(directory) && !IsExistingInstall(directory))
        {
            throw new IOException(
                "The selected directory contains unrelated files. Choose an empty directory.");
        }
        return directory;
    }

    private static bool HasEntries(string directory)
    {
        using (IEnumerator<string> entries = Directory.EnumerateFileSystemEntries(directory).GetEnumerator())
        {
            return entries.MoveNext();
        }
    }

    private static bool IsExistingInstall(string directory)
    {
        return File.Exists(Path.Combine(directory, "app", "version.txt")) &&
            File.Exists(Path.Combine(directory, OnlineInstaller.ProductName + ".exe"));
    }

    private static void Deploy(string stagedInstallation, string installDirectory)
    {
        string parent = Path.GetDirectoryName(installDirectory);
        Directory.CreateDirectory(parent);
        string candidate = installDirectory + ".installing-" + Guid.NewGuid().ToString("N");
        string backup = installDirectory + ".previous";
        TryDeleteDirectory(candidate);
        TryDeleteDirectory(backup);
        CopyDirectory(stagedInstallation, candidate);

        bool backedUp = false;
        try
        {
            if (Directory.Exists(installDirectory))
            {
                if (!HasEntries(installDirectory))
                {
                    Directory.Delete(installDirectory);
                }
                else
                {
                    Directory.Move(installDirectory, backup);
                    backedUp = true;
                }
            }
            Directory.Move(candidate, installDirectory);
            TryDeleteDirectory(backup);
        }
        catch
        {
            TryDeleteDirectory(candidate);
            if (!Directory.Exists(installDirectory) && backedUp && Directory.Exists(backup))
            {
                Directory.Move(backup, installDirectory);
            }
            throw new IOException(
                "Application files could not be replaced. Close the application and try again.");
        }
    }

    private static void CopyDirectory(string source, string destination)
    {
        Directory.CreateDirectory(destination);
        foreach (string directory in Directory.GetDirectories(source, "*", SearchOption.AllDirectories))
        {
            Directory.CreateDirectory(destination + directory.Substring(source.Length));
        }
        foreach (string file in Directory.GetFiles(source, "*", SearchOption.AllDirectories))
        {
            string target = destination + file.Substring(source.Length);
            Directory.CreateDirectory(Path.GetDirectoryName(target));
            File.Copy(file, target, true);
        }
    }

    private static void CreateShortcuts(string installDirectory, bool includeDesktop)
    {
        string launcher = Path.Combine(
            installDirectory,
            OnlineInstaller.ProductName + ".exe");
        string startMenuDirectory = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.Programs),
            OnlineInstaller.ProductName);
        Directory.CreateDirectory(startMenuDirectory);
        CreateShortcut(
            Path.Combine(startMenuDirectory, OnlineInstaller.ProductName + ".lnk"),
            launcher,
            installDirectory);

        string desktop = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory),
            OnlineInstaller.ProductName + ".lnk");
        if (includeDesktop)
        {
            CreateShortcut(desktop, launcher, installDirectory);
        }
        else if (File.Exists(desktop))
        {
            File.Delete(desktop);
        }
    }

    private static void CreateShortcut(string shortcutPath, string target, string workingDirectory)
    {
        Type shellType = Type.GetTypeFromProgID("WScript.Shell");
        if (shellType == null)
        {
            throw new InvalidOperationException("Windows Script Host is unavailable.");
        }
        dynamic shell = Activator.CreateInstance(shellType);
        dynamic shortcut = shell.CreateShortcut(shortcutPath);
        shortcut.TargetPath = target;
        shortcut.WorkingDirectory = workingDirectory;
        shortcut.IconLocation = target + ",0";
        shortcut.Description = OnlineInstaller.ProductName;
        shortcut.Save();
    }

    private static void RegisterInstallation(string installDirectory)
    {
        string version = File.ReadAllText(Path.Combine(installDirectory, "app", "version.txt")).Trim();
        using (RegistryKey key = Registry.CurrentUser.CreateSubKey(RegistryPath))
        {
            key.SetValue("DisplayName", OnlineInstaller.ProductName);
            key.SetValue("DisplayVersion", version);
            key.SetValue("Publisher", "Tyro");
            key.SetValue("InstallLocation", installDirectory);
            key.SetValue(
                "DisplayIcon",
                Path.Combine(installDirectory, OnlineInstaller.ProductName + ".exe"));
            key.SetValue(
                "UninstallString",
                "\"" + Path.Combine(installDirectory, "Uninstall.exe") + "\"");
            key.SetValue("NoModify", 1, RegistryValueKind.DWord);
            key.SetValue("NoRepair", 1, RegistryValueKind.DWord);
            key.SetValue(
                "EstimatedSize",
                Math.Min(int.MaxValue, DirectorySize(installDirectory) / 1024),
                RegistryValueKind.DWord);
        }
    }

    private static int DirectorySize(string directory)
    {
        long total = 0;
        foreach (string file in Directory.GetFiles(directory, "*", SearchOption.AllDirectories))
        {
            total += new FileInfo(file).Length;
        }
        return (int)Math.Min(int.MaxValue, total);
    }

    private static string FormatSize(long bytes)
    {
        return (bytes / 1024d / 1024d).ToString("0.0") + " MiB";
    }

    private static void TryDeleteDirectory(string directory)
    {
        try
        {
            if (Directory.Exists(directory))
            {
                Directory.Delete(directory, true);
            }
        }
        catch
        {
            // Cleanup is best effort; installation errors are reported at the operation that failed.
        }
    }

    private void ThrowIfCancelled()
    {
        if (cancelled)
        {
            throw new OperationCanceledException();
        }
    }

    private void ReportStatus(string text)
    {
        if (reportStatus != null)
        {
            reportStatus(text);
        }
    }

    private void ReportProgress(int value)
    {
        if (reportProgress != null)
        {
            reportProgress(value);
        }
    }
}
