using System.Diagnostics;
using System.IO.Compression;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Reflection;
using System.Text;
using System.Text.Json;

namespace BetterRNP.Core;

public enum UpdateSource
{
    Official,
    GhProxy
}

public enum UpdateAssetKind
{
    SelfContained,
    FrameworkDependent
}

public sealed record UpdateAsset(string Name, long SizeBytes, Uri DownloadUri);

public sealed record UpdateCheckResult(
    bool UpdateAvailable,
    string LatestTag,
    Version? LatestVersion,
    Uri ReleasePageUri,
    string ReleaseNotes,
    UpdateAsset? SelfContainedAsset,
    UpdateAsset? FrameworkDependentAsset);

/// <summary>
/// GitHub Releases based update check/download/apply flow, modeled after the FuckETS updater.
/// </summary>
public sealed class UpdateService
{
    public const string RepoOwner = "TakaHoshino";
    public const string RepoName = "BetterRandomNamePicker";
    public const string GhProxyPrefix = "https://gh-proxy.com/";

    private readonly LogService _log;
    private readonly HttpClient _http;
    private readonly string _updateDirectory;

    public UpdateService(LogService log, AppPaths paths)
    {
        _log = log;
        _http = new HttpClient { Timeout = TimeSpan.FromMinutes(15) };
        _http.DefaultRequestHeaders.UserAgent.ParseAdd($"BetterRNP/{GetCurrentTag()}");
        _updateDirectory = Path.Combine(paths.DataDir, "update-cache");
    }

    public static Version GetCurrentVersion()
    {
        var version = typeof(UpdateService).Assembly.GetName().Version
                      ?? new Version(0, 0, 0);
        return Normalize(version);
    }

    public static string GetCurrentTag()
    {
        var version = GetCurrentVersion();
        return $"v{version.Major}.{version.Minor}.{version.Build}";
    }

    public static Version? ParseTag(string? tag)
    {
        if (string.IsNullOrWhiteSpace(tag))
        {
            return null;
        }
        var text = tag.Trim();
        if (text.StartsWith('v') || text.StartsWith('V'))
        {
            text = text[1..];
        }
        return Version.TryParse(text, out var version) ? Normalize(version) : null;
    }

    public async Task<UpdateCheckResult> CheckAsync(CancellationToken cancellationToken = default)
    {
        using var cts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        cts.CancelAfter(TimeSpan.FromSeconds(20));

        var url = $"https://api.github.com/repos/{RepoOwner}/{RepoName}/releases/latest";
        using var request = new HttpRequestMessage(HttpMethod.Get, url);
        request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/vnd.github+json"));
        using var response = await _http.SendAsync(request, cts.Token);
        response.EnsureSuccessStatusCode();
        var json = await response.Content.ReadAsStringAsync(cts.Token);
        return EvaluateReleaseJson(json, GetCurrentVersion());
    }

    /// <summary>Parses a GitHub release payload. Public for deterministic tests and diagnostics.</summary>
    public static UpdateCheckResult EvaluateReleaseJson(string json, Version currentVersion)
    {
        using var document = JsonDocument.Parse(json);
        var root = document.RootElement;
        var tag = root.TryGetProperty("tag_name", out var tagElement)
            ? tagElement.GetString() ?? string.Empty
            : string.Empty;
        var latest = ParseTag(tag);
        var page = root.TryGetProperty("html_url", out var html)
                   && html.GetString() is { Length: > 0 } htmlUrl
            ? htmlUrl
            : $"https://github.com/{RepoOwner}/{RepoName}/releases/latest";
        var notes = root.TryGetProperty("body", out var body)
            ? body.GetString() ?? string.Empty
            : string.Empty;

        UpdateAsset? selfContained = null;
        UpdateAsset? frameworkDependent = null;
        if (root.TryGetProperty("assets", out var assets) && assets.ValueKind == JsonValueKind.Array)
        {
            foreach (var asset in assets.EnumerateArray())
            {
                var name = asset.TryGetProperty("name", out var nameElement)
                    ? nameElement.GetString() ?? string.Empty
                    : string.Empty;
                var url = asset.TryGetProperty("browser_download_url", out var urlElement)
                    ? urlElement.GetString()
                    : null;
                var size = asset.TryGetProperty("size", out var sizeElement)
                           && sizeElement.TryGetInt64(out var bytes)
                    ? bytes
                    : 0;
                if (string.IsNullOrWhiteSpace(url) || !Uri.TryCreate(url, UriKind.Absolute, out var uri))
                {
                    continue;
                }

                if (name.Contains("self-contained", StringComparison.OrdinalIgnoreCase))
                {
                    selfContained = new UpdateAsset(name, size, uri);
                }
                else if (name.Contains("framework-dependent", StringComparison.OrdinalIgnoreCase))
                {
                    frameworkDependent = new UpdateAsset(name, size, uri);
                }
            }
        }

        var available = latest is not null && latest > Normalize(currentVersion);
        return new UpdateCheckResult(
            available,
            tag,
            latest,
            new Uri(page),
            notes,
            selfContained,
            frameworkDependent);
    }

    public Uri ResolveDownloadUri(UpdateAsset asset, UpdateSource source)
        => source == UpdateSource.GhProxy
            ? new Uri(GhProxyPrefix + asset.DownloadUri.AbsoluteUri)
            : asset.DownloadUri;

    public async Task<string> DownloadAsync(
        Uri uri,
        IProgress<(long Bytes, long Total)>? progress,
        CancellationToken cancellationToken = default)
    {
        Directory.CreateDirectory(_updateDirectory);
        var file = Path.Combine(_updateDirectory, Path.GetFileName(uri.LocalPath));
        if (File.Exists(file))
        {
            File.Delete(file);
        }

        using var response = await _http.GetAsync(
            uri, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
        response.EnsureSuccessStatusCode();
        var total = response.Content.Headers.ContentLength ?? -1;
        await using var source = await response.Content.ReadAsStreamAsync(cancellationToken);
        await using var destination = File.Create(file);
        var buffer = new byte[81920];
        long done = 0;
        int read;
        while ((read = await source.ReadAsync(buffer, cancellationToken)) > 0)
        {
            await destination.WriteAsync(buffer.AsMemory(0, read), cancellationToken);
            done += read;
            progress?.Report((done, total));
        }
        return file;
    }

    /// <summary>Extracts the downloaded package and starts a helper that replaces files after exit.</summary>
    public string Apply(string zipPath)
    {
        Directory.CreateDirectory(_updateDirectory);
        var extractDirectory = Path.Combine(_updateDirectory, "extracted");
        if (Directory.Exists(extractDirectory))
        {
            Directory.Delete(extractDirectory, true);
        }
        ZipFile.ExtractToDirectory(zipPath, extractDirectory);

        var appDirectory = AppContext.BaseDirectory.TrimEnd('\\', '/');
        var executable = Path.Combine(appDirectory, "BetterRNP.exe");
        var scriptPath = Path.Combine(_updateDirectory, "apply_update.ps1");
        var script = new StringBuilder()
            .AppendLine("$ErrorActionPreference = 'Stop'")
            .AppendLine($"$targetPid = {Environment.ProcessId}")
            .AppendLine("$alive = $true")
            .AppendLine("for ($i = 0; $i -lt 120; $i++) {")
            .AppendLine("    if (-not (Get-Process -Id $targetPid -ErrorAction SilentlyContinue)) { $alive = $false; break }")
            .AppendLine("    Start-Sleep -Milliseconds 500")
            .AppendLine("}")
            .AppendLine("if ($alive) { exit 1 }")
            .AppendLine("Start-Sleep -Seconds 1")
            .AppendLine($"Copy-Item -Path (Join-Path '{Escape(extractDirectory)}' '*') -Destination '{Escape(appDirectory)}' -Recurse -Force")
            .AppendLine($"Start-Process '{Escape(executable)}'")
            .AppendLine("Remove-Item -LiteralPath $PSCommandPath -Force")
            .ToString();
        File.WriteAllText(scriptPath, script, new UTF8Encoding(false));

        Process.Start(new ProcessStartInfo
        {
            FileName = "powershell.exe",
            Arguments = $"-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File \"{scriptPath}\"",
            UseShellExecute = false,
            CreateNoWindow = true
        });
        _log.Write($"准备应用更新包：{zipPath}", "UPDATE_APPLY");
        return scriptPath;

        static string Escape(string path) => path.Replace("'", "''");
    }

    public static UpdateAssetKind DetectCurrentInstallationKind()
    {
        // Single-file self-contained apps intentionally return an empty location here.
#pragma warning disable IL3000
        var coreLibPath = typeof(object).Assembly.Location;
#pragma warning restore IL3000
        return DetectInstallationKind(coreLibPath, AppContext.BaseDirectory);
    }

    /// <summary>
    /// Framework-dependent apps load CoreLib from the shared .NET installation. Self-contained
    /// apps load it from the application directory; single-file self-contained apps may expose
    /// an empty CoreLib location.
    /// </summary>
    public static UpdateAssetKind DetectInstallationKind(string? coreLibPath, string appDirectory)
    {
        if (string.IsNullOrWhiteSpace(coreLibPath))
        {
            return UpdateAssetKind.SelfContained;
        }
        var coreLib = Path.GetFullPath(coreLibPath);
        var appDir = Path.GetFullPath(appDirectory)
            .TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar)
            + Path.DirectorySeparatorChar;
        return coreLib.StartsWith(appDir, StringComparison.OrdinalIgnoreCase)
            ? UpdateAssetKind.SelfContained
            : UpdateAssetKind.FrameworkDependent;
    }

    public static bool IsSelfContained()
        => DetectCurrentInstallationKind() == UpdateAssetKind.SelfContained;

    private static Version Normalize(Version version)
        => new(version.Major, version.Minor, Math.Max(version.Build, 0));
}
