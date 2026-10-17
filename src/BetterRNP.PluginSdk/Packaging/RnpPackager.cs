using System.IO.Compression;
using System.Text;
using BetterRNP.PluginSdk.Models;

namespace BetterRNP.PluginSdk.Packaging;

/// <summary>
/// .rnpp (BetterRNP Plugin Package) is a zip package containing plugin.json and the
/// declared entry assembly. The package layout follows the same simple convention as
/// the FuckETS .fep format, but uses the BetterRNP-specific .rnpp extension.
/// </summary>
public static class RnpPackager
{
    public const string ManifestFileName = "plugin.json";
    public const string Extension = ".rnpp";

    public static RnpReadResult ReadManifest(string packagePath)
    {
        if (string.IsNullOrWhiteSpace(packagePath) || !File.Exists(packagePath))
        {
            return RnpReadResult.Fail("插件文件不存在");
        }
        if (!Path.GetExtension(packagePath).Equals(Extension, StringComparison.OrdinalIgnoreCase))
        {
            return RnpReadResult.Fail($"扩展名必须为 {Extension}");
        }

        try
        {
            using var archive = ZipFile.OpenRead(packagePath);
            var manifestEntry = archive.GetEntry(ManifestFileName);
            if (manifestEntry is null)
            {
                return RnpReadResult.Fail($"缺少清单文件 {ManifestFileName}");
            }

            using var reader = new StreamReader(manifestEntry.Open(), Encoding.UTF8);
            var validation = ManifestValidator.TryParse(reader.ReadToEnd(), out var manifest);
            if (!validation.IsValid || manifest is null)
            {
                return RnpReadResult.Fail(validation.Error ?? "清单非法");
            }
            if (archive.GetEntry(manifest.Assembly) is null)
            {
                return RnpReadResult.Fail($"包内缺少入口程序集 {manifest.Assembly}");
            }
            return RnpReadResult.Ok(manifest);
        }
        catch (InvalidDataException ex)
        {
            return RnpReadResult.Fail($"包结构非法（不是有效 zip）：{ex.Message}");
        }
        catch (Exception ex)
        {
            return RnpReadResult.Fail($"读取 .rnpp 失败：{ex.Message}");
        }
    }

    /// <summary>Creates a .rnpp package from a directory containing plugin.json and its entry assembly.</summary>
    public static void Create(string sourceDirectory, string outputPath)
    {
        var manifestPath = Path.Combine(sourceDirectory, ManifestFileName);
        if (!File.Exists(manifestPath))
        {
            throw new FileNotFoundException($"清单缺失：{manifestPath}");
        }

        var validation = ManifestValidator.TryParse(File.ReadAllText(manifestPath), out var manifest);
        if (!validation.IsValid || manifest is null)
        {
            throw new InvalidOperationException($"清单非法：{validation.Error}");
        }

        var assemblyPath = Path.Combine(sourceDirectory, manifest.Assembly);
        if (!File.Exists(assemblyPath))
        {
            throw new FileNotFoundException($"入口程序集缺失：{assemblyPath}");
        }

        if (File.Exists(outputPath))
        {
            File.Delete(outputPath);
        }
        var outputDirectory = Path.GetDirectoryName(outputPath);
        if (!string.IsNullOrWhiteSpace(outputDirectory))
        {
            Directory.CreateDirectory(outputDirectory);
        }
        using var archive = ZipFile.Open(outputPath, ZipArchiveMode.Create);
        archive.CreateEntryFromFile(manifestPath, ManifestFileName);
        archive.CreateEntryFromFile(assemblyPath, manifest.Assembly);
    }
}

public sealed class RnpReadResult
{
    public bool Success { get; private init; }
    public string? Error { get; private init; }
    public PluginManifest? Manifest { get; private init; }

    public static RnpReadResult Ok(PluginManifest manifest)
        => new() { Success = true, Manifest = manifest };

    public static RnpReadResult Fail(string error)
        => new() { Success = false, Error = error };
}
