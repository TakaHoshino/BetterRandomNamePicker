using System.Text.Json;

namespace BetterRNP.PluginSdk.Models;

public sealed record ManifestValidation(bool IsValid, string? Error);

public static class ManifestValidator
{
    private static readonly JsonSerializerOptions Options = new()
    {
        PropertyNameCaseInsensitive = true
    };

    public static ManifestValidation TryParse(string json, out PluginManifest? manifest)
    {
        manifest = null;
        if (string.IsNullOrWhiteSpace(json))
        {
            return new ManifestValidation(false, "清单内容为空");
        }

        try
        {
            using var document = JsonDocument.Parse(json);
            if (document.RootElement.ValueKind != JsonValueKind.Object
                || !document.RootElement.TryGetProperty("type", out var type)
                || type.ValueKind != JsonValueKind.String)
            {
                return new ManifestValidation(false, "插件类型 type 缺失或非法");
            }
        }
        catch (Exception ex)
        {
            return new ManifestValidation(false, $"plugin.json 解析失败：{ex.Message}");
        }

        try
        {
            manifest = JsonSerializer.Deserialize<PluginManifest>(json, Options);
            return manifest is null
                ? new ManifestValidation(false, "无法解析 plugin.json")
                : Validate(manifest);
        }
        catch (Exception ex)
        {
            return new ManifestValidation(false, $"plugin.json 解析失败：{ex.Message}");
        }
    }

    public static ManifestValidation Validate(PluginManifest manifest)
    {
        if (string.IsNullOrWhiteSpace(manifest.Id))
        {
            return new ManifestValidation(false, "缺少插件 id");
        }
        if (string.IsNullOrWhiteSpace(manifest.Name))
        {
            return new ManifestValidation(false, "缺少插件名称 name");
        }
        if (string.IsNullOrWhiteSpace(manifest.Assembly))
        {
            return new ManifestValidation(false, "缺少入口程序集 assembly");
        }
        if (string.IsNullOrWhiteSpace(manifest.EntryType))
        {
            return new ManifestValidation(false, "缺少入口类型 entryType");
        }

        var currentParts = PluginApiVersion.Current.Split('.');
        var pluginParts = manifest.ApiVersion.Split('.');
        if (currentParts[0] != (pluginParts.Length > 0 ? pluginParts[0] : "0"))
        {
            return new ManifestValidation(false,
                $"插件 API 版本 {manifest.ApiVersion} 与主程序 {PluginApiVersion.Current} 不兼容");
        }
        return new ManifestValidation(true, null);
    }
}
