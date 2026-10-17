using System.Text.Json;
using System.Text.Json.Serialization;

namespace BetterRNP.Core;

public sealed class UpdateSettings
{
    public bool CheckOnStartup { get; set; } = true;
    public UpdateSource Source { get; set; } = UpdateSource.Official;
}

public sealed class UpdateSettingsService
{
    private static readonly JsonSerializerOptions Options = new()
    {
        WriteIndented = true,
        PropertyNameCaseInsensitive = true,
        Converters = { new JsonStringEnumConverter() }
    };

    private readonly string _path;

    public UpdateSettingsService(AppPaths paths)
    {
        _path = Path.Combine(paths.DataDir, "update.json");
    }

    public string SettingsPath => _path;

    public UpdateSettings Load()
    {
        try
        {
            return File.Exists(_path)
                ? JsonSerializer.Deserialize<UpdateSettings>(File.ReadAllText(_path), Options)
                  ?? new UpdateSettings()
                : new UpdateSettings();
        }
        catch
        {
            return new UpdateSettings();
        }
    }

    public void Save(UpdateSettings settings)
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(_path)!);
            File.WriteAllText(_path, JsonSerializer.Serialize(settings, Options));
        }
        catch
        {
            // Update preferences are non-critical.
        }
    }
}
