namespace BetterRNP.Core;

/// <summary>
/// All runtime files live under one root. The default is the executable directory so a published
/// application stays portable; tests can inject a temporary directory.
/// </summary>
public sealed class AppPaths
{
    public const string RootOverrideEnvironmentVariable = "BETTERRNP_HOME";

    public AppPaths(string? root = null)
    {
        var configured = root;
        if (string.IsNullOrWhiteSpace(configured))
        {
            configured = Environment.GetEnvironmentVariable(RootOverrideEnvironmentVariable);
        }

        if (string.IsNullOrWhiteSpace(configured))
        {
            // The original Java build stored data under the working directory. Prefer that
            // folder when it already contains data, while keeping portable exe-adjacent storage
            // as the default for a fresh installation.
            var baseDirectory = AppContext.BaseDirectory;
            var currentDirectory = Environment.CurrentDirectory;
            var currentData = Path.Combine(currentDirectory, "data");
            configured = !Path.GetFullPath(baseDirectory).Equals(
                    Path.GetFullPath(currentDirectory), StringComparison.OrdinalIgnoreCase)
                && Directory.Exists(currentData)
                && (File.Exists(Path.Combine(currentData, "config.properties"))
                    || Directory.Exists(Path.Combine(currentData, "schemes")))
                    ? currentDirectory
                    : baseDirectory;
        }

        Root = Path.GetFullPath(string.IsNullOrWhiteSpace(configured)
            ? AppContext.BaseDirectory
            : configured);
    }

    public string Root { get; }
    public string DataDir => Path.Combine(Root, "data");
    public string SchemesDir => Path.Combine(DataDir, "schemes");
    public string BackupDir => Path.Combine(DataDir, "backup");
    public string SchemesIndexFile => Path.Combine(SchemesDir, "index.txt");
    public string ConfigFile => Path.Combine(DataDir, "config.properties");
    public string LogDir => Path.Combine(Root, "log");
    public string LogFile => Path.Combine(LogDir, "Modifylog.txt");
    public string ThemesDir => Path.Combine(DataDir, "themes");
    public string ThemeImagesDir => Path.Combine(ThemesDir, "images");
    public string ThemeConfigFile => Path.Combine(ThemesDir, "theme.properties");
    public string PluginsDir => Path.Combine(Root, "plugins");

    public void EnsureDirectories()
    {
        Directory.CreateDirectory(DataDir);
        Directory.CreateDirectory(SchemesDir);
        Directory.CreateDirectory(BackupDir);
        Directory.CreateDirectory(LogDir);
        Directory.CreateDirectory(ThemesDir);
        Directory.CreateDirectory(ThemeImagesDir);
        Directory.CreateDirectory(PluginsDir);
    }
}
