using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Windows.Forms;

internal static class Launcher
{
    private const string ProductName = "Another Anime Game Archive";

    [STAThread]
    private static int Main(string[] args)
    {
        try
        {
            string installDirectory = AppDomain.CurrentDomain.BaseDirectory;
            string java = Path.Combine(installDirectory, "runtime", "bin", "javaw.exe");
            string application = Path.Combine(installDirectory, "app", "app.jar");
            string launcher = Path.Combine(installDirectory, ProductName + ".exe");

            if (!File.Exists(java))
            {
                throw new FileNotFoundException(
                    "The private Java 21 runtime is missing. Please run the installer again.",
                    java);
            }
            if (!File.Exists(application))
            {
                throw new FileNotFoundException(
                    "The application files are missing. Please run the installer again.",
                    application);
            }

            var arguments = new List<string>
            {
                "-Dfile.encoding=UTF-8",
                "-Djava.awt.headless=false",
                "-Djpackage.app-path=" + launcher,
                "-jar",
                application,
            };
            arguments.AddRange(args);

            var startInfo = new ProcessStartInfo
            {
                FileName = java,
                Arguments = JoinArguments(arguments),
                WorkingDirectory = installDirectory,
                UseShellExecute = false,
                CreateNoWindow = true,
            };
            Process.Start(startInfo);
            return 0;
        }
        catch (Exception exception)
        {
            MessageBox.Show(
                exception.Message,
                ProductName,
                MessageBoxButtons.OK,
                MessageBoxIcon.Error);
            return 1;
        }
    }

    private static string JoinArguments(IEnumerable<string> arguments)
    {
        var result = new StringBuilder();
        foreach (string argument in arguments)
        {
            if (result.Length > 0)
            {
                result.Append(' ');
            }
            result.Append(QuoteArgument(argument));
        }
        return result.ToString();
    }

    private static string QuoteArgument(string argument)
    {
        if (argument.Length > 0 &&
            argument.IndexOfAny(new[] { ' ', '\t', '\n', '\v', '"' }) < 0)
        {
            return argument;
        }

        var result = new StringBuilder("\"");
        int backslashes = 0;
        foreach (char character in argument)
        {
            if (character == '\\')
            {
                backslashes++;
            }
            else if (character == '"')
            {
                result.Append('\\', backslashes * 2 + 1);
                result.Append('"');
                backslashes = 0;
            }
            else
            {
                result.Append('\\', backslashes);
                result.Append(character);
                backslashes = 0;
            }
        }
        result.Append('\\', backslashes * 2);
        result.Append('"');
        return result.ToString();
    }
}
