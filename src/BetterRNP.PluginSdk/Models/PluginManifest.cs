using System.Text.Json.Serialization;

namespace BetterRNP.PluginSdk.Models;

public enum PluginType
{
    Command = 0,
    PickerMode = 1,
    Mixed = 2,
    Core = 3
}

public sealed class PluginManifest
{
    [JsonPropertyName("id")]
    public string Id { get; set; } = string.Empty;

    [JsonPropertyName("name")]
    public string Name { get; set; } = string.Empty;

    [JsonPropertyName("version")]
    public string Version { get; set; } = "1.0.0";

    [JsonPropertyName("author")]
    public string Author { get; set; } = string.Empty;

    [JsonPropertyName("type")]
    [JsonConverter(typeof(JsonStringEnumConverter))]
    public PluginType Type { get; set; } = PluginType.Command;

    [JsonPropertyName("assembly")]
    public string Assembly { get; set; } = string.Empty;

    [JsonPropertyName("entryType")]
    public string EntryType { get; set; } = string.Empty;

    [JsonPropertyName("apiVersion")]
    public string ApiVersion { get; set; } = PluginApiVersion.Current;

    [JsonPropertyName("description")]
    public string Description { get; set; } = string.Empty;
}

public static class PluginApiVersion
{
    public const string Current = "1.0";
}
